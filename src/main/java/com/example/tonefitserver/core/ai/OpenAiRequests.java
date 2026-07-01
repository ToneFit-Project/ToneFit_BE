package com.example.tonefitserver.core.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 요청 본문 조립(공용). 구조화 출력은 {@code response_format: json_schema(strict)}.
 */
public final class OpenAiRequests {

    private OpenAiRequests() {
    }

    /**
     * system/user 2-메시지 + json_schema(strict) 응답 형식 요청 본문.
     *
     * @param schema strict json schema 의 {@code schema} 객체 (root 는 additionalProperties:false + 전 속성 required 여야 함)
     */
    public static Map<String, Object> chatJsonSchema(String model, String systemPrompt, String userMessage,
                                                     String schemaName, Map<String, Object> schema) {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", schemaName);
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", schema);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)));
        body.put("response_format", Map.of("type", "json_schema", "json_schema", jsonSchema));
        return body;
    }
}
