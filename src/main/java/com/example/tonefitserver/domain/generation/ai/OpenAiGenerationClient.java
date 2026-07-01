package com.example.tonefitserver.domain.generation.ai;

import com.example.tonefitserver.core.ai.AiHttpTransport;
import com.example.tonefitserver.core.ai.OpenAiChatResponse;
import com.example.tonefitserver.core.ai.OpenAiProperties;
import com.example.tonefitserver.core.ai.OpenAiRequests;
import com.example.tonefitserver.core.enums.Purpose;
import com.example.tonefitserver.core.enums.Receiver;
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
 * OpenAI(GPT) 생성 클라이언트 — 폴오버 fallback. Gemini 와 같은 결과({@link AiGenerationResult})를 낸다.
 *
 * <p>Gemini 의 {@code responseJsonSchema} 대신 OpenAI Chat Completions 의
 * {@code response_format: json_schema(strict)} 로 동일 구조({generated_subject, generated_email})를 강제하고,
 * {@code choices[0].message.content}(내부 JSON 문자열)를 파싱한다. 프롬프트 본문(systemPrompt)·입력 형식은
 * Gemini 와 공유(모델 중립). 비동기(sendAsync)라 헤지에서 스레드를 잡지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.failover.enabled", havingValue = "true")
@RequiredArgsConstructor
public class OpenAiGenerationClient implements AsyncAiGenerationClient {

    /** GPT fallback 호출 타임아웃 — 헤지(10s) 이후 전체 30s 한도 내에서 ≤20s. */
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final String DEFAULT_GENERATION_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일을 작성해주는 어시스턴트입니다.
            수신자 유형과 목적, 사용자가 제공한 간략 내용(상황)을 바탕으로
            이메일 제목(generated_subject)과 본문(generated_email)을 새로 작성하세요.
            비즈니스 한국어로 간결·정중·명확하게, 수신자 유형에 맞는 호칭과 어조를 쓰고,
            상황의 의도를 보존하되 임의로 사실을 추가하지 마세요.
            본문(generated_email)은 문단 사이를 빈 줄(\\n\\n)로, 목록 항목을 줄바꿈(\\n)으로 구분합니다.
            """;

    private final AiHttpTransport transport;
    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public CompletableFuture<AiGenerationResult> generateAsync(String promptContent, Receiver receiver,
                                                               Purpose purpose, String briefContent) {
        String system = (promptContent == null || promptContent.isBlank())
                ? DEFAULT_GENERATION_SYSTEM_PROMPT : promptContent;
        String user = buildUserMessage(receiver, purpose, briefContent == null ? "" : briefContent);

        String body;
        try {
            body = objectMapper.writeValueAsString(
                    OpenAiRequests.chatJsonSchema(properties.generationModel(), system, user, "generation", generationSchema()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        URI uri = URI.create(properties.baseUrl() + "/chat/completions");
        Map<String, String> headers = Map.of("Authorization", "Bearer " + properties.apiKey());
        long start = System.currentTimeMillis();

        return transport.postJson(uri, headers, body, TIMEOUT)
                .thenApply(responseBody -> {
                    AiGenerationResult result = parse(responseBody);
                    log.info("gpt_call op=generation durationMs={} outcome=ok", System.currentTimeMillis() - start);
                    return result;
                })
                .exceptionallyCompose(ex -> {
                    log.info("gpt_call op=generation durationMs={} outcome=error error={}",
                            System.currentTimeMillis() - start, ex.getClass().getSimpleName());
                    return CompletableFuture.failedFuture(ex);
                });
    }

    /** Gemini 클라이언트와 동일 입력 형식 유지(모델 중립). 변경 시 GeminiAiGenerationClient.buildUserMessage 와 함께. */
    private String buildUserMessage(Receiver receiver, Purpose purpose, String briefContent) {
        return "수신자 유형: " + receiver + '\n'
                + "목적: " + purpose + '\n'
                + "상황: " + briefContent;
    }

    private Map<String, Object> generationSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("generated_subject", Map.of("type", "string"));
        props.put("generated_email", Map.of("type", "string"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("generated_subject", "generated_email"));
        root.put("additionalProperties", false);  // strict 모드 필수
        return root;
    }

    private AiGenerationResult parse(String httpBody) {
        try {
            OpenAiChatResponse response = objectMapper.readValue(httpBody, OpenAiChatResponse.class);
            String content = response == null ? null : response.firstContent();
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("OpenAI response content is empty");
            }
            return objectMapper.readValue(content, AiGenerationResult.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse OpenAI generation response: " + httpBody, e);
        }
    }
}
