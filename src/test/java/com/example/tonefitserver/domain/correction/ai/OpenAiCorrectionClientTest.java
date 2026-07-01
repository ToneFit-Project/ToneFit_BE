package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.core.ai.AiHttpTransport;
import com.example.tonefitserver.core.ai.OpenAiProperties;
import com.example.tonefitserver.core.enums.Purpose;
import com.example.tonefitserver.core.enums.Receiver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenAiCorrectionClient 단위 테스트 — transport mock 으로 파싱 + 공용 sanitize 검증(네트워크 불필요).
 */
class OpenAiCorrectionClientTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    @DisplayName("응답의 changes 를 파싱·정제해 원문 위치가 채워진 결과 반환")
    void parsesAndSanitizes() throws Exception {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("index", 0);
        change.put("original", "회신 바랍니다");
        change.put("corrected", "회신 부탁드립니다");
        change.put("reason", "정중형 제안");
        change.put("label", "SUGGEST");
        change.put("confidence", 0.9);
        change.put("applied_rules", List.of());
        String inner = mapper.writeValueAsString(Map.of("reasoning", "사고", "changes", List.of(change)));
        String canned = mapper.writeValueAsString(
                Map.of("choices", List.of(Map.of("message", Map.of("content", inner)))));

        AiHttpTransport transport = mock(AiHttpTransport.class);
        when(transport.postJson(any(URI.class), anyMap(), any(String.class), any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(canned));

        OpenAiCorrectionClient client = new OpenAiCorrectionClient(
                transport, new OpenAiProperties("k", "https://api.openai.com/v1", "gpt-test", "gpt-test"), mapper);

        AiCorrectionResult result = client.correctAsync("system", Receiver.DIRECT_SUPERVISOR,
                Purpose.NOTICE, "안녕하세요. 회신 바랍니다.", null).get(2, TimeUnit.SECONDS);

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).corrected()).isEqualTo("회신 부탁드립니다");
        assertThat("안녕하세요. 회신 바랍니다.".substring(
                result.changes().get(0).start(), result.changes().get(0).end())).isEqualTo("회신 바랍니다");
    }

    @Test
    @DisplayName("빈 content 응답은 예외 완료")
    void emptyContentFails() {
        AiHttpTransport transport = mock(AiHttpTransport.class);
        when(transport.postJson(any(URI.class), anyMap(), any(String.class), any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture("{\"choices\":[]}"));

        OpenAiCorrectionClient client = new OpenAiCorrectionClient(
                transport, new OpenAiProperties("k", "https://api.openai.com/v1", "gpt-test", "gpt-test"), mapper);

        assertThat(client.correctAsync("s", Receiver.DIRECT_SUPERVISOR, Purpose.NOTICE, "안녕하세요.", null))
                .isCompletedExceptionally();
    }
}
