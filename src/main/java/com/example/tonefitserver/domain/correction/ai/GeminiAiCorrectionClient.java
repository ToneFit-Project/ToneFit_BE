package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.domain.correction.model.Range;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini structured output 기반 교정 클라이언트. v0.5 부터 교정 단일 메서드만 유지.
 *
 * <p>입력은 보호 구간 마커(⟦…⟧)로 감싸진 본문 + receiver. 응답은 reasoning(선행 CoT, 폐기) +
 * changes 배열. 마커 삽입·changes 정제(위치 탐색·보호 구간 drop)는 {@link CorrectionSupport} 공용 로직.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
@RequiredArgsConstructor
public class GeminiAiCorrectionClient implements AiCorrectionClient {

    private static final String DEFAULT_CORRECTION_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 교정 어시스턴트입니다.
            입력된 이메일 본문을 검토하고 교정 항목(changes) 배열을 반환하세요.
            전체 교정 본문은 서버가 원문 + changes 로 재조립하므로 별도 반환할 필요 없습니다.

            [사고 과정 — reasoning 먼저, changes 나중]
            먼저 reasoning 필드에 교정 의심 구간을 짚고 각각 ① 보호 대상(수정 금지)인지 ② 등급(AUTO/SUGGEST/STYLE)을
            간단히 판단하세요. 오류가 눈에 보일 때만 교정하고, 일부러 찾지 않습니다(과교정 방지).
            그 판단을 마친 뒤에만 changes 를 작성하세요. reasoning 은 사고용이며 사용자에게 노출되지 않습니다.

            changes 각 항목 (index 는 0부터, 본문 등장 순서):
            - original: 원문에 실제로 존재하는 교정 대상 substring (정확히 그대로 발췌, 가공 금지)
            - corrected: 교정 후 문자열
            - reason: 교정 사유
            - label: 맞춤법/문법 AUTO, 톤 SUGGEST, 스타일 STYLE
            - confidence: 0.0 ~ 1.0
            - applied_rules: 참고한 규칙 코드 배열 (없으면 빈 배열)

            [보호 구간 규칙]
            입력 본문에 "⟦" 와 "⟧" 로 감싸진 구간이 있을 수 있습니다. 해당 구간은 보호 텍스트입니다.
            - 보호 구간 내부의 텍스트는 어떤 이유로도 수정하지 마세요.
            - 보호 구간에 대한 change 항목은 생성하지 마세요.

            [줄바꿈 규칙]
            원문의 개행 구조(문단 구분)를 있는 그대로 보존하세요.
            추가로 개행을 삽입하거나 제거하지 마세요.
            """;

    private final RestClient geminiRestClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public AiCorrectionResult correct(String promptContent, Receiver receiver,
                                      String original, List<Range> protectedRanges) {
        String system = (promptContent == null || promptContent.isBlank())
                ? DEFAULT_CORRECTION_SYSTEM_PROMPT : promptContent;
        String safeOriginal = original == null ? "" : original;
        String preparedOriginal = CorrectionSupport.insertMarkers(safeOriginal, protectedRanges);
        String user = CorrectionSupport.buildUserMessage(receiver, preparedOriginal);
        String json = callAndExtract(system, user, correctionSchema());

        // reasoning(선행 CoT)은 과교정 방지용 사고 과정 — 폐기(메일 내용 섞일 수 있어 로깅·노출·영속 금지). changes 만.
        CorrectionSupport.CorrectionRaw raw;
        try {
            raw = objectMapper.readValue(json, CorrectionSupport.CorrectionRaw.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini correction response: " + json, e);
        }

        List<AiCorrectionResult.Change> cleanedChanges =
                CorrectionSupport.sanitizeChanges(safeOriginal, protectedRanges, raw.changes());
        return new AiCorrectionResult(cleanedChanges);
    }

    private String callAndExtract(String systemInstruction, String userText, Map<String, Object> schema) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userText))
        )));
        Map<String, Object> genConfig = new LinkedHashMap<>();
        genConfig.put("responseMimeType", "application/json");
        genConfig.put("responseJsonSchema", schema);
        // 교정: gemini-3 계열 → thinkingLevel 만 지정. budget·level 동시 지정은 Gemini 가 거부
        //   ("You can only set only one of thinking budget and thinking level") → level 만 사용.
        String thinkingLevel = properties.correctionThinkingLevel();
        if (thinkingLevel != null && !thinkingLevel.isBlank()) {
            genConfig.put("thinkingConfig", Map.of("thinkingLevel", thinkingLevel));
        }
        body.put("generationConfig", genConfig);

        String path = "/models/" + properties.correctionModelOrDefault() + ":generateContent";
        // 폴오버(데드라인·차단기 임계치) 산정용 지연 계측 — 성공뿐 아니라 실패(타임아웃·5xx)·빈결과도
        // durationMs+outcome 로 남겨 p50/p95 와 실패 패턴을 쌓는다.
        long start = System.currentTimeMillis();
        GeminiResponse response;
        try {
            response = geminiRestClient.post()
                    .uri(uri -> uri.path(path).build())
                    .header("x-goog-api-key", properties.apiKey())
                    .body(body)
                    .retrieve()
                    .body(GeminiResponse.class);
        } catch (RuntimeException e) {
            log.info("gemini_call op=correction durationMs={} outcome=error error={}",
                    System.currentTimeMillis() - start, e.getClass().getSimpleName());
            throw e;
        }
        long durationMs = System.currentTimeMillis() - start;

        if (response == null
                || response.candidates() == null
                || response.candidates().isEmpty()
                || response.candidates().get(0).content() == null
                || response.candidates().get(0).content().parts() == null
                || response.candidates().get(0).content().parts().isEmpty()) {
            log.info("gemini_call op=correction durationMs={} outcome=empty", durationMs);
            throw new IllegalStateException("Empty or malformed Gemini response");
        }
        String text = response.candidates().get(0).content().parts().get(0).text();
        if (text == null || text.isBlank()) {
            log.info("gemini_call op=correction durationMs={} outcome=empty", durationMs);
            throw new IllegalStateException("Gemini response text is empty");
        }
        log.info("gemini_call op=correction durationMs={} outcome=ok", durationMs);
        logUsage("correction", response.usageMetadata());
        return text;
    }

    /** FUNC-Lim-07: 초안 1회당 평균 토큰·비용 산출 근거. usageMetadata 를 구조화 로깅. */
    private void logUsage(String op, GeminiResponse.UsageMetadata usage) {
        if (usage == null) return;
        log.info("gemini_usage op={} promptTokens={} candidatesTokens={} totalTokens={}",
                op, usage.promptTokenCount(), usage.candidatesTokenCount(), usage.totalTokenCount());
    }

    private Map<String, Object> correctionSchema() {
        Map<String, Object> rootProps = new LinkedHashMap<>();
        // reasoning 을 changes 보다 먼저 — autoregressive 생성에서 사고가 changes 출력을 조건짓도록(과교정 방지 CoT).
        rootProps.put("reasoning", Map.of("type", "string"));
        rootProps.put("changes", Map.of("type", "array", "items", CorrectionSupport.changeItemSchema()));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", rootProps);
        // propertyOrdering: Gemini 가 reasoning 을 먼저 생성하도록 순서 강제(없으면 changes 가 먼저 나와 CoT 무효).
        root.put("propertyOrdering", List.of("reasoning", "changes"));
        root.put("required", List.of("reasoning", "changes"));
        return root;
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
