package com.growercare.grower.security;

import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.grower.support.DevSeedPrincipals;
import com.growercare.grower.support.GrowerTestDatabase;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PrincipalResolver;
import com.growercare.testsupport.RedisTestContainer;
import com.growercare.testsupport.TestJwts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * SPEC-GRW-01 R40–R42 (GRW-E70…E72), wiring level: JWT validation, principal resolution (IAM stubbed), fail closed on
 * an IAM outage, service tokens only on /internal and user tokens never there. Grower endpoints come in increment 3,
 * so "passes security" shows as 404 here.
 */
@SpringBootTest(properties = "gc.migration.enabled=false")   // GrowerTestDatabase migrated it already
@AutoConfigureMockMvc
@Import(GrowerSecurityIT.Stubs.class)
class GrowerSecurityIT {

    static final UUID IAM_DOWN = UUID.fromString("00000000-0000-4000-8000-0000000000d0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        var db = GrowerTestDatabase.migrated();
        r.add("spring.datasource.url", db::jdbcUrl);
        r.add("spring.datasource.username", db::appUser);
        r.add("spring.datasource.password", db::appPassword);
        r.add("spring.data.redis.host", RedisTestContainer::host);
        r.add("spring.data.redis.port", RedisTestContainer::port);
        r.add("spring.data.redis.password", () -> RedisTestContainer.PASSWORD);
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder(List.of("gc-api", "gc-internal"));
        }

        /** Stands in for IAM: dev-seed principals, plus one subject whose lookup fails like an IAM outage. */
        @Bean
        @Primary
        PrincipalResolver stubIam() {
            Map<UUID, GcPrincipal> known = DevSeedPrincipals.all().stream()
                    .collect(Collectors.toMap(GcPrincipal::subject, Function.identity()));
            return subject -> {
                if (IAM_DOWN.equals(subject)) {
                    throw new DependencyUnavailableException("identity-access-service", null);
                }
                return Optional.ofNullable(known.get(subject));
            };
        }
    }

    @Autowired
    MockMvc mvc;

    static ResultActions problem(ResultActions r, int status, String code) throws Exception {
        return r.andExpect(status().is(status))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value(code));
    }

    static Stream<Arguments> rejectedCallers() {
        return Stream.of(
                argumentSet("GRW-E70 no token", "/growers/1", (Supplier<String>) () -> null, 401, "UNAUTHENTICATED"),
                argumentSet("GRW-E70 garbage token", "/growers/1", (Supplier<String>) () -> "Bearer not.a.jwt", 401, "UNAUTHENTICATED"),
                argumentSet("GRW-E70 valid token of an unknown subject", "/growers/1",
                        (Supplier<String>) () -> TestJwts.user(UUID.randomUUID()).bearer(), 401, "UNAUTHENTICATED"),
                argumentSet("GRW-E71 IAM unreachable fails closed", "/growers/1",
                        (Supplier<String>) () -> TestJwts.user(IAM_DOWN).bearer(), 503, "DEPENDENCY_UNAVAILABLE"),
                argumentSet("GRW-E72 allow-listed service token on the user API", "/growers/1",
                        (Supplier<String>) () -> TestJwts.service("case-service").bearer(), 403, "PERMISSION_DENIED"),
                argumentSet("GRW-E72 service client not allow-listed", "/internal/growers/1",
                        (Supplier<String>) () -> TestJwts.service("integration-service").bearer(), 401, "UNAUTHENTICATED"),
                argumentSet("GRW-E72 user token on /internal", "/internal/growers/1",
                        (Supplier<String>) () -> TestJwts.user(TestJwts.CARE_IN).bearer(), 403, "PERMISSION_DENIED"));
    }

    @ParameterizedTest
    @MethodSource("rejectedCallers")
    void rejectedCallers(String path, Supplier<String> authorization, int status, String code) throws Exception {
        var request = get(path);
        var header = authorization.get();
        if (header != null) {
            request.header("Authorization", header);
        }
        problem(mvc.perform(request), status, code);
    }

    static Stream<Arguments> acceptedCallers() {
        return Stream.of(
                argumentSet("GRW-R40 known user passes security", "/growers/1", TestJwts.user(TestJwts.CARE_IN).bearer()),
                argumentSet("GRW-R42 allow-listed service client passes /internal", "/internal/growers/1", TestJwts.service("case-service").bearer()));
    }

    /** Wiring check: callers that pass security reach routing (404 until the endpoints exist in increment 3). */
    @ParameterizedTest
    @MethodSource("acceptedCallers")
    void acceptedCallersPassSecurity(String path, String authorization) throws Exception {
        mvc.perform(get(path).header("Authorization", authorization)).andExpect(status().isNotFound());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
