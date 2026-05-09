package com.example.tonefitserver;

import com.example.tonefitserver.core.config.AwsSecretsManagerEnvironmentInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class TonefitServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(TonefitServerApplication.class);
        // Spring Boot 4 의 bootJar 가 META-INF/spring/*.imports 파일을 잘못 relocate 해서
        // SPI 자동 등록이 안 되므로 명시적으로 등록한다.
        app.addListeners(new AwsSecretsManagerEnvironmentInitializer());
        app.run(args);
    }
}
