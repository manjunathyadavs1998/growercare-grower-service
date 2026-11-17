package com.growercare.grower.adapters.out.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.growercare.common.error.DependencyUnavailableException;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/** SPEC-GRW-03 D2, R104, R131: how a one-time code is handed to notification-service (SPEC-NOT-01 §3). */
class NotificationClientTest {

    static final ClientRegistration IAM = ClientRegistration.withRegistrationId("iam")
            .clientId("grower-service").clientSecret("s").tokenUri("https://kc.test/token")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).build();
    static final OAuth2AuthorizedClientManager TOKENS = request -> new OAuth2AuthorizedClient(IAM, "grower-service",
            new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "svc-token", Instant.now(), Instant.now().plusSeconds(300)));
    static final UUID KEY = UUID.fromString("7d1f6b3e-0000-4000-8000-000000000001");

    MockRestServiceServer server;
    NotificationClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://notification.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NotificationClient(builder.build(), TOKENS);
    }

    @Test
    void GRW_E101_theCodeIsSentAsAnOtpSmsWithTheVerificationAsIdempotencyKey() {
        server.expect(requestTo("http://notification.test/internal/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andExpect(header("Idempotency-Key", KEY.toString()))
                .andExpect(content().json("""
                        {"channel": "SMS", "recipient": "+919876543210", "template": "otp.activation", "language": "mr",
                         "category": "OTP", "params": {"code": "482913", "minutes": "5"}}"""))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
        assertThatCode(() -> client.sendOtp("+919876543210", "mr", "482913", 5, KEY)).doesNotThrowAnyException();
        server.verify();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                argumentSet("GRW-E105 notification-service down", withServerError()),
                argumentSet("GRW-E105 template or language refused", withStatus(HttpStatus.UNPROCESSABLE_ENTITY)),
                argumentSet("GRW-E105 client not allow-listed", withStatus(HttpStatus.UNAUTHORIZED)));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void anyRefusalIs503WithoutThePhoneOrTheCode(ResponseCreator response) {
        server.expect(requestTo("http://notification.test/internal/messages")).andRespond(response);
        assertThatThrownBy(() -> client.sendOtp("+919876543210", "mr", "482913", 5, KEY))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasNoCause()
                .message().doesNotContain("9876543210", "482913");
    }

    @Test
    void GRW_E105_noTokenIs503() {
        var noTokens = new NotificationClient(RestClient.builder().baseUrl("http://notification.test").build(), request -> null);
        assertThatThrownBy(() -> noTokens.sendOtp("+919876543210", "mr", "482913", 5, KEY))
                .isInstanceOf(DependencyUnavailableException.class);
    }
}
