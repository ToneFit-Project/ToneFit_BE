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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini 기반 회신 클라이언트. 교정·생성 client 와 같은 RestClient·GeminiProperties 공유.
 *
 * <p>모델 분담 (FUNC-Rep-15): 작성({@link #draft})은 메인 모델, 요약·파악·점검은 저가 모델
 * ({@code gemini.light-model}, 미설정 시 메인 fallback). API 키는 하나 — 모델은 경로 파라미터.
 *
 * <p>요약·파악·점검 prompt 는 수신자 무관이라 코드 상수. 작성 prompt 만 DB(REPLY×recipient, V20).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
@RequiredArgsConstructor
public class GeminiAiReplyClient implements AiReplyClient {

    private final RestClient geminiRestClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    // ===== ② 요약 (light) =====

    private static final String SUMMARIZE_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 대화를 간추리는 요약 전문가입니다.
            회신을 준비하는 사용자가 이전 대화 맥락을 빠르게 읽도록 긴 메일을 짧게 줄입니다.
            요약만 합니다. 회신을 쓰거나 의견을 더하지 않습니다.

            [규칙]
            1. 메일당 3문장 이내.
            2. 있는 내용만 줄입니다. 새 사실·해석·평가·추측을 더하지 않습니다.
            3. 다음은 반드시 보존: 날짜·기한·금액·수량 등 모든 수치 / 질문·요청·결정 사항 /
               고유명사(회사·제품·프로젝트·사람 이름).
            4. 인사·안부·서명 등 내용 없는 부분은 버립니다.
            5. 이메일 주소·전화번호는 옮기지 않습니다(이름·호칭만).
            6. 원문에 없는 존칭·어체 변환을 하지 않습니다. 중립 서술체로 적습니다.
            """;

    @Override
    public List<String> summarize(List<String> mailBodies) {
        if (mailBodies == null || mailBodies.isEmpty()) return List.of();
        StringBuilder user = new StringBuilder("--- 요약할 메일 (오래된 것부터) ---\n");
        for (int i = 0; i < mailBodies.size(); i++) {
            user.append('[').append(i + 1).append("] 본문:\n").append(mailBodies.get(i)).append('\n');
        }
        user.append("--- 끝 ---");

        String json = callAndExtract("reply_summarize", properties.lightModelOrDefault(),
                SUMMARIZE_SYSTEM_PROMPT, user.toString(), summarizeSchema());
        SummarizeOut out;
        try {
            out = objectMapper.readValue(json, SummarizeOut.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini reply summarize response: " + json, e);
        }
        // order 로 정렬 후 입력 길이에 맞춰 정렬·보정 (누락분은 원문 유지).
        String[] result = new String[mailBodies.size()];
        if (out.summaries() != null) {
            for (SummarizeOut.Item it : out.summaries()) {
                int idx = (it.order() == null ? 0 : it.order()) - 1;
                if (idx >= 0 && idx < result.length) result[idx] = it.summary();
            }
        }
        List<String> list = new ArrayList<>(mailBodies.size());
        for (int i = 0; i < mailBodies.size(); i++) {
            list.add(result[i] != null ? result[i] : mailBodies.get(i));
        }
        return list;
    }

    // ===== ③ 파악 (light) =====

    private static final String ANALYZE_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 회신을 준비하는 분석가입니다.
            받은 메일 대화를 읽고 두 가지만 파악합니다 — ① 받는 사람 유형 추측 ② 답해야 할 질문·요청 목록.
            회신 본문을 쓰지 않고, 대화를 요약·재작성하지 않습니다.

            [작업 0 — 사전 점검 (가장 먼저)]
            - 대화가 비었거나 본문을 읽을 수 없으면 status="EMPTY_THREAD" 로 즉시 종료(recipient·questions 무의미).
            - 본문이 대부분 한국어가 아니면 status="NOT_KOREAN" 으로 종료(영어 전용 메일이 대표 예).
              한국어 문장에 영어 용어·고유명사가 섞인 것은 정상이며 해당하지 않습니다.
            - 그 외 status="OK".

            [작업 1 — 받는 사람 유형 추측] RCP-01 상사 / RCP-02 동료 / RCP-03 고객사 / RCP-04 협력사.
            1. 기준 인물 = 답장 대상 메일 발신자.
            2. 나와 도메인이 같으면 내부(상사·동료), 다르면 외부(고객사·협력사). 모르면 호칭·어체·내용으로.
            3. 내부: 직책 호칭 + 지시·승인·피드백 위치면 상사, 대등하면 동료.
            4. 외부: 우리가 납품·서비스 제공하거나 상대가 발주·구매 결정권이면 고객사, 대등 협업이면 협력사.
               애매하면 고객사(격식 높은 쪽 안전).
            5. To/CC 상향: 기준 인물보다 격식 필요한 상대가 있으면 그 기준으로 올림(상향만, 하향 없음).
            6. 나 자신·단체메일·발신전용(noreply)은 판단에서 제외.
            confidence: high(직책 호칭·귀사/발주 등 관계 표현이 본문/도메인에서 확인될 때만) /
            mid(내부외부는 확실하나 관계 표현 없음) / low(신호 부족·발신전용·단체). reason 은 근거 한 줄.

            [작업 2 — 답할 질문·요청 추출]
            - 마지막 메일 중심, 사용자가 답해야 할 것. 이전 메일 질문도 답이 안 오갔으면 포함.
            - 포함: 명시 질문, 요청·부탁(확인·검토·승인·회신·자료·일정), 선택 요구, 기한 동의 여부.
            - 제외: 인사치레, 단순 정보 공유, 이미 답한 것, 다른 수신자 지목 질문, 단순 첨부 참고 안내.
            - 각 항목은 짧게 답할 수 있는 형태로. 원문 기한·수치·명칭은 보존. 7개 이내(중요 순).
            - mail_order 는 그 질문이 나온 메일 번호. 답할 것 없으면 빈 배열. 없는 질문을 만들지 않음.
            """;

    @Override
    public AiReplyAnalysisResult analyze(AnalyzeInput in) {
        StringBuilder user = new StringBuilder();
        user.append("나: ").append(blankToNone(in.meIdentity())).append('\n');
        user.append("답장 대상: ").append(blankToNone(in.replyTargetSender())).append('\n');
        user.append("받는 사람(To): ").append(joinOrNone(in.to()))
            .append(" / 참조(CC): ").append(joinOrNone(in.cc())).append('\n');
        user.append("--- 받은 메일 대화 (오래된 것부터, 최근 3건 이내) ---\n")
            .append(in.conversation()).append("\n--- 대화 끝 ---");

        String json = callAndExtract("reply_analyze", properties.lightModelOrDefault(),
                ANALYZE_SYSTEM_PROMPT, user.toString(), analyzeSchema());
        AnalyzeOut out;
        try {
            out = objectMapper.readValue(json, AnalyzeOut.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini reply analysis response: " + json, e);
        }

        AiReplyAnalysisResult.Status status = parseStatus(out.status());
        if (status != AiReplyAnalysisResult.Status.OK) {
            return new AiReplyAnalysisResult(status, null, List.of());
        }

        AiReplyAnalysisResult.Recipient recipient = null;
        if (out.recipient() != null) {
            recipient = new AiReplyAnalysisResult.Recipient(
                    rcpToReceiver(out.recipient().type()),
                    out.recipient().label(),
                    out.recipient().confidence(),
                    out.recipient().reason());
        }
        List<AiReplyAnalysisResult.Question> questions = new ArrayList<>();
        if (out.questions() != null) {
            for (AnalyzeOut.QuestionOut q : out.questions()) {
                questions.add(new AiReplyAnalysisResult.Question(
                        q.question(), q.mailOrder() == null ? 0 : q.mailOrder()));
            }
        }
        return new AiReplyAnalysisResult(status, recipient, questions);
    }

    // ===== ⑤ 작성 (main) =====

    private static final String DEFAULT_DRAFT_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 회신을 작성하는 전문가입니다.
            받은 메일 대화와 사용자의 질문별 답변을 바탕으로 회신 제목·본문을 작성합니다.
            입장(수락/거절 등)은 사용자 답변에서 읽고, 답변이 비거나 모호하면 중립으로 씁니다.
            대화·답변에 없는 사실·일정·금액·약속을 지어내지 않습니다.
            응답은 JSON Schema 로 강제됩니다: { "generated_subject": "...", "generated_email": "..." }
            """;

    @Override
    public AiReplyDraftResult draft(DraftInput in) {
        String system = (in.promptContent() == null || in.promptContent().isBlank())
                ? DEFAULT_DRAFT_SYSTEM_PROMPT : in.promptContent();
        String json = callAndExtract("reply_draft", properties.model(),
                system, buildDraftUserMessage(in), draftSchema());
        try {
            return objectMapper.readValue(json, AiReplyDraftResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini reply draft response: " + json, e);
        }
    }

    private String buildDraftUserMessage(DraftInput in) {
        StringBuilder sb = new StringBuilder();
        sb.append("받는 사람 유형: ").append(receiverToRcp(in.receiver())).append('\n');
        sb.append("보내는 사람(나): ").append(blankToNone(in.senderName())).append('\n');
        sb.append("원 메일 제목: ").append(blankToNone(in.originalSubject())).append('\n');
        sb.append("--- 받은 메일 대화 (오래된 것부터) ---\n").append(in.conversation()).append("\n--- 대화 끝 ---\n");
        sb.append("--- 답해야 할 질문과 사용자의 답변 ---\n");
        if (in.questionAnswers() == null || in.questionAnswers().isEmpty()) {
            sb.append("사용자가 전하려는 내용: ").append(blankToNone(in.freeInput())).append('\n');
        } else {
            for (AiReplyClient.QuestionAnswer qa : in.questionAnswers()) {
                sb.append(qa.id()).append(". ").append(qa.question())
                  .append(" → 사용자 답변: ")
                  .append(qa.answer() == null || qa.answer().isBlank() ? "(답변 없음 — 중립으로)" : qa.answer())
                  .append('\n');
            }
        }
        sb.append("--- 끝 ---\n");
        sb.append("그 밖에 전하고 싶은 말: ").append(blankToNone(in.extraMessage())).append('\n');
        sb.append("재작성 사유: ").append(
                in.revisionNotes() == null || in.revisionNotes().isEmpty()
                        ? "없음" : String.join(" / ", in.revisionNotes()));
        return sb.toString();
    }

    // ===== ⑥ 점검 (light) =====

    private static final String INSPECT_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 회신 초안 검수자입니다. 초안을 고치지 말고 평가만 하세요.
            다섯 가지만 봅니다.
            1. 완전성 — 모든 질문에 답했는가. '그 밖에 전하고 싶은 말'이 반영됐는가.
               답 안 한 질문이 있으면 type=UNANSWERED_QUESTION, question_id 에 해당 질문 id.
            2. 사실 충실 — 대화·답변에 없는 사실·일정·금액·약속·거절 사유를 지어내지 않았는가,
               수치·기한이 바뀌지 않았는가. 위반 시 type=FABRICATION.
            3. 입장 일치 — 답변의 입장(수락/거절/조건부/중립)과 초안이 같은가. 모호한 답을 굳히지 않았는가.
               위반 시 type=STANCE_MISMATCH.
            4. 격식 — 수신자 유형에 맞는 어체인가. RCP-01·RCP-03 하십시오체, RCP-02·RCP-04 해요체 기본.
               불일치 시 type=FORMALITY_MISMATCH. 반말·인터넷 구어 혼입도 결함.
            5. 형식 — 제목·본문이 있고 이메일 형태인가. 위반 시 type=FORMAT.
            분명한 결함만 passed=false 로. 표현 취향·사소한 어색함은 통과. 의심스러우면 통과.
            question_id 는 UNANSWERED_QUESTION 일 때만 해당 id, 그 외 0. detail 은 무엇이 왜 문제인지 한 줄.
            """;

    @Override
    public AiReplyInspection inspect(InspectInput in) {
        StringBuilder sb = new StringBuilder();
        sb.append("받는 사람 유형: ").append(receiverToRcp(in.receiver())).append('\n');
        sb.append("--- 받은 메일 대화 ---\n").append(in.conversation()).append("\n--- 대화 끝 ---\n");
        sb.append("--- 질문과 사용자의 답변 ---\n");
        if (in.questionAnswers() == null || in.questionAnswers().isEmpty()) {
            sb.append("사용자가 전하려는 내용: ").append(blankToNone(in.freeInput())).append('\n');
        } else {
            for (AiReplyClient.QuestionAnswer qa : in.questionAnswers()) {
                sb.append(qa.id()).append(". ").append(qa.question())
                  .append(" → 사용자 답변: ").append(qa.answer() == null ? "" : qa.answer()).append('\n');
            }
        }
        sb.append("--- 끝 ---\n");
        sb.append("그 밖에 전하고 싶은 말: ").append(blankToNone(in.extraMessage())).append('\n');
        sb.append("--- 점검할 초안 ---\n제목: ").append(in.draft().generatedSubject())
          .append("\n본문:\n").append(in.draft().generatedEmail()).append("\n--- 초안 끝 ---");

        String json = callAndExtract("reply_inspect", properties.lightModelOrDefault(),
                INSPECT_SYSTEM_PROMPT, sb.toString(), inspectSchema());
        try {
            return objectMapper.readValue(json, AiReplyInspection.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini reply inspection response: " + json, e);
        }
    }

    // ===== 매핑·헬퍼 =====

    private AiReplyAnalysisResult.Status parseStatus(String s) {
        if (s == null) return AiReplyAnalysisResult.Status.OK;
        try {
            return AiReplyAnalysisResult.Status.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            return AiReplyAnalysisResult.Status.OK;
        }
    }

    private Receiver rcpToReceiver(String rcp) {
        if (rcp == null) return null;
        return switch (rcp.trim()) {
            case "RCP-01" -> Receiver.DIRECT_SUPERVISOR;
            case "RCP-02" -> Receiver.OTHER_DEPT_COLLEAGUE;
            case "RCP-03" -> Receiver.CLIENT;
            case "RCP-04" -> Receiver.EXTERNAL_PARTNER;
            default -> null;
        };
    }

    private String receiverToRcp(Receiver r) {
        if (r == null) return "RCP-02";
        return switch (r) {
            case DIRECT_SUPERVISOR -> "RCP-01";
            case OTHER_DEPT_COLLEAGUE -> "RCP-02";
            case CLIENT -> "RCP-03";
            case EXTERNAL_PARTNER -> "RCP-04";
        };
    }

    private String blankToNone(String s) {
        return (s == null || s.isBlank()) ? "없음" : s;
    }

    private String joinOrNone(List<String> list) {
        return (list == null || list.isEmpty()) ? "없음" : String.join(", ", list);
    }

    // ===== Gemini 호출 공통 =====

    private String callAndExtract(String op, String model, String systemInstruction,
                                  String userText, Map<String, Object> schema) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userText)))));
        body.put("generationConfig", Map.of(
                "responseMimeType", "application/json",
                "responseJsonSchema", schema));

        String path = "/models/" + model + ":generateContent";
        GeminiResponse response = geminiRestClient.post()
                .uri(uri -> uri.path(path).build())
                .header("x-goog-api-key", properties.apiKey())
                .body(body)
                .retrieve()
                .body(GeminiResponse.class);

        if (response == null || response.candidates() == null || response.candidates().isEmpty()
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

    private void logUsage(String op, GeminiResponse.UsageMetadata usage) {
        if (usage == null) return;
        log.info("gemini_usage op={} promptTokens={} candidatesTokens={} totalTokens={}",
                op, usage.promptTokenCount(), usage.candidatesTokenCount(), usage.totalTokenCount());
    }

    // ===== 스키마 =====

    private Map<String, Object> summarizeSchema() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("order", Map.of("type", "integer"));
        itemProps.put("summary", Map.of("type", "string"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProps);
        item.put("required", List.of("order", "summary"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("summaries", Map.of("type", "array", "items", item));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("summaries"));
        return root;
    }

    private Map<String, Object> analyzeSchema() {
        Map<String, Object> recipientProps = new LinkedHashMap<>();
        recipientProps.put("type", Map.of("type", "string",
                "enum", List.of("RCP-01", "RCP-02", "RCP-03", "RCP-04")));
        recipientProps.put("label", Map.of("type", "string"));
        recipientProps.put("confidence", Map.of("type", "string", "enum", List.of("high", "mid", "low")));
        recipientProps.put("reason", Map.of("type", "string"));
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("type", "object");
        recipient.put("properties", recipientProps);
        recipient.put("required", List.of("type", "label", "confidence", "reason"));

        Map<String, Object> qProps = new LinkedHashMap<>();
        qProps.put("question", Map.of("type", "string"));
        qProps.put("mail_order", Map.of("type", "integer"));
        Map<String, Object> qItem = new LinkedHashMap<>();
        qItem.put("type", "object");
        qItem.put("properties", qProps);
        qItem.put("required", List.of("question", "mail_order"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("status", Map.of("type", "string",
                "enum", List.of("OK", "EMPTY_THREAD", "NOT_KOREAN")));
        props.put("recipient", recipient);
        props.put("questions", Map.of("type", "array", "items", qItem));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("status", "recipient", "questions"));
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
                List.of("UNANSWERED_QUESTION", "FABRICATION", "FORMALITY_MISMATCH", "STANCE_MISMATCH", "FORMAT")));
        issueProps.put("question_id", Map.of("type", "integer"));
        issueProps.put("detail", Map.of("type", "string"));
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("type", "object");
        issue.put("properties", issueProps);
        issue.put("required", List.of("type", "question_id", "detail"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("passed", Map.of("type", "boolean"));
        props.put("issues", Map.of("type", "array", "items", issue));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("passed", "issues"));
        return root;
    }

    // ===== 파싱용 내부 DTO =====

    private record SummarizeOut(List<Item> summaries) {
        record Item(Integer order, String summary) {
        }
    }

    private record AnalyzeOut(String status, RecipientOut recipient, List<QuestionOut> questions) {
        record RecipientOut(String type, String label, String confidence, String reason) {
        }

        record QuestionOut(String question, Integer mailOrder) {
        }
    }

    private record GeminiResponse(
            List<Candidate> candidates,
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
