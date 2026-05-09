package com.example.tonefitserver.domain.event.amplitude;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AmplitudeProperties.class)
@ConditionalOnProperty(name = "amplitude.enabled", havingValue = "true")
public class AmplitudeConfig {

    @Bean
    public RestClient amplitudeRestClient(AmplitudeProperties properties, RestClient.Builder builder) {
        return builder
                .baseUrl(properties.baseUrl())
                .build();
    }
}
