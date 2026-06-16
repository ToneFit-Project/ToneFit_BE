package com.example.tonefitserver.domain.generation.ai;

import com.example.tonefitserver.domain.correction.ai.GeminiProperties;
import com.example.tonefitserver.core.enums.Purpose;
import com.example.tonefitserver.core.enums.Receiver;
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
 * Gemini 기반 생성 클라이언트. 교정 client 와 같은 RestClient·GeminiProperties 공유.
 * 출력 schema 는 {@code generated_subject + generated_email} 두 필드만.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
@RequiredArgsConstructor
public class GeminiAiGenerationClient implements AiGenerationClient {

    private static final String DEFAULT_GENERATION_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일을 작성해주는 어시스턴트입니다.
            수신자 유형과 목적, 사용자가 제공한 간략 내용(brief_content)을 바탕으로
            이메일 제목(generated_subject)과 본문(generated_email)을 새로 작성하세요.

            [원칙]
            - 비즈니스 한국어. 간결·정중·명확.
            - 수신자 유형에 맞는 호칭과 어조를 사용하세요.
            - brief_content 의 의도를 보존하고, 임의로 사실을 추가하지 마세요.

            [출력 형식]
            - 본문(generated_email)은 문단과 문단 사이를 빈 줄(\\n\\n)로 구분하고,
              번호·불릿 목록은 각 항목을 줄바꿈(\\n)으로 나눕니다. 한 줄로 평평하게 출력하지 않습니다.
            - 응답은 JSON Schema 로 강제됩니다: { "generated_subject": "<제목>", "generated_email": "<본문>" }
            """;

    private final RestClient geminiRestClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public AiGenerationResult generate(String promptContent, Receiver receiver, Purpose purpose, String briefContent) {
        String system = (promptContent == null || promptContent.isBlank())
                ? DEFAULT_GENERATION_SYSTEM_PROMPT : promptContent;
        String user = buildUserMessage(receiver, purpose, briefContent == null ? "" : briefContent);
        String json = callAndExtract(system, user, generationSchema());

        try {
            return objectMapper.readValue(json, AiGenerationResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini generation response: " + json, e);
        }
    }

    private String buildUserMessage(Receiver receiver, Purpose purpose, String briefContent) {
        // PM 생성 prompt 의 입력 형식([Receiver]/[Purpose]/[BriefContent])에 정렬. 수신자 유형은
        // DB prompt 가 recipient-specific 이라 redundant 지만, DEFAULT(generic) fallback 을 위해 함께 보낸다.
        // 목적은 Purpose enum 값(NOTICE/REQUEST/THANKS/APOLOGY/DECLINE/REPORT)을 그대로 전달 —
        // prompt 의 목적별 구조 규칙이 동일 enum 키를 사용한다(REPLY 는 회신 모드 전용이라 생성 대상 아님).
        return "수신자 유형: " + receiver + '\n'
                + "목적: " + purpose + '\n'
                + "상황: " + briefContent;
    }

    private String callAndExtract(String systemInstruction, String userText, Map<String, Object> schema) {
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

        String path = "/models/" + properties.model() + ":generateContent";
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
            throw new IllegalStateException("Empty or malformed Gemini response");
        }
        String text = response.candidates().get(0).content().parts().get(0).text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini response text is empty");
        }
        logUsage(response.usageMetadata());
        return text;
    }

    /** FUNC-Lim-07: 초안 1회당 평균 토큰·비용 산출 근거. usageMetadata 를 구조화 로깅. */
    private void logUsage(GeminiResponse.UsageMetadata usage) {
        if (usage == null) return;
        log.info("gemini_usage op=generation promptTokens={} candidatesTokens={} totalTokens={}",
                usage.promptTokenCount(), usage.candidatesTokenCount(), usage.totalTokenCount());
    }

    private Map<String, Object> generationSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("generated_subject", Map.of("type", "string"));
        props.put("generated_email", Map.of("type", "string"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("generated_subject", "generated_email"));
        return root;
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
