package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.core.ai.AiExceptions;
import com.example.tonefitserver.core.ai.AiHttpTransport;
import com.example.tonefitserver.core.ai.FailoverProperties;
import com.example.tonefitserver.core.ai.GeminiApiResponse;
import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.domain.correction.model.Range;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Gemini 교정 클라이언트의 비동기(sendAsync) 변형 — 폴오버 헤지의 primary. 동기
 * {@link GeminiAiCorrectionClient} 와 요청/스키마/후처리는 동일하되 {@link AiHttpTransport} 로 논블로킹 호출.
 * 후처리(마커·정제·스키마·파싱 DTO)는 {@link CorrectionSupport} 공용. ai.failover.enabled=true 시에만 빈 등록.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.failover.enabled", havingValue = "true")
@RequiredArgsConstructor
public class GeminiAsyncCorrectionClient implements AsyncAiCorrectionClient {

    private static final String DEFAULT_CORRECTION_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 교정 어시스턴트입니다.
            입력 본문을 검토해 교정 항목(changes) 배열을 반환하세요. 전체 교정 본문은 서버가 원문 + changes 로 재조립합니다.
            먼저 reasoning 에 교정 의심 구간을 짚어 보호 대상 여부와 등급(AUTO/SUGGEST/STYLE)을 판단한 뒤에만 changes 를 작성합니다.
            오류가 눈에 보일 때만 교정하고(과교정 방지), 원문의 개행 구조를 그대로 보존합니다.
            "⟦"·"⟧" 로 감싸진 보호 구간은 어떤 이유로도 수정하지 말고 change 항목도 만들지 마세요.
            """;

    private final AiHttpTransport transport;
    private final GeminiProperties properties;
    private final FailoverProperties failoverProperties;
    private final ObjectMapper objectMapper;

    @Override
    public CompletableFuture<AiCorrectionResult> correctAsync(String promptContent, Receiver receiver,
                                                              String original, List<Range> protectedRanges) {
        String system = (promptContent == null || promptContent.isBlank())
                ? DEFAULT_CORRECTION_SYSTEM_PROMPT : promptContent;
        String safeOriginal = original == null ? "" : original;
        String preparedOriginal = CorrectionSupport.insertMarkers(safeOriginal, protectedRanges);
        String user = CorrectionSupport.buildUserMessage(receiver, preparedOriginal);

        String body;
        try {
            body = objectMapper.writeValueAsString(buildRequestBody(system, user));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        URI uri = URI.create(properties.baseUrl()
                + "/models/" + properties.correctionModelOrDefault() + ":generateContent");
        Map<String, String> headers = Map.of("x-goog-api-key", properties.apiKey());
        long start = System.currentTimeMillis();

        return transport.postJson(uri, headers, body, Duration.ofMillis(failoverProperties.deadlineMs()))
                .thenApply(responseBody -> {
                    AiCorrectionResult result = parse(responseBody, safeOriginal, protectedRanges);
                    log.info("gemini_call op=correction mode=async durationMs={} outcome=ok",
                            System.currentTimeMillis() - start);
                    return result;
                })
                .exceptionallyCompose(ex -> {
                    log.info("gemini_call op=correction mode=async durationMs={} outcome=error error={}",
                            System.currentTimeMillis() - start, AiExceptions.typeName(ex));
                    return CompletableFuture.failedFuture(ex);
                });
    }

    private Map<String, Object> buildRequestBody(String system, String user) {
        Map<String, Object> genConfig = new LinkedHashMap<>();
        genConfig.put("responseMimeType", "application/json");
        genConfig.put("responseJsonSchema", correctionSchema());
        String thinkingLevel = properties.correctionThinkingLevel();
        if (thinkingLevel != null && !thinkingLevel.isBlank()) {
            genConfig.put("thinkingConfig", Map.of("thinkingLevel", thinkingLevel));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))));
        body.put("generationConfig", genConfig);
        return body;
    }

    private Map<String, Object> correctionSchema() {
        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("reasoning", Map.of("type", "string"));
        rootProps.put("changes", Map.of("type", "array", "items", CorrectionSupport.changeItemSchema()));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", rootProps);
        root.put("propertyOrdering", List.of("reasoning", "changes"));
        root.put("required", List.of("reasoning", "changes"));
        return root;
    }

    private AiCorrectionResult parse(String httpBody, String original, List<Range> protectedRanges) {
        CorrectionSupport.CorrectionRaw raw;
        try {
            GeminiApiResponse response = objectMapper.readValue(httpBody, GeminiApiResponse.class);
            String text = response == null ? null : response.firstText();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("Empty or malformed Gemini response");
            }
            raw = objectMapper.readValue(text, CorrectionSupport.CorrectionRaw.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini correction response: " + httpBody, e);
        }
        return new AiCorrectionResult(CorrectionSupport.sanitizeChanges(original, protectedRanges, raw.changes()));
    }
}
