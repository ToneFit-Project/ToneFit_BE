package com.example.tonefitserver.domain.generation.ai;

import com.example.tonefitserver.core.ai.AiHttpTransport;
import com.example.tonefitserver.core.ai.OpenAiProperties;
import com.example.tonefitserver.core.enums.Purpose;
import com.example.tonefitserver.core.enums.Receiver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenAiGenerationClient 단위 테스트 — transport mock 으로 요청 형식·응답 파싱 검증(네트워크 불필요).
 */
class OpenAiGenerationClientTest {

    // 전역 매퍼와 동일하게 SNAKE_CASE — 내부 content({generated_subject})가 record 로 매핑되는지 확인용.
    private final ObjectMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    @DisplayName("응답 content(내부 JSON)를 AiGenerationResult 로 파싱한다")
    void parsesResult() throws Exception {
        AiHttpTransport transport = mock(AiHttpTransport.class);
        String canned = """
                {"choices":[{"message":{"role":"assistant","content":"{\\"generated_subject\\":\\"제목\\",\\"generated_email\\":\\"본문\\\\n\\\\n둘째\\"}"}}]}
                """;
        when(transport.postJson(any(URI.class), anyMap(), any(String.class), any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(canned));

        OpenAiGenerationClient client = new OpenAiGenerationClient(
                transport, new OpenAiProperties("k", "https://api.openai.com/v1", "gpt-test", "gpt-test"), mapper);

        AiGenerationResult result = client.generateAsync("system prompt", Receiver.DIRECT_SUPERVISOR,
                Purpose.NOTICE, "상황 내용").get(2, TimeUnit.SECONDS);

        assertThat(result.generatedSubject()).isEqualTo("제목");
        assertThat(result.generatedEmail()).isEqualTo("본문\n\n둘째");
    }

    @Test
    @DisplayName("요청 본문에 model·json_schema(strict)·system/user 메시지가 담긴다")
    void buildsRequest() throws Exception {
        AiHttpTransport transport = mock(AiHttpTransport.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        String canned = """
                {"choices":[{"message":{"content":"{\\"generated_subject\\":\\"t\\",\\"generated_email\\":\\"b\\"}"}}]}
                """;
        when(transport.postJson(any(URI.class), anyMap(), bodyCaptor.capture(), any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(canned));

        OpenAiGenerationClient client = new OpenAiGenerationClient(
                transport, new OpenAiProperties("k", "https://api.openai.com/v1", "gpt-test", "gpt-test"), mapper);
        client.generateAsync(null, Receiver.CLIENT, Purpose.REQUEST, "견적 요청").get(2, TimeUnit.SECONDS);

        JsonNode body = mapper.readTree(bodyCaptor.getValue());
        assertThat(body.get("model").asText()).isEqualTo("gpt-test");
        assertThat(body.get("response_format").get("type").asText()).isEqualTo("json_schema");
        assertThat(body.get("response_format").get("json_schema").get("strict").asText()).isEqualTo("true");
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(body.get("messages").get(1).get("role").asText()).isEqualTo("user");
        assertThat(body.get("messages").get(1).get("content").asText()).contains("수신자 유형:");
    }

    @Test
    @DisplayName("응답이 비면 예외 완료")
    void emptyResponseFails() {
        AiHttpTransport transport = mock(AiHttpTransport.class);
        when(transport.postJson(any(URI.class), anyMap(), any(String.class), any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture("{\"choices\":[]}"));

        OpenAiGenerationClient client = new OpenAiGenerationClient(
                transport, new OpenAiProperties("k", "https://api.openai.com/v1", "gpt-test", "gpt-test"), mapper);

        assertThat(client.generateAsync("s", Receiver.DIRECT_SUPERVISOR, Purpose.NOTICE, "x"))
                .isCompletedExceptionally();
    }
}
