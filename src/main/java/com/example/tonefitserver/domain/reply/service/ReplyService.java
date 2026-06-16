package com.example.tonefitserver.domain.reply.service;

import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.core.enums.TermsType;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.TextSanitizer;
import com.example.tonefitserver.core.security.UserRateLimiter;
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
import com.example.tonefitserver.domain.user.UserTermsAgreementRepository;
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
 * <p>게이트 (v0.57, PM 확정): 킬스위치({@code reply.enabled}, FUNC-Lim-10) → MAIL_READ 동의(FUNC-Ag-08,
 * 회신만 차단) → 계정 한도(일일 합산 1회는 파악 호출에서 차감, 작성은 분당 가드만) →
 * 정리 후 본문 합산 20,000자 초과 시 CONTENT_TOO_LONG.
 *
 * <p>Phase C: reply 메타데이터·이벤트, 잔여 에러 세분화(한국어 감지 등), 비용 경보.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplyService {

    /** 점검(light 호출)을 시작할 최소 잔여 예산 — 이보다 적으면 점검 생략하고 초안 반환. */
    private static final long INSPECT_MIN_BUDGET_MS = 5_000L;

    /** 인용에서 복원한 이전 대화 블록 라벨 — 파악 모델에 출처를 알린다. */
    private static final String RECOVERED_LABEL = "[이전 대화 — 최신 메일 인용에서 복원]";

    /** "너무 김" 기준 — 정리(인용·서명 제거) 후 대화 본문 합산 글자 수 (PM 확정, FUNC-Rep-14). */
    private static final int CONVERSATION_MAX_CHARS = 20_000;

    /** 요약 호출 기준 — 답장 대상 제외 이전 메일 합산 길이가 이를 넘으면 요약(임시값, PM 기준 전달 대기). */
    private static final int SUMMARY_THRESHOLD_CHARS = 2_000;

    private final UserRepository userRepository;
    private final UserTermsAgreementRepository userTermsAgreementRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final AiReplyClient aiClient;
    private final ReplyProperties replyProperties;
    private final UserRateLimiter userRateLimiter;

    // ===== 파악 호출 =====

    public ReplyAnalysisResponse analyze(Long userId, ReplyAnalysisRequest req) {
        ensureReplyEnabled();
        User user = loadUser(userId);
        requireMailReadConsent(user.getId());

        // ① 기계 정리 (FUNC-Rep-04): 인용·서명 제거 + 미중복 trail 복원. 의미 파악은 AI 몫.
        List<String> rawBodies = req.mails().stream()
                .map(m -> TextSanitizer.sanitize(m.body()))
                .toList();
        MailCleaner.CleanResult cleaned = MailCleaner.clean(rawBodies);

        // "너무 김" 검증 (PM 확정): 정리 후 본문 합산 20,000자 초과 → 구분 응답.
        // 한도 차감보다 먼저 — 검증 실패는 AI 호출이 아니므로 카운트하지 않는다 (FUNC-Lim-06).
        int totalChars = cleaned.mails().stream().mapToInt(String::length).sum();
        if (totalChars > CONVERSATION_MAX_CHARS) {
            throw new BusinessException(ErrorType.CONTENT_TOO_LONG,
                    "정리 후 대화 본문 합산이 20,000자를 초과했습니다.");
        }
        // 정리 결과가 통째로 비면 AI 호출 전에 EMPTY_THREAD (한도 미차감).
        boolean allBlank = cleaned.mails().stream().allMatch(String::isBlank)
                && cleaned.recoveredContext().isBlank();
        if (allBlank) {
            throw new BusinessException(ErrorType.EMPTY_THREAD);
        }

        // 회신 1번 = 일일(합산) 1회 — 파악 호출 시점 차감 (PM 확정). 분당 한도도 함께.
        // 내부 요약+파악이 여러 AI 호출이어도 회신 1회로 친다.
        userRateLimiter.consume(UserRateLimiter.CATEGORY_REPLY, user.getId());

        // ② 요약(이전 메일 길 때만) + 대화 조립 — conversation 은 BE 가 만들어 모델 재출력 방지(FUNC-Rep-07).
        List<String> senders = req.mails().stream().map(ReplyAnalysisRequest.Mail::sender).toList();
        String conversation = buildConversation(senders, cleaned);

        long start = System.currentTimeMillis();
        String me = user.getNickname() + " / " + user.getEmail();
        String replyTarget = senders.isEmpty() ? null : senders.get(senders.size() - 1);
        AiReplyAnalysisResult result;
        try {
            result = aiClient.analyze(new AiReplyClient.AnalyzeInput(
                    me, replyTarget,
                    req.to() == null ? List.of() : req.to(),
                    req.cc() == null ? List.of() : req.cc(),
                    conversation));
        } catch (Exception e) {
            throw new BusinessException(ErrorType.AI_SERVICE_ERROR,
                    ErrorType.AI_SERVICE_ERROR.getMessage(), null, e);
        }
        log.info("reply_analyze durationMs={} mails={} status={}",
                System.currentTimeMillis() - start, cleaned.mails().size(), result.status());

        // 사전 점검 결과 → 구분 에러 (FUNC-Rep-14).
        switch (result.status()) {
            case EMPTY_THREAD -> throw new BusinessException(ErrorType.EMPTY_THREAD);
            case NOT_KOREAN -> throw new BusinessException(ErrorType.NOT_KOREAN);
            default -> { }
        }
        if (result.recipient() == null) {
            throw new BusinessException(ErrorType.AI_SERVICE_ERROR, "파악 결과가 비어 있습니다.");
        }

        // 질문 id 는 서버가 1부터 부여 — 작성 호출 답변 매핑·내부 점검의 기준 식별자.
        List<ReplyAnalysisResponse.Question> questions = new ArrayList<>();
        List<AiReplyAnalysisResult.Question> raw = result.questions() == null ? List.of() : result.questions();
        for (int i = 0; i < raw.size(); i++) {
            questions.add(new ReplyAnalysisResponse.Question(i + 1, raw.get(i).question(), raw.get(i).mailOrder()));
        }

        AiReplyAnalysisResult.Recipient r = result.recipient();
        return new ReplyAnalysisResponse(
                conversation,
                new ReplyAnalysisResponse.Recipient(r.type(), r.label(), r.confidence(), r.reason()),
                questions);
    }

    /**
     * 파악·작성에 넘길 대화 텍스트 조립. 답장 대상(마지막) 메일은 항상 원문, 이전 메일은 합산이 길면 요약.
     * 인용에서 복원한 이전 대화는 맨 앞에 덧붙인다. 요약은 별도 AI 호출이라 한도 차감 이후에만 부른다.
     */
    private String buildConversation(List<String> senders, MailCleaner.CleanResult cleaned) {
        List<String> bodies = cleaned.mails();
        int n = bodies.size();
        List<String> rendered = new ArrayList<>(bodies);
        boolean[] summarized = new boolean[n];

        if (n > 1) {
            List<String> older = bodies.subList(0, n - 1);
            int olderChars = older.stream().mapToInt(String::length).sum();
            if (olderChars > SUMMARY_THRESHOLD_CHARS) {
                List<String> summaries = aiClient.summarize(older);
                for (int i = 0; i < older.size() && i < summaries.size(); i++) {
                    rendered.set(i, summaries.get(i));
                    summarized[i] = true;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!cleaned.recoveredContext().isBlank()) {
            sb.append(RECOVERED_LABEL).append('\n').append(cleaned.recoveredContext()).append("\n\n");
        }
        for (int i = 0; i < n; i++) {
            String sender = (i < senders.size() && senders.get(i) != null && !senders.get(i).isBlank())
                    ? senders.get(i) : "(미상)";
            sb.append('[').append(i + 1).append(']').append(summarized[i] ? " (요약)" : "")
              .append(" 보낸 사람: ").append(sender).append('\n')
              .append("본문: ").append(rendered.get(i)).append('\n');
        }
        return sb.toString().strip();
    }

    // ===== 작성 호출 =====

    public ReplyDraftResponse draft(Long userId, ReplyDraftRequest req) {
        ensureReplyEnabled();
        User user = loadUser(userId);
        requireMailReadConsent(user.getId());
        // 일일 1회는 파악 호출에서 이미 차감 — 작성은 분당 가드만 (무상태라 직접 반복 호출 가능하므로).
        userRateLimiter.consumeMinuteOnly(UserRateLimiter.CATEGORY_REPLY, user.getId());

        List<AiReplyClient.QuestionAnswer> qas = pairQuestionAnswers(req);
        PromptVersion prompt = activeReplyPrompt(req.receiverType());
        String promptContent = prompt != null ? prompt.getContent() : null;
        String conversation = TextSanitizer.sanitize(req.conversation());
        String freeInput = TextSanitizer.sanitize(req.freeInput());
        String extraMessage = TextSanitizer.sanitize(req.extraMessage());
        String originalSubject = TextSanitizer.sanitize(req.originalSubject());
        String senderName = user.getNickname();

        long budget = replyProperties.draftBudgetMillis();
        long start = System.currentTimeMillis();

        // ⑤ 작성 (1차)
        AiReplyDraftResult first;
        try {
            first = aiClient.draft(new AiReplyClient.DraftInput(
                    promptContent, req.receiverType(), senderName, originalSubject,
                    conversation, qas, freeInput, extraMessage, List.of()));
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
            check = aiClient.inspect(new AiReplyClient.InspectInput(
                    req.receiverType(), conversation, qas, freeInput, extraMessage, first));
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
            AiReplyDraftResult second = aiClient.draft(new AiReplyClient.DraftInput(
                    promptContent, req.receiverType(), senderName, originalSubject,
                    conversation, qas, freeInput, extraMessage, toRevisionNotes(check)));
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

    /** FUNC-Lim-10 수동 킬스위치 — 비용 위험선 접근 시 회신만 차단 (생성·교정 유지). */
    private void ensureReplyEnabled() {
        if (!replyProperties.enabled()) {
            throw new BusinessException(ErrorType.REPLY_SUSPENDED);
        }
    }

    /**
     * "받은 메일 읽기" 동의 게이트 (FUNC-Ag-08). 회신은 받은 메일(제3자 글)을 읽으므로
     * MAIL_READ 활성 동의 없이는 차단 — 회신 최초 사용 시 FE 가 동의를 받아
     * {@code PATCH /users/me/terms/MAIL_READ} 로 기록한 뒤 재호출한다.
     * 응답은 로그인 약관 차단과 동일 형식: TERMS_AGREEMENT_REQUIRED + missing_terms.
     */
    private void requireMailReadConsent(Long userId) {
        boolean consented = userTermsAgreementRepository
                .findActiveTypesByUserId(userId)
                .contains(TermsType.MAIL_READ);
        if (!consented) {
            throw new BusinessException(ErrorType.TERMS_AGREEMENT_REQUIRED,
                    "받은 메일 읽기 동의가 필요합니다.")
                    .withDetails(Map.of("missing_terms", List.of(TermsType.MAIL_READ.name())));
        }
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
