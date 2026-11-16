package com.growercare.grower.config;

import com.growercare.grower.adapters.out.iam.IamPrincipalClient;
import com.growercare.grower.domain.GrowerAccess;
import com.growercare.security.PolicyEnforcer;
import com.growercare.security.PrincipalResolver;
import com.growercare.security.cache.CachingPrincipalResolver;
import com.growercare.security.web.GcSecurityProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * grower-service security (SPEC-GRW-01 R1–R5, R40–R42): principals come from IAM over HTTP (client credentials),
 * cached in Redis (ADR-011); grower access on top of the shared ABAC engine ({@link GrowerAccess}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GrowerSecurityConfig.IamClientProperties.class)
public class GrowerSecurityConfig {

    /** {@code gc.iam.*}: where IAM lives and how long we wait for it. */
    @ConfigurationProperties("gc.iam")
    public record IamClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        public IamClientProperties {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(2) : readTimeout;
        }
    }

    /** Client-credentials tokens outside any user request (no HttpServletRequest needed), cached until expiry. */
    @Bean
    OAuth2AuthorizedClientManager iamTokenManager(ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    IamPrincipalClient iamPrincipalClient(IamClientProperties iam, OAuth2AuthorizedClientManager tokens) {
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(iam.connectTimeout()).build());
        requestFactory.setReadTimeout(iam.readTimeout());
        return new IamPrincipalClient(RestClient.builder().baseUrl(iam.baseUrl()).requestFactory(requestFactory).build(), tokens);
    }

    /** Request authentication: IAM behind the Redis cache (ADR-011). Grant changes take effect within the TTL. */
    @Bean
    PrincipalResolver principalResolver(IamPrincipalClient iam, StringRedisTemplate redis, JsonMapper json, GcSecurityProperties security) {
        return new CachingPrincipalResolver(iam::resolve, redis, json, security.principalCacheTtl());
    }

    /** Grower rules on top of gc-security's {@link PolicyEnforcer} (which, like {@code ScopeFilter}, it defines). */
    @Bean
    GrowerAccess growerAccess(PolicyEnforcer enforcer) {
        return new GrowerAccess(enforcer);
    }
}
