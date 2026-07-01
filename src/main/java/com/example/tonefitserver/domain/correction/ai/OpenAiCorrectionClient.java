package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.core.ai.AiExceptions;
import com.example.tonefitserver.core.ai.AiHttpTransport;
import com.example.tonefitserver.core.ai.FailoverProperties;
import com.example.tonefitserver.core.ai.OpenAiChatResponse;
import com.example.tonefitserver.core.ai.OpenAiProperties;
import com.example.tonefitserver.core.ai.OpenAiRequests;
import com.example.tonefitserver.core.enums.Purpose;
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
 * OpenAI(GPT) 교정 클라이언트 — 폴오버 fallback. Gemini 와 같은 결과({@link AiCorrectionResult})를 낸다.
 *
 * <p>OpenAI {@code response_format: json_schema(strict)} 로 {reasoning, changes} 를 강제하고,
 * {@code choices[0].message.content}(내부 JSON)를 {@link CorrectionSupport.CorrectionRaw} 로 파싱한 뒤
 * Gemini 와 동일한 후처리({@link CorrectionSupport#sanitizeChanges})를 거친다. 마커 삽입·입력 형식도 공유.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.failover.enabled", havingValue = "true")
@RequiredArgsConstructor
public class OpenAiCorrectionClient implements AsyncAiCorrectionClient {

    private static final String DEFAULT_CORRECTION_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 교정 어시스턴트입니다.
            입력 본문을 검토해 교정 항목(changes) 배열을 반환하세요. 전체 교정 본문은 서버가 원문 + changes 로 재조립합니다.
            먼저 reasoning 에 교정 의심 구간을 짚어 보호 대상 여부와 등급(AUTO/SUGGEST/STYLE)을 판단한 뒤에만 changes 를 작성합니다.
            오류가 눈에 보일 때만 교정하고(과교정 방지), 원문의 개행 구조를 그대로 보존합니다.
            "⟦"·"⟧" 로 감싸진 보호 구간은 어떤 이유로도 수정하지 말고 change 항목도 만들지 마세요.
            """;

    private final AiHttpTransport transport;
    private final OpenAiProperties properties;
    private final FailoverProperties failoverProperties;
    private final ObjectMapper objectMapper;

    @Override
    public CompletableFuture<AiCorrectionResult> correctAsync(String promptContent, Receiver receiver, Purpose purpose,
                                                              String original, List<Range> protectedRanges) {
        String system = (promptContent == null || promptContent.isBlank())
                ? DEFAULT_CORRECTION_SYSTEM_PROMPT : promptContent;
        String safeOriginal = original == null ? "" : original;
        String preparedOriginal = CorrectionSupport.insertMarkers(safeOriginal, protectedRanges);
        String user = CorrectionSupport.buildUserMessage(receiver, purpose, preparedOriginal);

        String body;
        try {
            body = objectMapper.writeValueAsString(
                    OpenAiRequests.chatJsonSchema(properties.correctionModel(), system, user, "correction", correctionSchema()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        URI uri = URI.create(properties.baseUrl() + "/chat/completions");
        Map<String, String> headers = Map.of("Authorization", "Bearer " + properties.apiKey());
        long start = System.currentTimeMillis();

        return transport.postJson(uri, headers, body, Duration.ofMillis(failoverProperties.deadlineMs()))
                .thenApply(responseBody -> {
                    AiCorrectionResult result = parse(responseBody, safeOriginal, protectedRanges);
                    log.info("gpt_call op=correction durationMs={} outcome=ok", System.currentTimeMillis() - start);
                    return result;
                })
                .exceptionallyCompose(ex -> {
                    log.info("gpt_call op=correction durationMs={} outcome=error error={}",
                            System.currentTimeMillis() - start, AiExceptions.typeName(ex));
                    return CompletableFuture.failedFuture(ex);
                });
    }

    /** OpenAI strict: 모든 객체에 additionalProperties:false + 전 속성 required. reasoning 먼저(순서로 CoT 보존). */
    private Map<String, Object> correctionSchema() {
        Map<String, Object> changeItem = CorrectionSupport.changeItemSchema();
        changeItem.put("additionalProperties", false);

        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("reasoning", Map.of("type", "string"));
        rootProps.put("changes", Map.of("type", "array", "items", changeItem));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", rootProps);
        root.put("required", List.of("reasoning", "changes"));
        root.put("additionalProperties", false);
        return root;
    }

    private AiCorrectionResult parse(String httpBody, String original, List<Range> protectedRanges) {
        CorrectionSupport.CorrectionRaw raw;
        try {
            OpenAiChatResponse response = objectMapper.readValue(httpBody, OpenAiChatResponse.class);
            String content = response == null ? null : response.firstContent();
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("OpenAI correction content is empty");
            }
            raw = objectMapper.readValue(content, CorrectionSupport.CorrectionRaw.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse OpenAI correction response: " + httpBody, e);
        }
        // reasoning 은 폐기(내용 위생) — changes 만 정제해 반환.
        return new AiCorrectionResult(CorrectionSupport.sanitizeChanges(original, protectedRanges, raw.changes()));
    }
}
