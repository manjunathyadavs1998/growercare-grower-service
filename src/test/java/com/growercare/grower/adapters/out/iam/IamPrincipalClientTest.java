package com.growercare.grower.adapters.out.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.web.client.ResponseCreator;
import com.growercare.grower.support.DevSeedPrincipals;
import com.growercare.common.error.DependencyUnavailableException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class IamPrincipalClientTest {

    static final ClientRegistration IAM = ClientRegistration.withRegistrationId("iam")
            .clientId("grower-service").clientSecret("s").tokenUri("https://kc.test/token")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).build();
    static final OAuth2AuthorizedClientManager TOKENS = request -> new OAuth2AuthorizedClient(IAM, "grower-service",
            new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "svc-token", Instant.now(), Instant.now().plusSeconds(300)));

    MockRestServiceServer server;
    IamPrincipalClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://iam.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new IamPrincipalClient(builder.build(), TOKENS);
    }

    @Test
    void resolvesPrincipalWithServiceToken() {
        var expected = DevSeedPrincipals.CARE_IN;
        server.expect(requestTo("http://iam.test/internal/users/" + expected.subject() + "/principal"))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess(JsonMapper.builder().build().writeValueAsString(expected), MediaType.APPLICATION_JSON));
        assertThat(client.resolve(expected.subject())).contains(expected);
        server.verify();
    }

    /** A response whose body stream breaks mid-read, like a read timeout on a cold start (GRW-E71). */
    static ResponseCreator truncatedBody() {
        return request -> new MockClientHttpResponse(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("closed");
            }
        }, HttpStatus.OK) {
            {
                getHeaders().setContentType(MediaType.APPLICATION_JSON);
            }
        };
    }

    static final OAuth2AuthorizedClientManager REFUSING_TOKENS = request -> {
        throw new ClientAuthorizationException(
                new OAuth2Error("invalid_client"), "iam");
    };

    /** IAM behaviour (null = token endpoint fails first), expected: empty principal (true) or dependency failure (false). */
    static Stream<Arguments> failures() {
        return Stream.of(
                argumentSet("GRW-E71 IAM 5xx", withServerError(), false),
                argumentSet("GRW-E71 unreadable body (cold-start read timeout)", truncatedBody(), false),
                argumentSet("GRW-E71 token endpoint refuses", null, false),
                argumentSet("GRW-E70 404 = no account (401)", withResourceNotFound(), true));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void iamFailuresFailClosed(ResponseCreator response, boolean noAccount) {
        var subject = DevSeedPrincipals.CARE_IN.subject();
        var target = client;
        if (response == null) {
            var builder = RestClient.builder().baseUrl("http://iam.test");
            MockRestServiceServer.bindTo(builder).build();
            target = new IamPrincipalClient(builder.build(), REFUSING_TOKENS);
        } else {
            server.expect(requestTo("http://iam.test/internal/users/" + subject + "/principal")).andRespond(response);
        }
        var finalTarget = target;
        if (noAccount) {
            assertThat(finalTarget.resolve(subject)).isEmpty();
        } else {
            assertThatThrownBy(() -> finalTarget.resolve(subject)).isInstanceOf(DependencyUnavailableException.class);
        }
    }
}
