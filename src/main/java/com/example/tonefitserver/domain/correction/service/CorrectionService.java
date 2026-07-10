package com.example.tonefitserver.domain.correction.service;

import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.core.enums.TermsType;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.TextSanitizer;
import com.example.tonefitserver.core.security.UserRateLimiter;
import com.example.tonefitserver.domain.correction.ai.AiCorrectionClient;
import com.example.tonefitserver.domain.correction.ai.AiCorrectionResult;
import com.example.tonefitserver.domain.correction.dto.CorrectionRequest;
import com.example.tonefitserver.domain.correction.dto.CorrectionResponse;
import com.example.tonefitserver.domain.correction.dto.RejectionsRequest;
import com.example.tonefitserver.domain.correction.dto.RejectionsResponse;
import com.example.tonefitserver.domain.correction.model.Range;
import com.example.tonefitserver.domain.correction.model.RejectedCorrection;
import com.example.tonefitserver.domain.correction.repository.RejectedCorrectionRepository;
import com.example.tonefitserver.domain.event.EventService;
import com.example.tonefitserver.domain.event.EventType;
import com.example.tonefitserver.domain.prompt.PromptPurpose;
import com.example.tonefitserver.domain.prompt.PromptVersion;
import com.example.tonefitserver.domain.prompt.PromptVersionRepository;
import com.example.tonefitserver.domain.user.User;
import com.example.tonefitserver.domain.user.UserRepository;
import com.example.tonefitserver.domain.user.UserTermsAgreementRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 교정 도메인 서비스. v0.6 부터 무상태(stateless) 로 단순화 — 교정 세션·피드백·히스토리·확정 흐름 제거.
 *
 * <ul>
 *   <li>{@link #correct} — AI 교정 후 결과만 반환(저장 안 함) + CORRECTION_STARTED 발화(정식, best-effort)</li>
 *   <li>{@link #persistRejections} — 사용자가 거절한 항목을 AI_LEARNING 동의자에 한해 보존(이벤트 미발화)</li>
 * </ul>
 *
 * <p>측정 분담(FUNC-Amp-03): 교정 요청은 BE API 를 거치므로 BE 가 CORRECTION_STARTED 1종만 발화하고,
 * 항목 복사·거부 클릭은 클라이언트 동작이라 FE 가 직접 Amplitude 로 측정한다.
 *
 * <p>전체 원문·확정본·수락 항목 텍스트는 저장하지 않는다(FUNC-Cor-06) — 거절 구절만 별도 보존.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorrectionService {

    private final PromptVersionRepository promptVersionRepository;
    private final UserRepository userRepository;
    private final UserTermsAgreementRepository userTermsAgreementRepository;
    private final RejectedCorrectionRepository rejectedCorrectionRepository;
    private final AiCorrectionClient aiClient;
    private final EventService eventService;
    private final UserRateLimiter userRateLimiter;
    private final PlatformTransactionManager transactionManager;

    /** 이벤트 INSERT(트랜잭션) 만 별도 경계로 — AI 호출(블로킹)은 트랜잭션 밖. */
    private TransactionTemplate txTemplate;

    @PostConstruct
    void initTxTemplate() {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ===== 교정 =====

    public CorrectionResponse correct(Long userId, CorrectionRequest req) {
        AiCorrectionInput input = prepareCorrection(userId, req);

        long start = System.currentTimeMillis();
        AiCorrectionResult result;
        try {
            result = aiClient.correct(input.promptContent(), input.receiver(),
                    input.original(), input.protectedRanges());
        } catch (Exception e) {
            throw new BusinessException(ErrorType.AI_SERVICE_ERROR,
                    ErrorType.AI_SERVICE_ERROR.getMessage(), null, e);
        }
        long durationMs = System.currentTimeMillis() - start;

        recordStartedEvent(input, result.changes().size(), durationMs);
        return toCorrectionResponse(result);
    }

    /**
     * 교정 입력 스냅샷 준비. 세션을 만들지 않으므로 DB 쓰기는 없다.
     * REQ-Limit: correction 은 인증 필수(정식만)이라 계정 단위 한도를 AI 호출 직전 항상 차감
     * (성공·실패 무관 1회 카운트, FUNC-Lim-06).
     */
    private AiCorrectionInput prepareCorrection(Long userId, CorrectionRequest req) {
        User user = loadUser(userId);
        userRateLimiter.consume(UserRateLimiter.CATEGORY_CORRECTION, user.getId());
        PromptVersion prompt = activeCorrectionPrompt(req.receiverType());
        return new AiCorrectionInput(
                user.getId(),
                prompt != null ? prompt.getContent() : null,
                req.receiverType(),
                TextSanitizer.sanitize(req.originalEmail()),
                toRanges(req.protectedRanges())
        );
    }

    /**
     * CORRECTION_STARTED 발화 (정식 사용자만, best-effort). 인프라 실패가 교정 결과 반환을 막지 않도록
     * 별도 트랜잭션 + try/catch. user 는 FK 채우기용 프록시로만 사용(getReference).
     * 과교정·지연 분석을 위해 change_count·duration_ms 를 properties 에 동봉.
     */
    private void recordStartedEvent(AiCorrectionInput input, int changeCount, long durationMs) {
        try {
            txTemplate.executeWithoutResult(status -> {
                User userRef = userRepository.getReferenceById(input.userId());
                Map<String, Object> payload = new HashMap<>();
                payload.put("recipient_type", input.receiver() == null ? null : input.receiver().name());
                payload.put("input_length", input.original() == null ? 0 : input.original().length());
                payload.put("change_count", changeCount);
                payload.put("duration_ms", durationMs);
                eventService.record(userRef, EventType.STARTED, payload);
            });
        } catch (Exception e) {
            log.warn("Correction succeeded but event recording failed (best-effort): {}", e.toString());
        }
    }

    // ===== 거절 보존 =====

    /**
     * 사용자가 거절한 교정 항목 보존 (FUNC-Cor-06). AI_LEARNING 동의자만 저장하며, 미동의 시 무시(stored=0).
     * 측정 이벤트는 발화하지 않는다(FE 가 REJECTION_CLICKED 측정).
     */
    @Transactional
    public RejectionsResponse persistRejections(Long userId, RejectionsRequest req) {
        User user = loadUser(userId);

        boolean aiLearningConsented = userTermsAgreementRepository
                .findActiveTypesByUserId(user.getId())
                .contains(TermsType.AI_LEARNING);
        if (!aiLearningConsented) {
            return new RejectionsResponse(0);
        }

        List<RejectedCorrection> rows = req.items().stream()
                .map(it -> RejectedCorrection.of(
                        user.getId(),
                        req.receiverType(),
                        it.label(),
                        TextSanitizer.sanitize(it.originalPhrase()),
                        TextSanitizer.sanitize(it.correctedPhrase()),
                        it.meaningDamageSuspected()))
                .toList();
        rejectedCorrectionRepository.saveAll(rows);
        return new RejectionsResponse(rows.size());
    }

    // ===== 헬퍼 =====

    private User loadUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorType.UNAUTHORIZED);
        }
        return userRepository.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.UNAUTHORIZED));
    }

    /**
     * 활성 교정 prompt — (CORRECTION, receiver) 조합당 1개. receiver 가 null 이거나 매칭 row 가 없으면
     * null 반환 → GeminiAiCorrectionClient 가 기본 prompt 사용.
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

    private CorrectionResponse toCorrectionResponse(AiCorrectionResult r) {
        List<CorrectionResponse.ChangeItem> items = r.changes().stream()
                .map(c -> new CorrectionResponse.ChangeItem(
                        c.index(), c.start(), c.end(), c.original(), c.corrected(), c.reason(),
                        c.label(), c.confidence(), c.appliedRules()))
                .toList();
        return new CorrectionResponse(items);
    }

    /**
     * 교정 호출 입력 스냅샷. 세션이 없으므로 userId 를 함께 들고 다녀 이벤트 발화 시 FK 채움에 쓴다.
     */
    private record AiCorrectionInput(
            Long userId,
            String promptContent,
            Receiver receiver,

            String original,
            List<Range> protectedRanges
    ) {
    }
}
