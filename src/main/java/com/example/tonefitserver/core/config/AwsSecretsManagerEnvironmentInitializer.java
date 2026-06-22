package com.example.tonefitserver.core.config;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 운영(prod) 프로파일에서 AWS Secrets Manager 의 tonefit/db, tonefit/app 시크릿을 읽어
 * Spring Environment 에 주입한다.
 *
 * <p>Spring Boot 4 의 bootJar 가 META-INF/spring/*.imports 파일을 BOOT-INF/classes 밖으로 옮겨
 * 자동 SPI 등록이 동작하지 않으므로, TonefitServerApplication.main() 에서
 * SpringApplication.addListeners(...) 로 명시적으로 등록한다.
 */
public class AwsSecretsManagerEnvironmentInitializer
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Region REGION = Region.of("ap-northeast-2");
    private static final String APP_SECRET_NAME = "tonefit/app";
    private static final String DB_SECRET_NAME = "tonefit/db";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        String[] activeProfiles = environment.getActiveProfiles();

        boolean isProd = Arrays.asList(activeProfiles).contains("prod");
        if (!isProd) {
            return;
        }

        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(REGION)
                .build()) {

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> properties = new HashMap<>();

            JsonNode db = mapper.readTree(fetchSecret(client, DB_SECRET_NAME));
            // RDS 연결은 운영에서 항상 SSL 강제 — 평문 fallback 차단
            properties.put("spring.datasource.url",
                    "jdbc:postgresql://" + db.get("host").asText()
                    + ":" + db.get("port").asText()
                    + "/" + db.get("dbname").asText()
                    + "?sslmode=require");
            properties.put("spring.datasource.username", db.get("username").asText());
            properties.put("spring.datasource.password", db.get("password").asText());

            JsonNode app = mapper.readTree(fetchSecret(client, APP_SECRET_NAME));
            properties.put("jwt.secret", app.get("JWT_SECRET").asText());
            properties.put("gemini.api-key", app.get("GEMINI_API_KEY").asText());
            properties.put("gemini.model", app.get("GEMINI_MODEL").asText());
            // 회신 보조 단계(요약·파악·점검)용 저가 모델 — 없으면 gemini.model 로 fallback (FUNC-Rep-15)
            if (app.hasNonNull("GEMINI_LITE_MODEL")) {
                properties.put("gemini.light-model", app.get("GEMINI_LITE_MODEL").asText());
            }
            // 생성·교정 전용 모델·사고수준 — 모두 선택(없으면 application.yml 의 PM 확정 기본값 사용).
            // 운영에서 재배포 없이 모델/사고수준 튜닝 가능하도록 Secrets Manager 키로 노출.
            if (app.hasNonNull("GEMINI_GENERATION_MODEL")) {
                properties.put("gemini.generation-model", app.get("GEMINI_GENERATION_MODEL").asText());
            }
            if (app.hasNonNull("GEMINI_GENERATION_THINKING_BUDGET")) {
                properties.put("gemini.generation-thinking-budget", app.get("GEMINI_GENERATION_THINKING_BUDGET").asText());
            }
            if (app.hasNonNull("GEMINI_CORRECTION_MODEL")) {
                properties.put("gemini.correction-model", app.get("GEMINI_CORRECTION_MODEL").asText());
            }
            if (app.hasNonNull("GEMINI_CORRECTION_THINKING_BUDGET")) {
                properties.put("gemini.correction-thinking-budget", app.get("GEMINI_CORRECTION_THINKING_BUDGET").asText());
            }
            if (app.hasNonNull("GEMINI_CORRECTION_THINKING_LEVEL")) {
                properties.put("gemini.correction-thinking-level", app.get("GEMINI_CORRECTION_THINKING_LEVEL").asText());
            }
            // Google OAuth audience(client_id) whitelist — ID token 검증용. 콤마 구분 다중 허용.
            if (app.hasNonNull("GOOGLE_OAUTH_CLIENT_IDS")) {
                properties.put("google.oauth.client-ids", app.get("GOOGLE_OAUTH_CLIENT_IDS").asText());
            }
            // Amplitude 미러링 — 운영에서 활성화 (선택)
            if (app.hasNonNull("AMPLITUDE_API_KEY")) {
                properties.put("amplitude.api-key", app.get("AMPLITUDE_API_KEY").asText());
                properties.put("amplitude.enabled", "true");
            }

            environment.getPropertySources().addFirst(new MapPropertySource("awsSecretsManager", properties));

        } catch (Exception e) {
            throw new IllegalStateException("AWS Secrets Manager에서 설정을 불러오지 못했습니다.", e);
        }
    }

    private String fetchSecret(SecretsManagerClient client, String secretName) {
        return client.getSecretValue(
                GetSecretValueRequest.builder().secretId(secretName).build()
        ).secretString();
    }
}
