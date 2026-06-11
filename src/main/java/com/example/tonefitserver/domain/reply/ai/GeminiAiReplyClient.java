package com.example.tonefitserver.domain.reply.ai;

import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.domain.correction.ai.GeminiProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini 기반 회신 클라이언트. 교정·생성 client 와 같은 RestClient·GeminiProperties 공유.
 *
 * <p>모델 분담 (FUNC-Rep-15): 작성({@link #draft})은 메인 모델, 요약·파악({@link #analyze})과
 * 점검({@link #inspect})은 저가 모델({@code gemini.light-model}, 미설정 시 메인으로 fallback).
 * API 키는 하나 — 모델은 호출 경로 파라미터.
 *
 * <p>요약 정책 (FUNC-Rep-04): 이전 메일 합이 임계 초과일 때만 이전 대화를 요약하고
 * <b>답장 대상(마지막) 메일은 항상 원문 보존</b> — 지어내기 점검(FUNC-Rep-08)의 근거가 되므로.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
@RequiredArgsConstructor
public class GeminiAiReplyClient implements AiReplyClient {

    /** 이전 메일(답장 대상 제외) 합계가 이 길이를 넘으면 요약 요청. */
    private static final int SUMMARY_THRESHOLD_CHARS = 2_000;

    private static final String DEFAULT_ANALYZE_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 회신을 준비하는 분석 어시스턴트입니다.
            받은 메일 대화를 읽고 아래 항목만 정리하세요. 회신 본문을 작성하지 마세요.
            대화에 없는 내용을 추가하지 마세요.

            1. previous_summary: [지시]에 요약이 필요하다고 명시된 경우에만, 답장 대상(마지막) 메일을
               제외한 이전 대화를 핵심만 간결하게 요약하세요. 요약 불필요 지시면 빈 문자열 "".
            2. receiver_type: 답장을 받게 될 상대의 유형 추측 —
               DIRECT_SUPERVISOR(직속 상사) / OTHER_DEPT_COLLEAGUE(타부서 동료) /
               EXTERNAL_PARTNER(외부 협력사) / CLIENT(고객사).
               기준은 답장 대상 메일을 보낸 사람입니다. 단 To/CC 에 더 윗사람이나 외부 상대가
               있으면 그쪽에 맞춰 더 격식 있는 유형으로 올리고, 내리지는 마세요.
               본인 주소·단체 메일·발신전용(noreply) 주소는 판단에서 제외하세요.
            3. questions: 답장에서 답해야 할 질문·요청 목록. 답장 대상 메일에서 뽑되 각 항목은
               한 문장으로. 답할 것이 없으면 빈 배열.
            """;

    private static final String DEFAULT_DRAFT_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 회신을 작성해주는 어시스턴트입니다.
            받은 메일 대화(정리·요약본)와 사용자가 질문별로 적은 답변을 바탕으로
            회신 제목(generated_subject)과 본문(generated_email)을 작성하세요.

            [원칙]
            - 비즈니스 한국어. 간결·정중·명확.
            - 수신자 유형에 맞는 호칭과 어조를 사용하세요.
            - 답장의 입장(수락/거절/추가 정보 요청/보류·검토/확인 등)은 사용자의 질문별 답변에서 읽어
              톤과 뼈대를 정하세요. 한 회신에 질문마다 입장이 다를 수 있습니다.
            - 답변이 비거나 모호하면 수락·거절을 멋대로 정하지 말고
              중립("확인했습니다 / 검토 후 회신드리겠습니다")으로 쓰세요.

            [지어내기 금지]
            - 받은 메일 대화에 없는 사실·일정·약속을 만들어내지 마세요.
            - 못 읽은 첨부파일·링크 내용을 아는 척하지 마세요.

            [출력 형식]
            응답은 JSON Schema 로 강제됩니다:
            { "generated_subject": "<제목>", "generated_email": "<본문>" }
            """;

    private static final String DEFAULT_INSPECT_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 회신 초안의 검수자입니다.
            초안을 고치지 말고 평가만 하세요. 다음 세 가지를 점검합니다.

            1. 완답성: 질문 목록의 각 질문에 초안이 답했는지.
               답하지 않은 질문이 있으면 type=UNANSWERED_QUESTION, question_id 에 해당 질문 id.
            2. 지어내기: 대화·사용자 답변에 없는 사실·일정·약속이 초안에 있으면 type=FABRICATION.
            3. 격식·방향·형식: 수신자 유형 대비 격식(높임말 수위) 불일치는 FORMALITY_MISMATCH,
               사용자 답변의 입장(수락/거절 등)과 초안 톤 불일치는 STANCE_MISMATCH,
               이메일 형식 문제는 FORMAT.

            - 문제가 없으면 passed=true, issues 는 빈 배열.
            - detail 에는 무엇이 왜 부족한지 한 줄 (예: "2번 질문에 답하지 않음").
            - question_id 는 UNANSWERED_QUESTION 일 때만 해당 질문 id, 그 외에는 0.
            - 사소한 표현 차이는 문제 삼지 마세요. 회신 품질을 실제로 해치는 문제만 지적하세요.
            """;

    private final RestClient geminiRestClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    // ===== ②요약+③파악 (light) =====

    @Override
    public AiReplyAnalysisResult analyze(String promptContent, List<String> mailBodies,
                                         List<String> to, List<String> cc) {
        String system = (promptContent == null || promptContent.isBlank())
                ? DEFAULT_ANALYZE_SYSTEM_PROMPT : promptContent;

        List<String> previous = mailBodies.subList(0, mailBodies.size() - 1);
        boolean needSummary = previous.stream().mapToInt(String::length).sum() > SUMMARY_THRESHOLD_CHARS;

        String user = buildAnalyzeUserMessage(mailBodies, to, cc, needSummary);
        String json = callAndExtract("reply_analyze", properties.lightModelOrDefault(),
                system, user, analyzeSchema());

        AnalyzeOut out;
        try {
            out = objectMapper.readValue(json, AnalyzeOut.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini reply analysis response: " + json, e);
        }

        // 답장 대상 메일은 항상 원문 보존 — 요약된 경우에만 이전 대화를 요약본으로 대체.
        String conversation;
        if (needSummary && out.previousSummary() != null && !out.previousSummary().isBlank()) {
            conversation = "[이전 대화 요약]\n" + out.previousSummary().strip()
                    + "\n\n[답장 대상 메일]\n" + mailBodies.get(mailBodies.size() - 1);
        } else {
            conversation = joinLabeled(mailBodies);
        }
        return new AiReplyAnalysisResult(conversation, out.receiverType(),
                out.questions() == null ? List.of() : out.questions());
    }

    private String buildAnalyzeUserMessage(List<String> mailBodies, List<String> to, List<String> cc,
                                           boolean needSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("[To] ").append(to == null || to.isEmpty() ? "(없음)" : String.join(", ", to)).append('\n');
        sb.append("[Cc] ").append(cc == null || cc.isEmpty() ? "(없음)" : String.join(", ", cc)).append('\n');
        sb.append("[지시] ").append(needSummary
                ? "이전 대화가 깁니다. previous_summary 에 이전 대화 요약을 채우세요."
                : "대화가 짧습니다. previous_summary 는 빈 문자열로 두세요.").append('\n');
        sb.append("[대화 — 시간순, 마지막이 답장 대상]\n").append(joinLabeled(mailBodies));
        return sb.toString();
    }

    private String joinLabeled(List<String> mailBodies) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mailBodies.size(); i++) {
            boolean last = i == mailBodies.size() - 1;
            sb.append("[메일 ").append(i + 1).append(last ? " — 답장 대상]" : "]").append('\n')
              .append(mailBodies.get(i)).append('\n');
        }
        return sb.toString();
    }

    // ===== ⑤작성 (main) =====

    @Override
    public AiReplyDraftResult draft(String promptContent, Receiver receiver, String conversation,
                                    List<QuestionAnswer> questionAnswers, String freeInput,
                                    List<String> revisionNotes) {
        String system = (promptContent == null || promptContent.isBlank())
                ? DEFAULT_DRAFT_SYSTEM_PROMPT : promptContent;
        String user = buildDraftUserMessage(receiver, conversation, questionAnswers, freeInput, revisionNotes);
        String json = callAndExtract("reply_draft", properties.model(), system, user, draftSchema());

        try {
            return objectMapper.readValue(json, AiReplyDraftResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini reply draft response: " + json, e);
        }
    }

    private String buildDraftUserMessage(Receiver receiver, String conversation,
                                         List<QuestionAnswer> questionAnswers, String freeInput,
                                         List<String> revisionNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Receiver] ").append(receiver).append('\n');
        sb.append("[Conversation]\n").append(conversation).append('\n');
        sb.append(formatQuestionAnswers(questionAnswers));
        if (freeInput != null && !freeInput.isBlank()) {
            sb.append("[사용자 자유 입력]\n").append(freeInput).append('\n');
        }
        if (revisionNotes != null && !revisionNotes.isEmpty()) {
            sb.append("[재작성 지시 — 이전 초안의 다음 문제를 해결하세요]\n");
            for (String note : revisionNotes) {
                sb.append("- ").append(note).append('\n');
            }
        }
        return sb.toString();
    }

    // ===== ⑥내부 점검 (light) =====

    @Override
    public AiReplyInspection inspect(Receiver receiver, String conversation,
                                     List<QuestionAnswer> questionAnswers, String freeInput,
                                     AiReplyDraftResult draft) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Receiver] ").append(receiver).append('\n');
        sb.append("[Conversation]\n").append(conversation).append('\n');
        sb.append(formatQuestionAnswers(questionAnswers));
        if (freeInput != null && !freeInput.isBlank()) {
            sb.append("[사용자 자유 입력]\n").append(freeInput).append('\n');
        }
        sb.append("[점검 대상 초안 제목]\n").append(draft.generatedSubject()).append('\n');
        sb.append("[점검 대상 초안 본문]\n").append(draft.generatedEmail()).append('\n');

        String json = callAndExtract("reply_inspect", properties.lightModelOrDefault(),
                DEFAULT_INSPECT_SYSTEM_PROMPT, sb.toString(), inspectSchema());
        try {
            return objectMapper.readValue(json, AiReplyInspection.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini reply inspection response: " + json, e);
        }
    }

    private String formatQuestionAnswers(List<QuestionAnswer> questionAnswers) {
        if (questionAnswers == null || questionAnswers.isEmpty()) {
            return "[질문·답변] (질문 없음)\n";
        }
        StringBuilder sb = new StringBuilder("[질문·답변]\n");
        for (QuestionAnswer qa : questionAnswers) {
            sb.append(qa.id()).append(". 질문: ").append(qa.question()).append('\n')
              .append("   답변: ")
              .append(qa.answer() == null || qa.answer().isBlank()
                      ? "(답변 없음 — 중립적으로 쓸 것)" : qa.answer())
              .append('\n');
        }
        return sb.toString();
    }

    // ===== 요청/응답 공통 =====

    private String callAndExtract(String op, String model, String systemInstruction,
                                  String userText, Map<String, Object> schema) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userText))
        )));
        body.put("generationConfig", Map.of(
                "responseMimeType", "application/json",
                "responseJsonSchema", schema
        ));

        String path = "/models/" + model + ":generateContent";
        GeminiResponse response = geminiRestClient.post()
                .uri(uri -> uri.path(path).build())
                .header("x-goog-api-key", properties.apiKey())
                .body(body)
                .retrieve()
                .body(GeminiResponse.class);

        if (response == null
                || response.candidates() == null
                || response.candidates().isEmpty()
                || response.candidates().get(0).content() == null
                || response.candidates().get(0).content().parts() == null
                || response.candidates().get(0).content().parts().isEmpty()) {
            throw new IllegalStateException("Empty or malformed Gemini response (op=" + op + ")");
        }
        String text = response.candidates().get(0).content().parts().get(0).text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini response text is empty (op=" + op + ")");
        }
        logUsage(op, response.usageMetadata());
        return text;
    }

    /** FUNC-Lim-07: 호출 1회당 평균 토큰·비용 산출 근거. 내용은 로깅하지 않는다. */
    private void logUsage(String op, GeminiResponse.UsageMetadata usage) {
        if (usage == null) return;
        log.info("gemini_usage op={} promptTokens={} candidatesTokens={} totalTokens={}",
                op, usage.promptTokenCount(), usage.candidatesTokenCount(), usage.totalTokenCount());
    }

    // ===== 스키마 =====

    private Map<String, Object> analyzeSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("previous_summary", Map.of("type", "string"));
        props.put("receiver_type", Map.of("type", "string", "enum",
                List.of("DIRECT_SUPERVISOR", "OTHER_DEPT_COLLEAGUE", "EXTERNAL_PARTNER", "CLIENT")));
        props.put("questions", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("previous_summary", "receiver_type", "questions"));
        return root;
    }

    private Map<String, Object> draftSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("generated_subject", Map.of("type", "string"));
        props.put("generated_email", Map.of("type", "string"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("generated_subject", "generated_email"));
        return root;
    }

    private Map<String, Object> inspectSchema() {
        Map<String, Object> issueProps = new LinkedHashMap<>();
        issueProps.put("type", Map.of("type", "string", "enum",
                List.of("UNANSWERED_QUESTION", "FABRICATION", "FORMALITY_MISMATCH",
                        "STANCE_MISMATCH", "FORMAT")));
        issueProps.put("question_id", Map.of("type", "integer"));
        issueProps.put("detail", Map.of("type", "string"));

        Map<String, Object> issueItem = new LinkedHashMap<>();
        issueItem.put("type", "object");
        issueItem.put("properties", issueProps);
        issueItem.put("required", List.of("type", "question_id", "detail"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("passed", Map.of("type", "boolean"));
        props.put("issues", Map.of("type", "array", "items", issueItem));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("passed", "issues"));
        return root;
    }

    /** 파악 응답 바인딩용 — 전역 SNAKE_CASE 로 previous_summary/receiver_type 매핑. */
    private record AnalyzeOut(String previousSummary, Receiver receiverType, List<String> questions) {
    }

    private record GeminiResponse(
            List<Candidate> candidates,
            // 전역 Jackson 이 SNAKE_CASE 라 multi-word 키는 명시 매핑 필요 (Gemini 는 camelCase 응답).
            @JsonProperty("usageMetadata") UsageMetadata usageMetadata) {
        private record Candidate(Content content) {
        }

        private record Content(List<Part> parts) {
        }

        private record Part(String text) {
        }

        private record UsageMetadata(
                @JsonProperty("promptTokenCount") Integer promptTokenCount,
                @JsonProperty("candidatesTokenCount") Integer candidatesTokenCount,
                @JsonProperty("totalTokenCount") Integer totalTokenCount) {
        }
    }
}
