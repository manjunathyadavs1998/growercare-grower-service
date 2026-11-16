package com.growercare.grower.adapters.out.iam;

import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.security.GcPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resolves principals through identity-access-service ({@code GET /internal/users/{sub}/principal}) using a
 * client-credentials token (Keycloak client {@code grower-service}, audience {@code gc-internal}).
 * 404 → no active account; anything else that prevents a principal (network errors, timeouts, unreadable bodies, 5xx,
 * token endpoint failures) → {@link DependencyUnavailableException} (503, fail closed; SPEC-GRW-01 R41, as SPEC-CASE-01 R9/R9a).
 */
public final class IamPrincipalClient {

    static final String REGISTRATION = "iam";

    private final RestClient rest;
    private final OAuth2AuthorizedClientManager tokens;

    public IamPrincipalClient(RestClient rest, OAuth2AuthorizedClientManager tokens) {
        this.rest = rest;
        this.tokens = tokens;
    }

    /** Backs {@link com.growercare.security.PrincipalResolver} (behind the Redis cache). */
    public Optional<GcPrincipal> resolve(UUID subject) {
        return fetch("/internal/users/{id}/principal", subject);
    }

    private Optional<GcPrincipal> fetch(String path, Object id) {
        try {
            var principal = rest.get()
                    .uri(path, id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .retrieve()
                    .body(GcPrincipal.class);
            return Optional.ofNullable(principal);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw new DependencyUnavailableException("identity-access-service", e);
        } catch (RestClientException                    // network, timeouts, unreadable body (R9a)
                 | OAuth2AuthorizationException  // token endpoint failed (R9a)
                 | IllegalStateException e) {
            throw new DependencyUnavailableException("identity-access-service", e);
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
