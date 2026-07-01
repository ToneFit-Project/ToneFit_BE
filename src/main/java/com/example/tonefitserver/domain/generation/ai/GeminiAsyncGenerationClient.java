package com.example.tonefitserver.domain.generation.ai;

import com.example.tonefitserver.core.ai.AiHttpTransport;
import com.example.tonefitserver.core.ai.GeminiApiResponse;
import com.example.tonefitserver.core.enums.Purpose;
import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.domain.correction.ai.GeminiProperties;
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
 * Gemini 생성 클라이언트의 비동기(sendAsync) 변형 — 폴오버 헤지의 primary. 동기
 * {@link GeminiAiGenerationClient} 와 요청/스키마/입력 형식은 동일하되 {@link AiHttpTransport} 로 논블로킹 호출한다.
 * ai.failover.enabled=true 일 때만 빈 등록.
 *
 * <p>요청 본문·스키마·입력 형식은 동기 클라이언트와 일치시켜야 한다(변경 시 함께).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.failover.enabled", havingValue = "true")
@RequiredArgsConstructor
public class GeminiAsyncGenerationClient implements AsyncAiGenerationClient {

    /** primary(Gemini) 호출 타임아웃 — 전체 데드라인(30s)과 정렬. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String DEFAULT_GENERATION_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일을 작성해주는 어시스턴트입니다.
            수신자 유형과 목적, 사용자가 제공한 간략 내용(상황)을 바탕으로
            이메일 제목(generated_subject)과 본문(generated_email)을 새로 작성하세요.
            비즈니스 한국어로 간결·정중·명확하게, 수신자 유형에 맞는 호칭과 어조를 쓰고,
            상황의 의도를 보존하되 임의로 사실을 추가하지 마세요.
            본문(generated_email)은 문단 사이를 빈 줄(\\n\\n)로, 목록 항목을 줄바꿈(\\n)으로 구분합니다.
            """;

    private final AiHttpTransport transport;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public CompletableFuture<AiGenerationResult> generateAsync(String promptContent, Receiver receiver,
                                                               Purpose purpose, String briefContent) {
        String system = (promptContent == null || promptContent.isBlank())
                ? DEFAULT_GENERATION_SYSTEM_PROMPT : promptContent;
        String user = buildUserMessage(receiver, purpose, briefContent == null ? "" : briefContent);

        String body;
        try {
            body = objectMapper.writeValueAsString(buildRequestBody(system, user));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        URI uri = URI.create(properties.baseUrl()
                + "/models/" + properties.generationModelOrDefault() + ":generateContent");
        Map<String, String> headers = Map.of("x-goog-api-key", properties.apiKey());
        long start = System.currentTimeMillis();

        return transport.postJson(uri, headers, body, TIMEOUT)
                .thenApply(responseBody -> {
                    AiGenerationResult result = parse(responseBody);
                    log.info("gemini_call op=generation mode=async durationMs={} outcome=ok",
                            System.currentTimeMillis() - start);
                    return result;
                })
                .exceptionallyCompose(ex -> {
                    log.info("gemini_call op=generation mode=async durationMs={} outcome=error error={}",
                            System.currentTimeMillis() - start, ex.getClass().getSimpleName());
                    return CompletableFuture.failedFuture(ex);
                });
    }

    // --- 동기 GeminiAiGenerationClient 와 일치(변경 시 함께) ---

    private String buildUserMessage(Receiver receiver, Purpose purpose, String briefContent) {
        return "수신자 유형: " + receiver + '\n'
                + "목적: " + purpose + '\n'
                + "상황: " + briefContent;
    }

    private Map<String, Object> buildRequestBody(String system, String user) {
        Map<String, Object> genConfig = new LinkedHashMap<>();
        genConfig.put("responseMimeType", "application/json");
        genConfig.put("responseJsonSchema", generationSchema());
        Integer thinkingBudget = properties.generationThinkingBudget();
        if (thinkingBudget != null && thinkingBudget >= 0) {
            genConfig.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudget));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))));
        body.put("generationConfig", genConfig);
        return body;
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

    private AiGenerationResult parse(String httpBody) {
        try {
            GeminiApiResponse response = objectMapper.readValue(httpBody, GeminiApiResponse.class);
            String text = response == null ? null : response.firstText();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("Empty or malformed Gemini response");
            }
            return objectMapper.readValue(text, AiGenerationResult.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini generation response: " + httpBody, e);
        }
    }
}
