package com.example.tonefitserver.domain.reply.service;

import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.TextSanitizer;
import com.example.tonefitserver.domain.prompt.PromptPurpose;
import com.example.tonefitserver.domain.prompt.PromptVersion;
import com.example.tonefitserver.domain.prompt.PromptVersionRepository;
import com.example.tonefitserver.domain.reply.ai.AiReplyAnalysisResult;
import com.example.tonefitserver.domain.reply.ai.AiReplyClient;
import com.example.tonefitserver.domain.reply.ai.AiReplyDraftResult;
import com.example.tonefitserver.domain.reply.dto.ReplyAnalysisRequest;
import com.example.tonefitserver.domain.reply.dto.ReplyAnalysisResponse;
import com.example.tonefitserver.domain.reply.dto.ReplyDraftRequest;
import com.example.tonefitserver.domain.reply.dto.ReplyDraftResponse;
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
 *   <li>{@link #analyze} — 파악 호출 (FUNC-Rep-02 ①~③): 코드 정리 → light 모델 요약·파악.
 *       응답을 FE 가 입력 화면(R-01)에 띄웠다가 작성 호출로 그대로 회송 — 서버 상태 없음.</li>
 *   <li>{@link #draft} — 작성 호출 (⑤~⑥): main 모델 작성 (+ Phase B: 점검·조건부 1회 재작성).</li>
 * </ul>
 *
 * <p>받은 메일·생성 회신은 저장하지 않고 처리 후 즉시 폐기 (FUNC-Rep-13).
 * 받은 메일은 제3자 글 — 본문·보낸 사람 정보를 로그에도 남기지 않는다.
 *
 * <p>Phase B 에서 채울 것: ① 인용·서명 제거(코드), 요약 분기, 점검(light judge)+재작성,
 * 60초 2구간 시간 예산(FUNC-Rep-12), 한국어 감지 등 에러 세분화(FUNC-Rep-14).
 * 결정 대기 후 부착: 계정 한도 차감 시점, "받은 메일 읽기" 동의 게이트(FUNC-Ag-08).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplyService {

    private final UserRepository userRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final AiReplyClient aiClient;

    // ===== 파악 호출 =====

    public ReplyAnalysisResponse analyze(Long userId, ReplyAnalysisRequest req) {
        loadUser(userId);

        // ① 정리 — Phase B 에서 인용·서명 제거 구현. 현재는 sanitize 만.
        List<String> mailBodies = req.mails().stream()
                .map(m -> TextSanitizer.sanitize(m.body()))
                .toList();

        AiReplyAnalysisResult result = aiClient.analyze(
                null, // 파악 prompt 는 수신자 무관 코드 상수 — Gemini 구현체(Phase B)가 보유
                mailBodies,
                req.to() == null ? List.of() : req.to(),
                req.cc() == null ? List.of() : req.cc());

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

        AiReplyDraftResult result = aiClient.draft(
                prompt != null ? prompt.getContent() : null,
                req.receiverType(),
                TextSanitizer.sanitize(req.conversation()),
                qas,
                TextSanitizer.sanitize(req.freeInput()));

        return new ReplyDraftResponse(result.generatedSubject(), result.generatedEmail());
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
