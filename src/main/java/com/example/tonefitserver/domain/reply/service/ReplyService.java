package com.example.tonefitserver.domain.reply.service;

import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.TextSanitizer;
import com.example.tonefitserver.domain.prompt.PromptPurpose;
import com.example.tonefitserver.domain.prompt.PromptVersion;
import com.example.tonefitserver.domain.prompt.PromptVersionRepository;
import com.example.tonefitserver.domain.reply.ReplyProperties;
import com.example.tonefitserver.domain.reply.ai.AiReplyAnalysisResult;
import com.example.tonefitserver.domain.reply.ai.AiReplyClient;
import com.example.tonefitserver.domain.reply.ai.AiReplyDraftResult;
import com.example.tonefitserver.domain.reply.ai.AiReplyInspection;
import com.example.tonefitserver.domain.reply.dto.ReplyAnalysisRequest;
import com.example.tonefitserver.domain.reply.dto.ReplyAnalysisResponse;
import com.example.tonefitserver.domain.reply.dto.ReplyDraftRequest;
import com.example.tonefitserver.domain.reply.dto.ReplyDraftResponse;
import com.example.tonefitserver.domain.reply.support.MailCleaner;
import com.example.tonefitserver.domain.user.User;
import com.example.tonefitserver.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 회신(Reply) 도메인 서비스. REQ-Reply — 무상태 2-호출 파이프라인.
 *
 * <ul>
 *   <li>{@link #analyze} — 파악 호출 (FUNC-Rep-02 ①~③): MailCleaner 기계 정리 →
 *       light 모델 요약·파악. 응답을 FE 가 입력 화면(R-01)에 띄웠다가 작성 호출로 회송 — 서버 상태 없음.</li>
 *   <li>{@link #draft} — 작성 호출 (⑤~⑥): main 모델 작성 → light 모델 점검(best-effort) →
 *       실패 + 시간 여유 시 1회 재작성(첫 초안 폐기). 시간 모자라면 첫 초안 그대로 (FUNC-Rep-11/12).</li>
 * </ul>
 *
 * <p>받은 메일·생성 회신은 저장하지 않고 처리 후 즉시 폐기 (FUNC-Rep-13).
 * 받은 메일은 제3자 글 — 본문·보낸 사람 정보를 로그에도 남기지 않는다.
 * 점검 issue 의 detail 도 내용이 섞일 수 있어 재작성 프롬프트로만 쓰고 로그엔 type 만.
 *
 * <p>결정 대기 후 부착: 계정 한도 차감 시점, "받은 메일 읽기" 동의 게이트(FUNC-Ag-08).
 * Phase C: reply 메타데이터·이벤트, 에러 세분화(FUNC-Rep-14 — 한국어 감지·길이 초과 구분 등).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplyService {

    /** 점검(light 호출)을 시작할 최소 잔여 예산 — 이보다 적으면 점검 생략하고 초안 반환. */
    private static final long INSPECT_MIN_BUDGET_MS = 5_000L;

    /** 인용에서 복원한 이전 대화 블록 라벨 — 파악 모델에 출처를 알린다. */
    private static final String RECOVERED_LABEL = "[이전 대화 — 최신 메일 인용에서 복원]";

    private final UserRepository userRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final AiReplyClient aiClient;
    private final ReplyProperties replyProperties;

    // ===== 파악 호출 =====

    public ReplyAnalysisResponse analyze(Long userId, ReplyAnalysisRequest req) {
        loadUser(userId);

        // ① 기계 정리 (FUNC-Rep-04): 인용·서명 제거 + 미중복 trail 복원. 의미 파악은 AI 몫.
        List<String> rawBodies = req.mails().stream()
                .map(m -> TextSanitizer.sanitize(m.body()))
                .toList();
        MailCleaner.CleanResult cleaned = MailCleaner.clean(rawBodies);

        List<String> mailBodies = new ArrayList<>();
        if (!cleaned.recoveredContext().isEmpty()) {
            mailBodies.add(RECOVERED_LABEL + "\n" + cleaned.recoveredContext());
        }
        mailBodies.addAll(cleaned.mails());

        long start = System.currentTimeMillis();
        AiReplyAnalysisResult result;
        try {
            result = aiClient.analyze(
                    null, // 파악 prompt 는 수신자 무관 — 구현체 기본값 사용
                    mailBodies,
                    req.to() == null ? List.of() : req.to(),
                    req.cc() == null ? List.of() : req.cc());
        } catch (Exception e) {
            throw new BusinessException(ErrorType.AI_SERVICE_ERROR,
                    ErrorType.AI_SERVICE_ERROR.getMessage(), null, e);
        }
        log.info("reply_analyze durationMs={} mails={} recovered={}",
                System.currentTimeMillis() - start, cleaned.mails().size(),
                !cleaned.recoveredContext().isEmpty());

        // 질문 id 는 서버가 1부터 순번 부여 — 작성 호출 답변 매핑·내부 점검의 기준 식별자.
        List<ReplyAnalysisResponse.Question> questions = new ArrayList<>();
        List<String> raw = result.questions() == null ? List.of() : result.questions();
        for (int i = 0; i < raw.size(); i++) {
            questions.add(new ReplyAnalysisResponse.Question(i + 1, raw.get(i)));
        }

        return new ReplyAnalysisResponse(result.conversation(), result.receiverTypeSuggestion(), questions);
    }

    // ===== 작성 호출 =====

    public ReplyDraftResponse draft(Long userId, ReplyDraftRequest req) {
        loadUser(userId);

        List<AiReplyClient.QuestionAnswer> qas = pairQuestionAnswers(req);
        PromptVersion prompt = activeReplyPrompt(req.receiverType());
        String promptContent = prompt != null ? prompt.getContent() : null;
        String conversation = TextSanitizer.sanitize(req.conversation());
        String freeInput = TextSanitizer.sanitize(req.freeInput());

        long budget = replyProperties.draftBudgetMillis();
        long start = System.currentTimeMillis();

        // ⑤ 작성 (1차)
        AiReplyDraftResult first;
        try {
            first = aiClient.draft(promptContent, req.receiverType(), conversation, qas, freeInput, List.of());
        } catch (Exception e) {
            throw new BusinessException(ErrorType.AI_SERVICE_ERROR,
                    ErrorType.AI_SERVICE_ERROR.getMessage(), null, e);
        }
        long firstDuration = System.currentTimeMillis() - start;
        if (isBlank(first.generatedEmail())) {
            throw new BusinessException(ErrorType.AI_SERVICE_ERROR, "회신 본문 생성에 실패했습니다.");
        }

        // ⑥ 내부 점검 — best-effort: 점검 실패가 초안 반환을 막지 않는다 (FUNC-Rep-11).
        long remaining = budget - (System.currentTimeMillis() - start);
        if (remaining < INSPECT_MIN_BUDGET_MS) {
            log.info("reply_draft durationMs={} inspect=skipped(budget) rewrite=false", firstDuration);
            return toResponse(first);
        }
        AiReplyInspection check;
        try {
            check = aiClient.inspect(req.receiverType(), conversation, qas, freeInput, first);
        } catch (Exception e) {
            log.warn("reply inspect failed (best-effort): {}", e.toString());
            return toResponse(first);
        }
        if (check.passed()) {
            log.info("reply_draft durationMs={} inspect=passed rewrite=false",
                    System.currentTimeMillis() - start);
            return toResponse(first);
        }

        // detail 은 메일 내용이 섞일 수 있어 로그엔 type 만 (재작성 프롬프트로만 전달).
        List<String> issueTypes = check.issues().stream()
                .map(i -> i.type() == null ? "UNKNOWN" : i.type().name())
                .toList();

        // 재작성은 시간이 남을 때 1번만 — 1차 작성 소요만큼의 여유가 없으면 첫 초안 그대로 (FUNC-Rep-12).
        remaining = budget - (System.currentTimeMillis() - start);
        if (remaining < firstDuration) {
            log.info("reply_draft inspect=failed types={} rewrite=skipped(budget)", issueTypes);
            return toResponse(first);
        }

        try {
            AiReplyDraftResult second = aiClient.draft(promptContent, req.receiverType(), conversation,
                    qas, freeInput, toRevisionNotes(check));
            log.info("reply_draft inspect=failed types={} rewrite=true totalDurationMs={}",
                    issueTypes, System.currentTimeMillis() - start);
            // 재작성본이 비정상이면 첫 초안 fallback — 쓸 수 있는 초안을 이미 들고 있다.
            return isBlank(second.generatedEmail()) ? toResponse(first) : toResponse(second);
        } catch (Exception e) {
            log.warn("reply rewrite failed, falling back to first draft: {}", e.toString());
            return toResponse(first);
        }
    }

    // ===== 헬퍼 =====

    /**
     * 질문-답변 페어링. 답변은 question_id 기준 매핑하며, 질문 목록에 없는 id 의 답변은
     * INVALID_REQUEST. 답변이 없는 질문은 답변 없이 전달 — 비거나 모호하면 AI 가 중립으로
     * 쓴다 (FUNC-Rep-06).
     */
    private List<AiReplyClient.QuestionAnswer> pairQuestionAnswers(ReplyDraftRequest req) {
        List<ReplyDraftRequest.Question> questions =
                req.questions() == null ? List.of() : req.questions();
        List<ReplyDraftRequest.Answer> answers =
                req.answers() == null ? List.of() : req.answers();

        Map<Integer, String> questionById = questions.stream()
                .collect(Collectors.toMap(ReplyDraftRequest.Question::id,
                        ReplyDraftRequest.Question::text,
                        (a, b) -> a));

        for (ReplyDraftRequest.Answer a : answers) {
            if (!questionById.containsKey(a.questionId())) {
                throw new BusinessException(ErrorType.INVALID_REQUEST,
                        "질문 목록에 없는 question_id 입니다: " + a.questionId());
            }
        }
        Map<Integer, String> answerByQuestionId = answers.stream()
                .collect(Collectors.toMap(ReplyDraftRequest.Answer::questionId,
                        a -> TextSanitizer.sanitize(a.answer()),
                        (a, b) -> a));

        return questions.stream()
                .map(q -> new AiReplyClient.QuestionAnswer(
                        q.id(),
                        TextSanitizer.sanitize(q.text()),
                        answerByQuestionId.get(q.id())))
                .toList();
    }

    /** 점검 지적사항 → 재작성 지시 문자열. detail 포함 — 프롬프트로만 흘리고 영속·로깅 금지. */
    private List<String> toRevisionNotes(AiReplyInspection check) {
        return check.issues().stream()
                .map(i -> {
                    String head = i.type() == null ? "ISSUE" : i.type().name();
                    String qid = (i.questionId() != null && i.questionId() > 0)
                            ? " (질문 " + i.questionId() + ")" : "";
                    return head + qid + ": " + (i.detail() == null ? "" : i.detail());
                })
                .toList();
    }

    private ReplyDraftResponse toResponse(AiReplyDraftResult result) {
        return new ReplyDraftResponse(result.generatedSubject(), result.generatedEmail());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private User loadUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorType.UNAUTHORIZED);
        }
        return userRepository.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.UNAUTHORIZED));
    }

    /**
     * 활성 회신 작성 prompt — (REPLY, receiver) 조합당 1개 (V18 시드).
     * 매칭 row 없으면 null → Gemini 구현체가 기본 prompt 사용 (교정과 동일 정책).
     */
    private PromptVersion activeReplyPrompt(Receiver receiver) {
        if (receiver == null) return null;
        return promptVersionRepository
                .findFirstByPurposeAndRecipientTypeAndIsActiveTrue(PromptPurpose.REPLY, receiver)
                .orElse(null);
    }
}
