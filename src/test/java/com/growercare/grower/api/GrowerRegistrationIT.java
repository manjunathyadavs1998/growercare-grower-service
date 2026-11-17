package com.growercare.grower.api;

import static com.growercare.grower.support.DevSeedPrincipals.ADMIN_IN;
import static com.growercare.grower.support.DevSeedPrincipals.AGRO_IN;
import static com.growercare.grower.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.grower.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER_IN;
import static com.growercare.grower.support.DevSeedPrincipals.TM_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.grower.domain.GrowerPlacement;
import com.growercare.grower.support.GrowerApiTestBase;
import com.growercare.grower.support.GrowerTestDatabase;
import com.growercare.grower.support.SeedGrowers;
import com.growercare.grower.support.TestPhones;
import com.growercare.security.GcPrincipal;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-GRW-01 R10–R18 (GRW-E20…E33): registering growers. */
class GrowerRegistrationIT extends GrowerApiTestBase {

    @Test
    void GRW_E20_aGrowerRegistersThemselvesAndIsActive() throws Exception {
        var user = newGrowerUser();
        var body = with(assisted(), "displayName", "Anil Shinde");
        var json = register(user, body, UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.onboardingChannel").value("SELF"))
                .andExpect(jsonPath("$.userId").value(Long.toString(user.userId())))
                .andExpect(jsonPath("$.countryCode").value("IN"))
                .andExpect(jsonPath("$.phoneLast4").value(((String) body.get("phone")).substring(9)))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String growerId = JsonPath.read(json, "$.growerId");

        // the event carries ids only: no name, no phone, no HMAC
        var shard = GrowerTestDatabase.ROUTER.forId(Long.parseLong(growerId)).orElseThrow();
        var payload = owner.sql("SELECT payload::text FROM %s.outbox WHERE aggregate_id = ? AND event_type = 'grower.registered'"
                .formatted(shard.schemaName())).param(growerId).query(String.class).single();
        var event = JSON.readTree(payload).get("data");
        assertThat(event.get("growerId").asString()).isEqualTo(growerId);
        assertThat(event.get("onboardingChannel").asString()).isEqualTo("SELF");
        assertThat(event.get("userId").asString()).isEqualTo(Long.toString(user.userId()));
        assertThat(payload).doesNotContain("Anil", ((String) body.get("phone")).substring(3), "phone", "Hmac");
    }

    @Test
    void GRW_E21_assistedRegistrationIsPendingConsentAndNormalisesThePhone() throws Exception {
        var e164 = TestPhones.nextIndian();
        var national = e164.substring(3);   // "+91" stripped: national format
        var growerId = registerGrower(AGRO_IN, with(assisted(), "phone", national.substring(0, 5) + " " + national.substring(5)));
        getGrower(AGRO_IN, growerId)
                .andExpect(jsonPath("$.status").value("PENDING_CONSENT"))
                .andExpect(jsonPath("$.onboardingChannel").value("ASSISTED"))
                .andExpect(jsonPath("$.onboardedByUserId").value("102"))
                .andExpect(jsonPath("$.userId").doesNotExist());
        var shard = GrowerTestDatabase.ROUTER.forId(Long.parseLong(growerId)).orElseThrow();
        var stored = owner.sql("SELECT phone_hmac FROM %s.grower WHERE grower_id = ?".formatted(shard.schemaName()))
                .param(Long.parseLong(growerId)).query(byte[].class).single();
        assertThat(stored).isEqualTo(TestPhones.hmacOf(e164));
    }

    @Test
    void GRW_E22_aRetailerOnboardsAGrowerAndThenSeesIt() throws Exception {
        var growerId = registerGrower(RETAILER_IN, assisted());
        getGrower(RETAILER_IN, growerId).andExpect(status().isOk()).andExpect(jsonPath("$.onboardedViaOrgId").value("501"));
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
                argumentSet("GRW-E23 R12 grower.in1 already registered", GROWER_IN1, Map.of(), 409, "DUPLICATE"),
                argumentSet("GRW-E25 R14 tm.in outside Maharashtra", TM_IN, Map.of("territoryId", "1005"), 404, "NOT_FOUND"),
                argumentSet("GRW-E26 R14 care.in in a BR territory", CARE_IN, Map.of("territoryId", "2002"), 404, "NOT_FOUND"),
                argumentSet("GRW-E27a R14 IN partner in a BR territory", RETAILER_IN, Map.of("territoryId", "2002"), 404, "NOT_FOUND"),
                argumentSet("GRW-E26a R14 unknown territory", CARE_IN, Map.of("territoryId", "999999"), 404, "NOT_FOUND"),
                argumentSet("GRW-E27 R14 preferred partner of another country", CARE_IN, Map.of("preferredPartnerOrgId", "504"),
                        422, "BUSINESS_RULE_VIOLATION"),
                argumentSet("GRW-E33 R4 admin.in has no grower:create", ADMIN_IN, Map.of(), 403, "PERMISSION_DENIED"));
    }

