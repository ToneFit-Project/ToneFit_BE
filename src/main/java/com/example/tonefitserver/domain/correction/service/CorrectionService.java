package com.example.tonefitserver.domain.correction.service;

import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.TextSanitizer;
import com.example.tonefitserver.domain.correction.ai.AiCorrectionClient;
import com.example.tonefitserver.domain.correction.ai.AiCorrectionResult;
import com.example.tonefitserver.domain.correction.ai.AiFinalizeResult;
import com.example.tonefitserver.domain.correction.ai.AiStructureResult;
import com.example.tonefitserver.domain.correction.dto.ConfirmRequest;
import com.example.tonefitserver.domain.correction.dto.ConfirmResponse;
import com.example.tonefitserver.domain.correction.dto.CorrectionDetailResponse;
import com.example.tonefitserver.domain.correction.dto.CorrectionRequest;
import com.example.tonefitserver.domain.correction.dto.CorrectionResponse;
import com.example.tonefitserver.domain.correction.dto.EditRequest;
import com.example.tonefitserver.domain.correction.dto.EditResponse;
import com.example.tonefitserver.domain.correction.dto.FinalizeResponse;
import com.example.tonefitserver.domain.correction.dto.HistoryResponse;
import com.example.tonefitserver.domain.correction.dto.InProgressResponse;
import com.example.tonefitserver.domain.correction.dto.RecorrectRequest;
import com.example.tonefitserver.domain.correction.dto.RejectRequest;
import com.example.tonefitserver.domain.correction.dto.RejectResponse;
import com.example.tonefitserver.domain.correction.dto.SessionSummary;
import com.example.tonefitserver.domain.correction.dto.StructureCorrectionRequest;
import com.example.tonefitserver.domain.correction.dto.StructureCorrectionResponse;
import com.example.tonefitserver.domain.correction.model.Action;
import com.example.tonefitserver.domain.correction.model.CorrectionFeedback;
import com.example.tonefitserver.domain.correction.repository.CorrectionFeedbackRepository;
import com.example.tonefitserver.domain.event.EventService;
import com.example.tonefitserver.domain.event.EventType;
import com.example.tonefitserver.domain.prompt.PromptPurpose;
import com.example.tonefitserver.domain.prompt.PromptVersion;
import com.example.tonefitserver.domain.prompt.PromptVersionRepository;
import com.example.tonefitserver.domain.session.CorrectionSession;
import com.example.tonefitserver.domain.session.CorrectionSessionRepository;
import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Range;
import com.example.tonefitserver.domain.session.Receiver;
import com.example.tonefitserver.domain.session.Status;
import com.example.tonefitserver.domain.user.User;
import com.example.tonefitserver.domain.user.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CorrectionService {

    private static final int PREVIEW_LENGTH = 50;
    /** 세션 당 재교정 허용 횟수 (PM 결정). 초과 시 429 RECORRECT_LIMIT_EXCEEDED. */
    private static final int RECORRECT_LIMIT = 2;
    // listInProgress 응답에 포함될 status. 진행 중(in-flight 포함)·검토 대기·편집 단계 모두 노출.
    private static final List<Status> IN_PROGRESS_STATUSES = List.of(
            Status.IN_PROGRESS,
            Status.STRUCTURE_REVIEW,
            Status.STRUCTURING,
            Status.RECORRECTING,
            Status.FINALIZING,
            Status.EDITING
    );

    private final CorrectionSessionRepository sessionRepository;
    private final CorrectionFeedbackRepository feedbackRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final UserRepository userRepository;
    private final AiCorrectionClient aiClient;
    private final EventService eventService;
    private final PlatformTransactionManager transactionManager;

    /**
     * AI 호출(8~15초 블로킹)을 트랜잭션 밖에서 실행하기 위한 프로그래밍적 TX 경계 제어.
     * 각 public 메서드는 prepare(TX1) → AI 호출(TX 없음) → persist(TX2) 패턴.
     * 이렇게 분리하지 않으면 Hikari pool(max=10) 이 AI 호출 동안 점유되어 동시 10명 초과 시 즉시 병목.
     */
    private TransactionTemplate txTemplate;

    @PostConstruct
    void initTxTemplate() {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ====== 구조 교정 (선택 단계, 1차 교정 전) ======

    /**
     * 구조 교정 1회성 호출. 세션을 새로 만들고 구조 교정 AI 결과를 저장한 뒤 STRUCTURE_REVIEW 상태로 둔다.
     * 사용자가 결과 확인 후 {@link #initialAfterStructure} 로 1차 교정 진행.
     */
    public StructureCorrectionResponse structureCorrect(Long userId, StructureCorrectionRequest req) {
        // TX1: 세션 생성 + STRUCTURING 진입
        AiStructureInput input = txTemplate.execute(status -> prepareStructure(userId, req));

        AiStructureResult result;
        try {
            result = aiClient.correctStructure(input.promptContent(), input.receiver(),
                    input.purpose(), input.original());
        } catch (Exception e) {
            // 실패 시 세션 통째 삭제 (1회성이라 revert 의미 없음)
            txTemplate.executeWithoutResult(status -> deleteFailedSession(input.sessionId()));
            throw new BusinessException(ErrorType.AI_SERVICE_ERROR,
                    ErrorType.AI_SERVICE_ERROR.getMessage(), input.sessionId(), e);
        }

        // TX2: structure_corrected 저장 + status STRUCTURE_REVIEW
        return txTemplate.execute(status -> persistStructureResult(input.sessionId(), result));
    }

    private AiStructureInput prepareStructure(Long userId, StructureCorrectionRequest req) {
        User user = loadUser(userId);
        CorrectionSession session = CorrectionSession.builder()
                .user(user)
                .status(Status.STRUCTURING)
                .build();
        // updateDraft 시그니처가 (receiver, purpose, original) — subject 없음
        session.updateDraft(req.receiverType(), req.purpose(), TextSanitizer.sanitize(req.originalEmail()));
        // 구조 교정 prompt — active STRUCTURE 가 있으면 사용, 없으면 GeminiAiCorrectionClient default 사용
        PromptVersion prompt = activeStructurePrompt();
        if (prompt != null) {
            session.updateInitialPromptVersion(prompt); // initial_prompt_ver_id 자리에 임시 저장 (구조 단계 prompt 추적)
        }
        CorrectionSession saved = sessionRepository.save(session);

        return new AiStructureInput(
                saved.getId(),
                prompt != null ? prompt.getContent() : null,
                saved.getReceiverType(),
                saved.getPurpose(),
                saved.getOriginal()
        );
    }

    private StructureCorrectionResponse persistStructureResult(Long sessionId, AiStructureResult result) {
        CorrectionSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorType.NOT_FOUND, "세션을 찾을 수 없습니다."));
        session.updateStructureCorrected(result.structureCorrected());
        session.updateStatus(Status.STRUCTURE_REVIEW);
        // 구조 교정 단계의 prompt 는 임시로 initial_prompt_ver_id 자리에 저장했으므로,
        // 다음 단계(initial) 에서 active INITIAL prompt 로 덮어쓰여짐.
        return new StructureCorrectionResponse(
                session.getId(), result.structureCorrected(), session.getCreatedAt());
    }

    /**
     * 구조 교정 완료된 세션을 기반으로 1차 교정 실행.
     * AI 입력으로 session.original 대신 session.structureCorrected 사용 (effectiveOriginal).
     */
    public CorrectionResponse initialAfterStructure(Long userId, Long sessionId) {
        // TX1: STRUCTURE_REVIEW 검증 + STRUCTURING 또는 IN_PROGRESS... 가 아닌 별도 in-flight 필요
        // 여기선 RECORRECTING 와 비슷하게 한시적 마커 도입 대신, STRUCTURE_REVIEW → IN_PROGRESS 직접 전환 + 동시성은 SESSION_LOCKED 게이트로 차단
        AiCorrectionInput input = txTemplate.execute(status -> prepareInitialAfterStructure(userId, sessionId));

        AiCorrectionResult result;
        try {
            result = aiClient.correct(input.promptContent(), input.receiver(), input.purpose(),
                    input.original(), input.protectedRanges());
        } catch (Exception e) {
            // 실패 시 STRUCTURE_REVIEW 로 복원 (재시도 가능 상태)
            txTemplate.executeWithoutResult(status -> revertSessionToStructureReview(sessionId));
            throw new BusinessException(ErrorType.AI_SERVICE_ERROR,
                    ErrorType.AI_SERVICE_ERROR.getMessage(), sessionId, e);
        }

        return txTemplate.execute(status -> persistInitialCorrectionResult(sessionId, result));
    }

    private AiCorrectionInput prepareInitialAfterStructure(Long userId, Long sessionId) {
        User user = loadUser(userId);
        CorrectionSession session = findOwnedSession(user.getId(), sessionId);
        // STRUCTURE_REVIEW 만 허용. 다른 상태면 분기 처리
        Status current = session.getStatus();
        if (current == Status.STRUCTURING) {
            throw new BusinessException(ErrorType.SESSION_LOCKED);
        }
        if (current != Status.STRUCTURE_REVIEW) {
            throw new BusinessException(ErrorType.INVALID_REQUEST,
                    "구조 교정이 선행되지 않았거나 이미 1차 교정이 완료된 세션입니다.");
        }
        // 1차 교정 진입 — IN_PROGRESS 로 전환 (in-flight 마커는 별도 추가 안 함, SESSION_LOCKED 검사는 위 status 체크가 대체)
        session.updateStatus(Status.IN_PROGRESS);
        session.updateInitialPromptVersion(activeInitialPrompt());

        return new AiCorrectionInput(
                sessionId,
                session.getInitialPromptVersion() != null ? session.getInitialPromptVersion().getContent() : null,
                session.getReceiverType(),
                session.getPurpose(),
                session.effectiveOriginal(),
                session.getProtectedRanges()
        );
    }

    private void revertSessionToStructureReview(Long sessionId) {
        sessionRepository.findById(sessionId).ifPresent(s -> s.updateStatus(Status.STRUCTURE_REVIEW));
    }

    private PromptVersion activeStructurePrompt() {
        return promptVersionRepository
                .findFirstByPurposeAndIsActiveTrueOrderByCreatedAtDesc(PromptPurpose.STRUCTURE)
                .orElse(null);
    }

    // ====== 일반 교정 (기존) ======

    public CorrectionResponse correct(Long userId, CorrectionRequest req) {
        // TX1: 새 세션 생성 + IN_PROGRESS 진입 + AI 입력 스냅샷
        // (draft 는 FE local storage 에서 관리하므로 BE 는 1차 교정 시점에 처음 세션을 만듦)
        AiCorrectionInput input = txTemplate.execute(status -> prepareInitialCorrection(userId, req));

        // AI 호출 (트랜잭션 밖)
        AiCorrectionResult result;
        try {
            result = aiClient.correct(input.promptContent(), input.receiver(), input.purpose(),
                    input.original(), input.protectedRanges());
        } catch (Exception e) {
            // TX3: 실패 시 세션 통째 삭제. 사용자 본문(개인정보) 누적 차단.
            // FE-local draft 정책상 사용자가 재시도하면 동일 본문으로 새 요청을 보내므로 손실 없음.
            txTemplate.executeWithoutResult(status -> deleteFailedSession(input.sessionId()));
            throw new BusinessException(ErrorType.AI_SERVICE_ERROR,
                    ErrorType.AI_SERVICE_ERROR.getMessage(), input.sessionId(), e);
        }

        // TX2: 결과 영속화 + 이벤트 기록
        return txTemplate.execute(status -> persistInitialCorrectionResult(input.sessionId(), result));
    }

    private AiCorrectionInput prepareInitialCorrection(Long userId, CorrectionRequest req) {
        User user = loadUser(userId);
        CorrectionSession session = CorrectionSession.builder()
                .user(user)
                .status(Status.IN_PROGRESS)
                .build();
        session.updateDraft(
                req.receiverType(),
                req.purpose(),
                TextSanitizer.sanitize(req.originalEmail()));
        session.updateProtectedRanges(toRanges(req.protectedRanges()));
        session.updateInitialPromptVersion(activeInitialPrompt());
        CorrectionSession saved = sessionRepository.save(session);

        return new AiCorrectionInput(
                saved.getId(),
                saved.getInitialPromptVersion() != null ? saved.getInitialPromptVersion().getContent() : null,
                saved.getReceiverType(),
                saved.getPurpose(),
                saved.effectiveOriginal(),  // 신규 세션은 structure_corrected null 이라 사실상 original 과 동일
                saved.getProtectedRanges()
        );
    }

    private void deleteFailedSession(Long sessionId) {
        // 첫 1차 교정 실패 — feedback 은 아직 없을 가능성이 높지만 안전망으로 명시적 삭제.
        feedbackRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
    }

    private CorrectionResponse persistInitialCorrectionResult(Long sessionId, AiCorrectionResult result) {
        CorrectionSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorType.NOT_FOUND, "세션을 찾을 수 없습니다."));

        // 옛 feedback 삭제 + 새 feedback 저장을 같은 TX 안에서 원자적으로 수행.
        // (TX 분리 전에는 prepare 단계에서 삭제했지만, AI 실패 시 옛 데이터 손실되므로 persist 단계로 이동)
        feedbackRepository.deleteBySessionId(sessionId);

        List<CorrectionFeedback> feedbacks = result.changes().stream()
                .map(c -> CorrectionFeedback.builder()
                        .user(session.getUser())
                        .session(session)
                        .index(c.index())
                        .start(c.start())
                        .end(c.end())
                        .original(c.original())
                        .corrected(c.corrected())
                        .reason(c.reason())
                        .label(c.label())
                        .confidence(c.confidence())
                        .appliedRules(c.appliedRules())
                        .build())
                .toList();
        feedbackRepository.saveAll(feedbacks);

        eventService.record(session.getUser(), EventType.STARTED, session,
                Map.of("input_length", session.getOriginal() == null ? 0 : session.getOriginal().length()));

        return toCorrectionResponse(session, result);
    }

    public CorrectionResponse recorrect(Long userId, Long sessionId, RecorrectRequest req) {
        // TX1: 검증 + 상태를 RECORRECTING 으로 전환 (in-flight 마커)
        AiCorrectionInput input = txTemplate.execute(status -> prepareRecorrect(userId, sessionId, req));

        // AI 호출 (트랜잭션 밖)
        AiCorrectionResult result;
        try {
            result = aiClient.correct(input.promptContent(), input.receiver(), input.purpose(),
                    input.original(), input.protectedRanges());
        } catch (Exception e) {
            // TX3: AI 실패 시 status 를 IN_PROGRESS 로 revert (재시도 가능 상태로 복귀)
            txTemplate.executeWithoutResult(status -> revertSessionToInProgress(sessionId));
            throw new BusinessException(ErrorType.AI_SERVICE_ERROR,
                    ErrorType.AI_SERVICE_ERROR.getMessage(), sessionId, e);
        }

        // TX2: 세션 갱신 + feedback 교체 + status → IN_PROGRESS
        return txTemplate.execute(status -> persistRecorrectResult(sessionId, req, result));
    }

    private AiCorrectionInput prepareRecorrect(Long userId, Long sessionId, RecorrectRequest req) {
        User user = loadUser(userId);
        CorrectionSession session = findOwnedSession(user.getId(), sessionId);
        ensureInProgressOrLocked(session, "재교정");
        if (session.getRecorrectCount() >= RECORRECT_LIMIT) {
            throw new BusinessException(ErrorType.RECORRECT_LIMIT_EXCEEDED);
        }
        session.updateStatus(Status.RECORRECTING);

        PromptVersion prompt = activeInitialPrompt();
        return new AiCorrectionInput(
                sessionId,
                prompt != null ? prompt.getContent() : null,
                req.receiverType(),
                req.purpose(),
                session.effectiveOriginal(),  // 구조 교정 적용 세션이면 structure_corrected 사용
                session.getProtectedRanges()
        );
    }

    private CorrectionResponse persistRecorrectResult(Long sessionId, RecorrectRequest req, AiCorrectionResult result) {
        CorrectionSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorType.NOT_FOUND, "세션을 찾을 수 없습니다."));

        session.updateReceiverPurpose(req.receiverType(), req.purpose());
        session.updateInitialPromptVersion(activeInitialPrompt());
        session.updateStatus(Status.IN_PROGRESS);
        session.incrementRecorrectCount();

        feedbackRepository.deleteBySessionId(sessionId);

        List<CorrectionFeedback> feedbacks = result.changes().stream()
                .map(c -> CorrectionFeedback.builder()
                        .user(session.getUser())
                        .session(session)
                        .index(c.index())
                        .start(c.start())
                        .end(c.end())
                        .original(c.original())
                        .corrected(c.corrected())
                        .reason(c.reason())
                        .label(c.label())
                        .confidence(c.confidence())
                        .appliedRules(c.appliedRules())
                        .build())
                .toList();
        feedbackRepository.saveAll(feedbacks);

        return toCorrectionResponse(session, result);
    }

    /**
     * 동시 진입 차단 게이트.
     * - IN_PROGRESS: 통과
     * - RECORRECTING/FINALIZING: 다른 작업이 진행 중 → 409 SESSION_LOCKED
     * - 그 외(EDITING/CONFIRMED/DRAFT): 잘못된 상태 → 400
     */
    private void ensureInProgressOrLocked(CorrectionSession session, String operation) {
        Status current = session.getStatus();
        if (current == Status.IN_PROGRESS) return;
        if (current == Status.RECORRECTING || current == Status.FINALIZING) {
            throw new BusinessException(ErrorType.SESSION_LOCKED);
        }
        throw new BusinessException(ErrorType.INVALID_REQUEST,
                "IN_PROGRESS 상태에서만 " + operation + "할 수 있습니다.");
    }

    private void revertSessionToInProgress(Long sessionId) {
        sessionRepository.findById(sessionId).ifPresent(s -> s.updateStatus(Status.IN_PROGRESS));
    }

    @Transactional
    public RejectResponse rejectFeedback(Long userId, Long sessionId, RejectRequest req) {
        User user = loadUser(userId);
        CorrectionSession session = findOwnedSession(user.getId(), sessionId);
        if (session.getStatus() != Status.IN_PROGRESS) {
            throw new BusinessException(ErrorType.INVALID_REQUEST,
                    "IN_PROGRESS 상태에서만 교정을 거절할 수 있습니다.");
        }
        CorrectionFeedback feedback = feedbackRepository.findBySessionIdAndIndex(sessionId, req.index())
                .orElseThrow(() -> new BusinessException(ErrorType.NOT_FOUND, "교정 건을 찾을 수 없습니다."));
        feedback.reject(req.reasonPrimary(), req.reasonSecondary(),
                TextSanitizer.sanitize(req.reasonText()));

        Map<String, Object> payload = new HashMap<>();
        payload.put("correction_index", feedback.getIndex());
        payload.put("reason_code", req.reasonPrimary() == null ? null : req.reasonPrimary().name());
        payload.put("recipient_type", session.getReceiverType() == null ? null : session.getReceiverType().name());
        eventService.record(user, EventType.REJECTED, session, payload);

        return new RejectResponse(session.getId(), feedback.getIndex(), Action.REJECTED, feedback.getUpdatedAt());
    }

    public FinalizeResponse finalize(Long userId, Long sessionId) {
        // TX1: 검증 + 미처리 feedback 자동 수락 + final 프롬프트 지정 + 머지 산출 + status → FINALIZING
        AiFinalizeInput input = txTemplate.execute(status -> prepareFinalize(userId, sessionId));

        // AI 호출 (트랜잭션 밖)
        AiFinalizeResult result;
        try {
            result = aiClient.finalizePolish(input.promptContent(), input.receiver(), input.purpose(),
                    input.mergedText(), input.protectedRanges());
        } catch (Exception e) {
            // TX3: AI 실패 시 status 를 IN_PROGRESS 로 revert (auto-accept 는 멱등이라 유지해도 무방)
            txTemplate.executeWithoutResult(status -> revertSessionToInProgress(sessionId));
            throw new BusinessException(ErrorType.AI_SERVICE_ERROR,
                    ErrorType.AI_SERVICE_ERROR.getMessage(), sessionId, e);
        }

        // TX2: AI 결과 저장 + 상태 EDITING 전환 + 이벤트
        return txTemplate.execute(status -> persistFinalizeResult(sessionId, result));
    }

    private AiFinalizeInput prepareFinalize(Long userId, Long sessionId) {
        User user = loadUser(userId);
        CorrectionSession session = findOwnedSession(user.getId(), sessionId);
        ensureInProgressOrLocked(session, "최종 다듬기");

        List<CorrectionFeedback> feedbacks = feedbackRepository.findBySessionIdOrderByIndexAsc(sessionId);
        feedbacks.stream().filter(f -> f.getAction() == null).forEach(CorrectionFeedback::accept);

        PromptVersion finalPrompt = activeFinalPrompt();
        session.updateFinalPromptVersion(finalPrompt);
        session.updateStatus(Status.FINALIZING);

        // 구조 교정 적용 세션이면 effectiveOriginal(structure_corrected) 기준으로 머지.
        // feedback 의 start/end 좌표도 structure_corrected 기준이라 일관됨.
        MergeResult merge = mergeForFinalize(session.effectiveOriginal(), session.getProtectedRanges(), feedbacks);

        return new AiFinalizeInput(
                sessionId,
                finalPrompt != null ? finalPrompt.getContent() : null,
                session.getReceiverType(),
                session.getPurpose(),
                merge.mergedText(),
                merge.protectedRanges()
        );
    }

    private FinalizeResponse persistFinalizeResult(Long sessionId, AiFinalizeResult result) {
        CorrectionSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorType.NOT_FOUND, "세션을 찾을 수 없습니다."));

        session.updateAiResult(result.aiFinal(), result.aiSubject());
        session.updateStatus(Status.EDITING);

        eventService.record(session.getUser(), EventType.COMPLETED, session, null);

        return new FinalizeResponse(session.getId(), session.getStatus(),
                session.getAiFinal(), session.getAiSubject(), session.getCreatedAt());
    }

    @Transactional
    public EditResponse editFinal(Long userId, Long sessionId, EditRequest req) {
        User user = loadUser(userId);
        CorrectionSession session = findOwnedSession(user.getId(), sessionId);
        if (session.getStatus() != Status.EDITING) {
            throw new BusinessException(ErrorType.INVALID_REQUEST, "EDITING 상태에서만 편집할 수 있습니다.");
        }
        session.updateUserEdit(
                TextSanitizer.sanitize(req.userFinal()),
                TextSanitizer.sanitize(req.userSubject()));
        return new EditResponse(session.getId(), session.getStatus(), session.getUpdatedAt());
    }

    @Transactional
    public ConfirmResponse confirmFinal(Long userId, Long sessionId, ConfirmRequest req) {
        User user = loadUser(userId);
        CorrectionSession session = findOwnedSession(user.getId(), sessionId);
        if (session.getStatus() != Status.EDITING) {
            throw new BusinessException(ErrorType.INVALID_REQUEST, "EDITING 상태에서만 확정할 수 있습니다.");
        }
        boolean edited = req != null
                && (req.userFinal() != null || req.userSubject() != null);
        if (req != null) {
            session.updateUserEdit(
                    TextSanitizer.sanitize(req.userFinal()),
                    TextSanitizer.sanitize(req.userSubject()));
        }
        session.updateStatus(Status.CONFIRMED);

        eventService.record(user, EventType.COPIED, session, Map.of("edited", edited));

        return new ConfirmResponse(session.getId(), session.getStatus(), session.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public InProgressResponse listInProgress(Long userId) {
        User user = loadUser(userId);
        List<SessionSummary> summaries = sessionRepository
                .findByUserIdAndStatusIn(user.getId(), IN_PROGRESS_STATUSES).stream()
                .map(this::toSessionSummary)
                .toList();
        return new InProgressResponse(summaries);
    }

    @Transactional(readOnly = true)
    public HistoryResponse listHistory(Long userId, int page, int size,
                                       Receiver receiverType, Purpose purpose) {
        User user = loadUser(userId);
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size);
        Page<CorrectionSession> result = sessionRepository
                .searchHistory(user.getId(), Status.CONFIRMED, receiverType, purpose, pageable);
        List<SessionSummary> summaries = result.getContent().stream()
                .map(this::toSessionSummary)
                .toList();
        return new HistoryResponse((int) result.getTotalElements(), page, size, summaries);
    }

    @Transactional(readOnly = true)
    public CorrectionDetailResponse getDetail(Long userId, Long sessionId) {
        User user = loadUser(userId);
        CorrectionSession session = findOwnedSession(user.getId(), sessionId);
        List<CorrectionDetailResponse.FeedbackItem> feedbacks = feedbackRepository
                .findBySessionIdOrderByIndexAsc(sessionId).stream()
                .map(f -> new CorrectionDetailResponse.FeedbackItem(
                        f.getIndex(), f.getStart(), f.getEnd(),
                        f.getOriginal(), f.getCorrected(), f.getReason(),
                        f.getLabel(), f.getConfidence(), f.getAppliedRules(),
                        f.getAction(), f.getReasonPrimary(), f.getReasonSecondary(), f.getReasonText()))
                .toList();

        return new CorrectionDetailResponse(
                session.getId(),
                session.getReceiverType(),
                session.getPurpose(),
                session.getSubject(),
                session.getOriginal(),
                session.getStructureCorrected(),
                session.getAiFinal(),
                session.getUserFinal(),
                session.getAiSubject(),
                session.getUserSubject(),
                session.getStatus(),
                feedbacks,
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    private User loadUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorType.UNAUTHORIZED);
        }
        return userRepository.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.UNAUTHORIZED));
    }

    private CorrectionSession findOwnedSession(Long userId, Long sessionId) {
        CorrectionSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorType.NOT_FOUND, "세션을 찾을 수 없습니다."));
        if (!session.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorType.FORBIDDEN);
        }
        return session;
    }

    private PromptVersion activeInitialPrompt() {
        return promptVersionRepository
                .findFirstByPurposeAndIsActiveTrueOrderByCreatedAtDesc(PromptPurpose.INITIAL)
                .orElse(null);
    }

    private PromptVersion activeFinalPrompt() {
        return promptVersionRepository
                .findFirstByPurposeAndIsActiveTrueOrderByCreatedAtDesc(PromptPurpose.FINAL)
                .orElse(null);
    }

    private List<Range> toRanges(List<CorrectionRequest.ProtectedRange> ranges) {
        if (ranges == null) return List.of();
        return ranges.stream().map(r -> new Range(r.start(), r.end())).toList();
    }

    /**
     * finalize 단계 입력 텍스트와 거기서의 보호 구간을 함께 산출한다.
     *
     * <p>보호 구간은 다음 셋의 합집합이다:
     * <ul>
     *   <li>수락된(ACCEPTED) feedback의 corrected 텍스트가 들어간 위치</li>
     *   <li>거절된(REJECTED) feedback의 original 텍스트가 그대로 남아 있는 위치</li>
     *   <li>FE가 처음에 지정한 session.protectedRanges (merged 좌표로 변환)</li>
     * </ul>
     */
    private MergeResult mergeForFinalize(String original,
                                         List<Range> originalProtected,
                                         List<CorrectionFeedback> feedbacks) {
        if (original == null) return new MergeResult(null, List.of());

        List<CorrectionFeedback> sorted = feedbacks.stream()
                .sorted((a, b) -> Integer.compare(a.getStart(), b.getStart()))
                .toList();

        StringBuilder out = new StringBuilder();
        List<Range> protectedInMerged = new ArrayList<>();
        int origCursor = 0;
        for (CorrectionFeedback f : sorted) {
            if (f.getStart() < origCursor) continue;
            out.append(original, origCursor, f.getStart());
            int atomStart = out.length();
            String replacement = f.getAction() == Action.ACCEPTED ? f.getCorrected() : f.getOriginal();
            out.append(replacement);
            int atomEnd = out.length();
            if (atomEnd > atomStart) {
                protectedInMerged.add(new Range(atomStart, atomEnd));
            }
            origCursor = f.getEnd();
        }
        if (origCursor < original.length()) {
            out.append(original, origCursor, original.length());
        }

        // FE가 지정했던 원문 보호 구간을 merged 좌표로 변환해 추가
        if (originalProtected != null) {
            for (Range r : originalProtected) {
                int ms = translateOrigToMerged(r.getStart(), sorted);
                int me = translateOrigToMerged(r.getEnd(), sorted);
                if (ms < me) protectedInMerged.add(new Range(ms, me));
            }
        }

        return new MergeResult(out.toString(), coalesceRanges(protectedInMerged));
    }

    /** original 텍스트의 위치를 merged 텍스트 좌표로 변환한다. */
    private int translateOrigToMerged(int origPos, List<CorrectionFeedback> sortedFeedbacks) {
        int delta = 0;
        for (CorrectionFeedback f : sortedFeedbacks) {
            if (f.getEnd() <= origPos) {
                String replacement = f.getAction() == Action.ACCEPTED ? f.getCorrected() : f.getOriginal();
                delta += replacement.length() - (f.getEnd() - f.getStart());
            } else if (f.getStart() < origPos) {
                return f.getStart() + delta;
            } else {
                break;
            }
        }
        return origPos + delta;
    }

    private List<Range> coalesceRanges(List<Range> ranges) {
        if (ranges.isEmpty()) return List.of();
        List<Range> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(Range::getStart));
        List<Range> result = new ArrayList<>();
        Range cur = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Range nxt = sorted.get(i);
            if (nxt.getStart() <= cur.getEnd()) {
                cur = new Range(cur.getStart(), Math.max(cur.getEnd(), nxt.getEnd()));
            } else {
                result.add(cur);
                cur = nxt;
            }
        }
        result.add(cur);
        return result;
    }

    private record MergeResult(String mergedText, List<Range> protectedRanges) {
    }

    /**
     * TX1 → AI 호출 → TX2 사이에 entity 를 들고 다닐 수 없으므로 (트랜잭션 종료 후 detach) 값 객체로 스냅샷.
     * correct/recorrect 공통.
     */
    private record AiCorrectionInput(
            Long sessionId,
            String promptContent,
            Receiver receiver,
            Purpose purpose,
            String original,
            List<Range> protectedRanges
    ) {
    }

    private record AiFinalizeInput(
            Long sessionId,
            String promptContent,
            Receiver receiver,
            Purpose purpose,
            String mergedText,
            List<Range> protectedRanges
    ) {
    }

    /** 구조 교정 단계의 AI 입력 스냅샷. protected_ranges 미지원. */
    private record AiStructureInput(
            Long sessionId,
            String promptContent,
            Receiver receiver,
            Purpose purpose,
            String original
    ) {
    }

    private CorrectionResponse toCorrectionResponse(CorrectionSession s, AiCorrectionResult r) {
        List<AiCorrectionResult.Change> changes = r.changes();
        // Gemini 가 보낸 corrected_email 대신 원문 + sanitize 통과한 changes 로 재조립.
        // 이유: sanitize 에서 drop 된 변경(콤마 묶음, 매칭 실패 등)이 Gemini 응답에는 적용되어 있지만
        // 우리 changes 배열에는 없는 silent change 가 됨. 사용자 화면이 changes 와 어긋나 보임.
        // 재조립으로 changes 와 corrected_email 일관성 확보. Gemini 의 부수적 polishing(미세 공백 정리 등)은 손실됨.
        String correctedEmail = reconstructCorrected(s.effectiveOriginal(), changes);

        List<CorrectionResponse.ChangeItem> items = changes.stream()
                .map(c -> new CorrectionResponse.ChangeItem(
                        c.index(), c.start(), c.end(), c.original(), c.corrected(), c.reason(),
                        c.label(), c.confidence(), c.appliedRules(), null))
                .toList();
        return new CorrectionResponse(s.getId(), correctedEmail, items, s.getUpdatedAt());
    }

    /** original 에 sanitize 통과한 changes 를 start 오름차순으로 substitute 해서 corrected_email 재조립. */
    private String reconstructCorrected(String original, List<AiCorrectionResult.Change> changes) {
        if (original == null) return null;
        if (changes == null || changes.isEmpty()) return original;

        List<AiCorrectionResult.Change> sorted = changes.stream()
                .sorted(Comparator.comparingInt(AiCorrectionResult.Change::start))
                .toList();

        StringBuilder sb = new StringBuilder(original.length());
        int cursor = 0;
        for (AiCorrectionResult.Change c : sorted) {
            if (c.start() < cursor) continue; // overlap 방어
            if (c.start() > original.length() || c.end() > original.length()) continue;
            sb.append(original, cursor, c.start());
            sb.append(c.corrected());
            cursor = c.end();
        }
        if (cursor < original.length()) {
            sb.append(original, cursor, original.length());
        }
        return sb.toString();
    }

    private SessionSummary toSessionSummary(CorrectionSession s) {
        String preview = s.getOriginal() == null ? null
                : s.getOriginal().substring(0, Math.min(PREVIEW_LENGTH, s.getOriginal().length()));
        return new SessionSummary(s.getId(), s.getReceiverType(), s.getPurpose(),
                s.getSubject(), s.getStatus(), preview, s.getCreatedAt());
    }
}
