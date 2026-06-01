package com.example.tonefitserver.domain.generation.service;

import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.TermsType;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.TextSanitizer;
import com.example.tonefitserver.core.security.UserRateLimiter;
import com.example.tonefitserver.domain.event.EventService;
import com.example.tonefitserver.domain.event.EventType;
import com.example.tonefitserver.domain.generation.GenerationMetadata;
import com.example.tonefitserver.domain.generation.GenerationMetadataRepository;
import com.example.tonefitserver.domain.generation.ai.AiGenerationClient;
import com.example.tonefitserver.domain.generation.ai.AiGenerationResult;
import com.example.tonefitserver.domain.generation.dto.GenerationRequest;
import com.example.tonefitserver.domain.generation.dto.GenerationResponse;
import com.example.tonefitserver.domain.prompt.PromptPurpose;
import com.example.tonefitserver.domain.prompt.PromptVersion;
import com.example.tonefitserver.domain.prompt.PromptVersionRepository;
import com.example.tonefitserver.domain.session.Receiver;
import com.example.tonefitserver.domain.user.User;
import com.example.tonefitserver.domain.user.UserRepository;
import com.example.tonefitserver.domain.user.UserTermsAgreementRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 생성(Generation) 도메인 서비스. v0.52 §4 + PM 요구사항 REQ-Demo / REQ-Limit / REQ-Extension.
 *
 * <p>단일 엔드포인트로 데모(익명)·Extension(정식) 모두 처리하되 다음만 분기 (PM 합의):
 * <ul>
 *   <li>한도: 정식 사용자만 계정 단위 한도 적용 (REQ-Limit). 익명/데모는 미적용</li>
 *   <li>메타데이터: AI_LEARNING 동의자만 90일 보존 (REQ-Ext-11). 그 외는 저장 안 함</li>
 *   <li>측정: is_guest 플래그로 event_log 발화 — Amplitude 측에서 정식/익명 분포 분리</li>
 * </ul>
 * 입력·출력·모델·프롬프트는 데모/Extension 공유.
 *
 * <p>흐름:
 * <ol>
 *   <li>TX1: user 조회 + (정식이면) 한도 차감 + prompt 조회 + AI_LEARNING 동의 여부 — AI 호출 직전 차감으로
 *       성공·실패 무관 1회 카운트 (FUNC-Lim-06)</li>
 *   <li>AI 호출 (트랜잭션 밖). 소요 시간 측정.</li>
 *   <li>TX2: (동의자면) 메타데이터 저장 + GENERATION_STARTED 이벤트. 실패 시에도 동의자면 메타 저장 후 502.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationService {

    private final UserRepository userRepository;
    private final UserTermsAgreementRepository userTermsAgreementRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final GenerationMetadataRepository generationMetadataRepository;
    private final AiGenerationClient aiClient;
    private final EventService eventService;
    private final UserRateLimiter userRateLimiter;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @PostConstruct
    void initTxTemplate() {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    public GenerationResponse generate(Long userId, GenerationRequest req) {
        PreparedGeneration prep = txTemplate.execute(status -> prepareGeneration(userId, req));

        String sanitizedBrief = TextSanitizer.sanitize(req.briefContent());
        int briefLength = req.briefContent() == null ? 0 : req.briefContent().length();
        long start = System.currentTimeMillis();

        AiGenerationResult aiResult;
        try {
            aiResult = aiClient.generate(prep.promptContent(), req.receiverType(), req.purpose(), sanitizedBrief);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            // 실패 메타 저장은 best-effort — 저장 자체가 실패해도 원래 AI 에러를 마스킹하면 안 된다.
            if (prep.aiLearningConsented()) {
                try {
                    txTemplate.executeWithoutResult(status ->
                            generationMetadataRepository.save(GenerationMetadata.failure(
                                    prep.userId(), req.receiverType(), req.purpose(), briefLength, duration)));
                } catch (Exception metaEx) {
                    log.warn("Failed to persist generation failure metadata (best-effort): {}", metaEx.toString());
                }
            }
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(ErrorType.AI_SERVICE_ERROR,
                    ErrorType.AI_SERVICE_ERROR.getMessage(), null, e);
        }
        long duration = System.currentTimeMillis() - start;
        int resultLength = aiResult.generatedEmail() == null ? 0 : aiResult.generatedEmail().length();

        // AI 성공 — 결과(과금 완료)는 확정. 측정/메타 기록은 best-effort:
        // 부가 작업(event_log·메타 INSERT)이 인프라 사유로 실패해도 이미 만들어진 결과 반환을 막지 않는다.
        try {
            txTemplate.executeWithoutResult(status -> {
                if (prep.aiLearningConsented()) {
                    generationMetadataRepository.save(GenerationMetadata.success(
                            prep.userId(), req.receiverType(), req.purpose(), briefLength, resultLength, duration));
                }
                recordEvent(prep.userId(), prep.isGuest(), req);
            });
        } catch (Exception e) {
            log.warn("Generation succeeded but metadata/event recording failed (best-effort): {}", e.toString());
        }
        return new GenerationResponse(aiResult.generatedSubject(), aiResult.generatedEmail());
    }

    private PreparedGeneration prepareGeneration(Long userId, GenerationRequest req) {
        User user = loadUser(userId);
        // REQ-Limit: 정식(로그인) 사용자만 계정 단위 한도 적용. 익명(데모)은 미적용.
        if (!user.isGuest()) {
            userRateLimiter.consume(UserRateLimiter.CATEGORY_GENERATION, user.getId());
        }
        PromptVersion prompt = activeGenerationPrompt(req.receiverType());
        // AI_LEARNING 동의자만 메타데이터 보존 (REQ-Ext-11). 익명은 약관 자체가 없으므로 항상 false.
        boolean aiLearningConsented = !user.isGuest()
                && userTermsAgreementRepository.findActiveTypesByUserId(user.getId())
                        .contains(TermsType.AI_LEARNING);
        return new PreparedGeneration(
                user.getId(),
                prompt != null ? prompt.getContent() : null,
                aiLearningConsented,
                user.isGuest()
        );
    }

    /**
     * GENERATION_STARTED 이벤트 발화. user 는 FK 채우기용 프록시(getReference)로만 사용 —
     * prepareGeneration(TX1)이 이미 로드·검증했으므로 재조회하지 않는다(중복 쿼리 + 실패 지점 제거).
     */
    private void recordEvent(Long userId, boolean isGuest, GenerationRequest req) {
        User user = userRepository.getReferenceById(userId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("recipient_type", req.receiverType() == null ? null : req.receiverType().name());
        payload.put("purpose", req.purpose() == null ? null : req.purpose().name());
        payload.put("brief_length", req.briefContent() == null ? 0 : req.briefContent().length());
        payload.put("is_guest", isGuest);
        eventService.record(user, EventType.GENERATION_STARTED, null, payload);
    }

    private User loadUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorType.UNAUTHORIZED);
        }
        return userRepository.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.UNAUTHORIZED));
    }

    private PromptVersion activeGenerationPrompt(Receiver receiver) {
        return promptVersionRepository
                .findFirstByPurposeAndRecipientTypeAndIsActiveTrue(PromptPurpose.GENERATION, receiver)
                .orElse(null);
    }

    /** TX1 → AI 호출 → TX2 사이 entity detach 회피를 위한 값 객체 스냅샷. */
    private record PreparedGeneration(Long userId, String promptContent,
                                      boolean aiLearningConsented, boolean isGuest) {
    }
}
