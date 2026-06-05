package com.example.tonefitserver.domain.correction.service;

import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.TextSanitizer;
import com.example.tonefitserver.core.security.UserRateLimiter;
import com.example.tonefitserver.domain.correction.ai.AiCorrectionClient;
import com.example.tonefitserver.domain.correction.ai.AiCorrectionResult;
import com.example.tonefitserver.domain.correction.dto.ConfirmRequest;
import com.example.tonefitserver.domain.correction.dto.ConfirmResponse;
import com.example.tonefitserver.domain.correction.dto.CorrectionDetailResponse;
import com.example.tonefitserver.domain.correction.dto.CorrectionRequest;
import com.example.tonefitserver.domain.correction.dto.CorrectionResponse;
import com.example.tonefitserver.domain.correction.dto.HistoryResponse;
import com.example.tonefitserver.domain.correction.dto.InProgressResponse;
import com.example.tonefitserver.domain.correction.dto.RejectRequest;
import com.example.tonefitserver.domain.correction.dto.RejectResponse;
import com.example.tonefitserver.domain.correction.dto.SessionSummary;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 교정 도메인 서비스. v0.5 부터 흐름이 단순화돼 다음 4개 동작만 제공:
 *
 * <ul>
 *   <li>{@link #correct} — 세션 생성 + AI 호출 + changes 저장 (IN_PROGRESS)</li>
 *   <li>{@link #rejectFeedback} — 개별 change 거부 + 이벤트 발화</li>
 *   <li>{@link #confirm} — 사용자 송신본 저장 + 미처리 changes 일괄 ACCEPTED + CONFIRMED 전환</li>
 *   <li>조회: {@link #listInProgress}, {@link #listHistory}, {@link #getDetail}</li>
 * </ul>
 *
 * <p>후교정/재교정/구조교정/제목생성/편집 단계는 모두 제거됐다 (별도 PR).
 */
@Service
@RequiredArgsConstructor
public class CorrectionService {

    private static final int PREVIEW_LENGTH = 50;

    private final CorrectionSessionRepository sessionRepository;
    private final CorrectionFeedbackRepository feedbackRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final UserRepository userRepository;
    private final AiCorrectionClient aiClient;
    private final EventService eventService;
    private final UserRateLimiter userRateLimiter;
    private final PlatformTransactionManager transactionManager;

    /**
     * AI 호출(8~15초 블로킹)을 트랜잭션 밖에서 실행하기 위한 프로그래밍적 TX 경계 제어.
     * prepare(TX1) → AI 호출(TX 없음) → persist(TX2) 패턴.
     * 이렇게 분리하지 않으면 Hikari pool(max=10) 이 AI 호출 동안 점유되어 동시 10명 초과 시 즉시 병목.
     */
    private TransactionTemplate txTemplate;

    @PostConstruct
    void initTxTemplate() {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ===== 교정 =====

    public CorrectionResponse correct(Long userId, CorrectionRequest req) {
        // TX1: 새 세션 생성 + IN_PROGRESS 진입 + AI 입력 스냅샷
        AiCorrectionInput input = txTemplate.execute(status -> prepareCorrection(userId, req));

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
        return txTemplate.execute(status -> persistCorrectionResult(input.sessionId(), result));
    }

    private AiCorrectionInput prepareCorrection(Long userId, CorrectionRequest req) {
        User user = loadUser(userId);
        // REQ-Limit: correction 은 인증 필수(정식만)이므로 계정 단위 한도를 항상 적용.
        // AI 호출 직전(세션 생성 전)에 차감 → 성공·실패 무관 1회 카운트 (FUNC-Lim-06).
        userRateLimiter.consume(UserRateLimiter.CATEGORY_CORRECTION, user.getId());
        CorrectionSession session = CorrectionSession.builder()
                .user(user)
                .receiverType(req.receiverType())
                .purpose(req.purpose())
                .original(TextSanitizer.sanitize(req.originalEmail()))
                .protectedRanges(toRanges(req.protectedRanges()))
                .status(Status.IN_PROGRESS)
                .build();
        // recipient_type 별 활성 CORRECTION prompt — V12 마이그레이션 후 (CORRECTION, recipient) 조합당 1개.
        session.updateInitialPromptVersion(activeCorrectionPrompt(req.receiverType()));
        CorrectionSession saved = sessionRepository.save(session);

        return new AiCorrectionInput(
                saved.getId(),
                saved.getInitialPromptVersion() != null ? saved.getInitialPromptVersion().getContent() : null,
                saved.getReceiverType(),
                saved.getPurpose(),
                saved.getOriginal(),
                saved.getProtectedRanges()
        );
    }

    private void deleteFailedSession(Long sessionId) {
        // 1차 교정 실패 — feedback 은 아직 없을 가능성이 높지만 안전망으로 명시적 삭제.
        feedbackRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
    }

    private CorrectionResponse persistCorrectionResult(Long sessionId, AiCorrectionResult result) {
        CorrectionSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorType.NOT_FOUND, "세션을 찾을 수 없습니다."));

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

        Map<String, Object> properties = new HashMap<>();
        properties.put("input_length", session.getOriginal() == null ? 0 : session.getOriginal().length());
        properties.put("recipient_type", session.getReceiverType() == null ? null : session.getReceiverType().name());
        properties.put("purpose", session.getPurpose() == null ? null : session.getPurpose().name());
        eventService.record(session.getUser(), EventType.STARTED, session, properties);

        return toCorrectionResponse(session, result);
    }

    // ===== 거부 =====

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

    // ===== 확정 =====

    /**
     * 사용자 송신 시점 호출. v0.52 명세 §3.3.
     * <ul>
     *   <li>현재 status 가 IN_PROGRESS 가 아니면 거부.</li>
     *   <li>미처리(action==null) feedback 은 일괄 ACCEPTED 로 기록.</li>
     *   <li>user_final 저장 + status CONFIRMED 전환.</li>
     *   <li>CORRECTION_COPIED 이벤트 발화 ({@code edited} = AI 제안과 차이 여부 — MVP 에선 true 단순).</li>
     * </ul>
     */
    @Transactional
    public ConfirmResponse confirm(Long userId, Long sessionId, ConfirmRequest req) {
        User user = loadUser(userId);
        CorrectionSession session = findOwnedSession(user.getId(), sessionId);
        if (session.getStatus() != Status.IN_PROGRESS) {
            throw new BusinessException(ErrorType.INVALID_REQUEST,
                    "IN_PROGRESS 상태에서만 확정할 수 있습니다.");
        }

        // 미처리 feedback 일괄 ACCEPTED.
        List<CorrectionFeedback> feedbacks = feedbackRepository.findBySessionIdOrderByIndexAsc(sessionId);
        feedbacks.stream().filter(f -> f.getAction() == null).forEach(CorrectionFeedback::accept);

        session.confirmWith(TextSanitizer.sanitize(req.userFinal()));

        // edited 플래그 — 사용자가 우리 추천 그대로 송신했는지 여부의 근사치.
        // 정확 비교는 비용 대비 의미가 떨어지므로 user_final 길이만 표시.
        Map<String, Object> payload = new HashMap<>();
        payload.put("edited", true);
        eventService.record(user, EventType.COPIED, session, payload);

        return new ConfirmResponse(session.getId(), session.getStatus(), session.getUpdatedAt());
    }

    // ===== 조회 =====

    @Transactional(readOnly = true)
    public InProgressResponse listInProgress(Long userId) {
        User user = loadUser(userId);
        List<SessionSummary> summaries = sessionRepository
                .findByUserIdAndStatusIn(user.getId(), List.of(Status.IN_PROGRESS)).stream()
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
                session.getOriginal(),
                session.getUserFinal(),
                session.getStatus(),
                feedbacks,
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    // ===== 헬퍼 =====

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

    /**
     * 활성 교정 prompt — (CORRECTION, receiver) 조합당 1개. V12 마이그레이션 후 partial UNIQUE.
     * receiver 가 null 이거나 매칭 row 가 없으면 null 반환 → GeminiAiCorrectionClient 가 기본 prompt 사용.
     */
    private PromptVersion activeCorrectionPrompt(Receiver receiver) {
        if (receiver == null) return null;
        return promptVersionRepository
                .findFirstByPurposeAndRecipientTypeAndIsActiveTrue(PromptPurpose.CORRECTION, receiver)
                .orElse(null);
    }

    private List<Range> toRanges(List<CorrectionRequest.ProtectedRange> ranges) {
        if (ranges == null) return List.of();
        return ranges.stream().map(r -> new Range(r.start(), r.end())).toList();
    }

    /**
     * TX1 → AI 호출 → TX2 사이에 entity 를 들고 다닐 수 없으므로 (트랜잭션 종료 후 detach) 값 객체로 스냅샷.
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

    private CorrectionResponse toCorrectionResponse(CorrectionSession s, AiCorrectionResult r) {
        List<CorrectionResponse.ChangeItem> items = r.changes().stream()
                .map(c -> new CorrectionResponse.ChangeItem(
                        c.index(), c.start(), c.end(), c.original(), c.corrected(), c.reason(),
                        c.label(), c.confidence(), c.appliedRules(), null))
                .toList();
        return new CorrectionResponse(s.getId(), items, s.getUpdatedAt());
    }

    private SessionSummary toSessionSummary(CorrectionSession s) {
        String preview = s.getOriginal() == null ? null
                : s.getOriginal().substring(0, Math.min(PREVIEW_LENGTH, s.getOriginal().length()));
        return new SessionSummary(s.getId(), s.getReceiverType(), s.getPurpose(),
                s.getStatus(), preview, s.getCreatedAt());
    }
}
