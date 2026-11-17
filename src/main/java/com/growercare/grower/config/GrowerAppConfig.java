package com.growercare.grower.config;

import com.growercare.grower.domain.PhoneProtector;
import com.growercare.outbox.OutboxWriter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Phone protection keys (SPEC-GRW-01 D3) and the outbox (ADR-012). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GrowerAppConfig.PhoneKeyProperties.class)
public class GrowerAppConfig {

    /** {@code gc.phone.*}: base64 keys from the environment (Secrets Manager in AWS). Never printed. */
    @ConfigurationProperties("gc.phone")
    public record PhoneKeyProperties(String hmacKey, String encryptionKey, String encryptionKeyId) {
        @Override
        public String toString() {
            return "PhoneKeyProperties[encryptionKeyId=" + encryptionKeyId + "]";
        }
    }

    /** Fails startup on a missing or malformed key (32 bytes each), naming the problem but never the key. */
    @Bean
    PhoneProtector phoneProtector(PhoneKeyProperties keys) {
        return PhoneProtector.fromBase64(keys.hmacKey(), keys.encryptionKey(), keys.encryptionKeyId());
    }

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc, JsonMapper json) {
        return new OutboxWriter(jdbc, json);
    }
}