    @ParameterizedTest
    @MethodSource("rejected")
    void rejectedRegistrations(GcPrincipal actor, Map<String, Object> changes, int status, String code) throws Exception {
        var body = assisted();
        changes.forEach(body::put);
        problem(register(actor, body, UUID.randomUUID()), status, code);
    }

    @Test
    void GRW_E24_aKnownPhoneIsADuplicateAndTheOtherGrowerStaysHidden() throws Exception {
        problem(register(CARE_IN, with(assisted(), "phone", "+919876543210"), UUID.randomUUID()), 409, "DUPLICATE")
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString(Long.toString(SeedGrowers.G1.growerId())))));
    }

    @Test
    void GRW_E27_theRuleIdIsNamed() throws Exception {
        register(CARE_IN, with(assisted(), "preferredPartnerOrgId", "504"), UUID.randomUUID())
                .andExpect(jsonPath("$.rule").value("grower.partner-invalid"));
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                argumentSet("GRW-E28 blank name", "displayName", "", "displayName"),
                argumentSet("GRW-E28 121-char name", "displayName", "x".repeat(121), "displayName"),
                argumentSet("GRW-E28 control character (NUL) in the name", "displayName", "Anil\u0000Shinde", "displayName"),
                argumentSet("GRW-E28 phone of another country", "phone", "+55 11 91234 5678", "phone"),
                argumentSet("GRW-E28 phone too short", "phone", "12345", "phone"),
                argumentSet("GRW-E28 unsupported language", "preferredLanguage", "xx", "preferredLanguage"),
                argumentSet("GRW-E28 malformed territory id", "territoryId", "abc", "territoryId"),
                argumentSet("GRW-E28 malformed partner id", "preferredPartnerOrgId", "5x", "preferredPartnerOrgId"),
                argumentSet("GRW-E28 territory id above Long.MAX_VALUE", "territoryId", "9999999999999999999", "territoryId"),
                argumentSet("GRW-E28 partner id above Long.MAX_VALUE", "preferredPartnerOrgId", "9999999999999999999", "preferredPartnerOrgId"));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void invalidInputIs400WithTheField(String field, String value, String errorField) throws Exception {
        problem(register(CARE_IN, with(assisted(), field, value), UUID.randomUUID()), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[*].field", hasItem(errorField)));
    }

    @Test
    void GRW_E28_missingIdempotencyKeyIs400() throws Exception {
        problem(register(CARE_IN, assisted(), null), 400, "VALIDATION_FAILED");
    }

    @Test
    void GRW_E29_a120CharNameIsAccepted() throws Exception {
        register(CARE_IN, with(assisted(), "displayName", "x".repeat(120)), UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    void GRW_E30_E31_idempotentReplayAndKeyReuse() throws Exception {
        var key = UUID.randomUUID();
        var body = assisted();
        var first = register(CARE_IN, body, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String growerId = JsonPath.read(first, "$.growerId");
        register(CARE_IN, body, key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.growerId").value(growerId));
        var shard = GrowerTestDatabase.ROUTER.forId(Long.parseLong(growerId)).orElseThrow();
        int events = owner.sql("SELECT count(*) FROM %s.outbox WHERE aggregate_id = ?".formatted(shard.schemaName()))
                .param(growerId).query(Integer.class).single();
        assertThat(events).isEqualTo(1);
        problem(register(CARE_IN, with(body, "phone", TestPhones.nextIndian()), key), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void GRW_E30_aSelfRegistrationRetriedWithTheSameKeyIsReplayedNotADuplicate() throws Exception {
        var user = newGrowerUser();
        var key = UUID.randomUUID();
        var body = assisted();
        register(user, body, key).andExpect(status().isCreated());
        register(user, body, key).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
        problem(register(user, with(body, "phone", TestPhones.nextIndian()), UUID.randomUUID()), 409, "DUPLICATE");   // R12
    }

    @Test
    void GRW_E32_theIdDecodesToTheShardOfThePhoneAndTheDirectoryRowIsThere() throws Exception {
        var body = assisted();
        var growerId = Long.parseLong(registerGrower(CARE_IN, body));
        var hmac = TestPhones.hmacOf((String) body.get("phone"));
        var shard = GrowerTestDatabase.ROUTER.forId(growerId).orElseThrow();
        assertThat(shard.shardNo()).isEqualTo(GrowerPlacement.shardNo(hmac));
        long directory = owner.sql("SELECT grower_id FROM %s.phone_directory WHERE phone_hmac = ?".formatted(shard.schemaName()))
                .param(hmac).query(Long.class).single();
        assertThat(directory).isEqualTo(growerId);
    }
}
