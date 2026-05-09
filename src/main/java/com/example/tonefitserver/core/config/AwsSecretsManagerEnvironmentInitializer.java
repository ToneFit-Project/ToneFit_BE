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
