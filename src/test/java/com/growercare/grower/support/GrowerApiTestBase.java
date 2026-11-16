package com.growercare.grower.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.security.GcPrincipal;
import com.growercare.security.PrincipalResolver;
import com.growercare.testsupport.RedisTestContainer;
import com.growercare.testsupport.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared Spring context for grower API tests (one cached context for all subclasses): light test database, Redis
 * container, test JWTs, and IAM replaced by {@link DevSeedPrincipals}.
 */
@SpringBootTest(properties = "gc.migration.enabled=false")
@AutoConfigureMockMvc
@Import(GrowerApiTestBase.Stubs.class)
public abstract class GrowerApiTestBase {

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
    public static class Stubs {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder(List.of("gc-api", "gc-internal"));
        }

        @Bean
        @Primary
        PrincipalResolver stubIam() {
            return DevSeedPrincipals::resolve;
        }
    }

    protected static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicLong NEXT_TEST_GROWER = new AtomicLong(950);

    @Autowired
    protected MockMvc mvc;

    protected final JdbcClient owner = JdbcClient.create(GrowerTestDatabase.migrated().ownerDataSource());

    /** A GROWER account that has not registered yet (unique per call). */
    protected static GcPrincipal newGrowerUser() {
        return DevSeedPrincipals.testGrower(NEXT_TEST_GROWER.getAndIncrement());
    }

    protected ResultActions as(GcPrincipal actor, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", TestJwts.user(actor.subject()).bearer()));
    }

    protected ResultActions register(GcPrincipal actor, Map<String, Object> body, UUID idempotencyKey) throws Exception {
        var request = post("/growers").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey.toString());
        }
        return as(actor, request);
    }

    /** Registers and returns the new grower's id (string, as in the API). */
    protected String registerGrower(GcPrincipal actor, Map<String, Object> body) throws Exception {
        var json = register(actor, body, UUID.randomUUID()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.growerId");
    }

    protected ResultActions getGrower(GcPrincipal actor, String growerId) throws Exception {
        return as(actor, get("/growers/{id}", growerId));
    }

    protected ResultActions search(GcPrincipal actor, String phone, String countryCode) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("phone", phone);
        body.put("countryCode", countryCode);
        return as(actor, post("/growers/search").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    protected static ResultActions problem(ResultActions r, int status, String code) throws Exception {
        return r.andExpect(status().is(status))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value(code));
    }

    /** An assisted registration in Pune with a fresh phone in a test shard. */
    protected static Map<String, Object> assisted() {
        var body = new LinkedHashMap<String, Object>();
        body.put("territoryId", "1002");
        body.put("displayName", "Anil Shinde");
        body.put("phone", TestPhones.nextIndian());
        body.put("preferredLanguage", "mr");
        return body;
    }

    protected static Map<String, Object> with(Map<String, Object> body, Object... keyValues) {
        var copy = new LinkedHashMap<>(body);
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] == null) {
                copy.remove((String) keyValues[i]);
            } else {
                copy.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return copy;
    }
}
