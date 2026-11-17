package com.growercare.grower.adapters.out.notification;

import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.grower.application.port.MessageSender;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link MessageSender} over notification-service's {@code POST /internal/messages} (SPEC-NOT-01), with the same
 * client-credentials token grower-service uses for IAM (Keycloak client {@code grower-service}, audience gc-internal).
 * Any failure, including a 4xx, means the code was not sent: 503 (SPEC-GRW-03 R104). The phone and the code are never
 * logged; exceptions carry neither.
 */
public final class NotificationClient implements MessageSender {

    static final String REGISTRATION = "iam";        // the grower-service client registration (one client, two audiences)

    private final RestClient rest;
    private final OAuth2AuthorizedClientManager tokens;

    public NotificationClient(RestClient rest, OAuth2AuthorizedClientManager tokens) {
        this.rest = rest;
        this.tokens = tokens;
    }

    @Override
    public void sendOtp(String phoneE164, String language, String code, int validMinutes, UUID idempotencyKey) {
        var body = Map.of(
                "channel", "SMS",
                "recipient", phoneE164,
                "template", "otp.activation",
                "language", language,
                "category", "OTP",
                "params", Map.of("code", code, "minutes", Integer.toString(validMinutes)));
        try {
            rest.post()
                    .uri("/internal/messages")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .header("Idempotency-Key", idempotencyKey.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException | OAuth2AuthorizationException | IllegalStateException e) {
            // drop the cause: a RestClient exception may quote the request (phone, code)
            throw new DependencyUnavailableException("notification-service (" + e.getClass().getSimpleName() + ")", null);
        }
    }

    private String accessToken() {
        var request = OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION).principal("grower-service").build();
        var client = tokens.authorize(request);
        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException("No client-credentials token for " + REGISTRATION);
        }
        return client.getAccessToken().getTokenValue();
    }
}
