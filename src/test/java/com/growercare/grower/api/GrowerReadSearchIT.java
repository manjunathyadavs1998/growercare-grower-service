package com.growercare.grower.api;

import static com.growercare.grower.support.DevSeedPrincipals.AGRO_IN;
import static com.growercare.grower.support.DevSeedPrincipals.CARE_BR;
import static com.growercare.grower.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.grower.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER2_IN;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER_IN;
import static com.growercare.grower.support.DevSeedPrincipals.TM_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.grower.support.GrowerApiTestBase;
import com.growercare.grower.support.SeedGrowers;
import com.growercare.security.GcPrincipal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-GRW-01 R20–R22 (GRW-E40…E42) and R30–R32 (GRW-E60…E65): reading and finding growers. */
class GrowerReadSearchIT extends GrowerApiTestBase {

    static final String G1 = Long.toString(SeedGrowers.G1.growerId());

    @Test
    void GRW_E40_aGrowerInScopeIsReturnedWithItsVersion() throws Exception {
        getGrower(CARE_IN, G1).andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.growerId").value(G1))
                .andExpect(jsonPath("$.territoryId").value("1002"));
    }

    static Stream<Arguments> notFound() {
        // country bits 63 (no such country) with shard 1: decodes, but routes nowhere
        long unknownCountry = (1L << 22) | (63L << 16) | (1L << 10);
        return Stream.of(
                argumentSet("GRW-E41 out of scope (care.br, IN grower)", CARE_BR, G1),
                argumentSet("GRW-E41 unknown id", CARE_IN, "999"),
                argumentSet("GRW-E41 malformed id", CARE_IN, "abc"),
                argumentSet("GRW-E41 19-digit id above Long.MAX_VALUE", CARE_IN, "9999999999999999999"),
                argumentSet("GRW-E41 id of an unknown country", CARE_IN, Long.toString(unknownCountry)));
    }

    @ParameterizedTest
    @MethodSource("notFound")
    void outOfScopeUnknownAndMalformedAreTheSame404(GcPrincipal actor, String growerId) throws Exception {
        var body = problem(getGrower(actor, growerId), 404, "NOT_FOUND").andReturn().getResponse().getContentAsString();
        // identical apart from the request echo (instance) and the trace id: nothing reveals which case it was
        java.util.Map<String, Object> problem = new java.util.HashMap<>(JSON.readValue(body, new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { }));
        problem.remove("instance");
        problem.remove("traceId");
        assertThat(problem).isEqualTo(java.util.Map.of("type", "https://errors.growercare.example/not-found", "title", "Resource not found",
                "status", 404, "detail", "Grower not found", "code", "NOT_FOUND", "resource", "Grower"));
    }

    static Stream<Arguments> masking() {
        return Stream.of(
                argumentSet("GRW-E42 the grower themself: FULL", GROWER_IN1, "Ramesh Patil"),
                argumentSet("GRW-E42 field agronomist: FULL", AGRO_IN, "Ramesh Patil"),
                argumentSet("GRW-E42 territory manager: FULL", TM_IN, "Ramesh Patil"),
                argumentSet("GRW-E42 care agent: MASKED", CARE_IN, "R***** P****"),
                argumentSet("GRW-E42 retailer: MASKED", RETAILER_IN, "R***** P****"));
    }

    @ParameterizedTest
    @MethodSource("masking")
    void namesAreMaskedByClearanceAndThePhoneNeverLeaves(GcPrincipal actor, String expectedName) throws Exception {
        getGrower(actor, G1).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value(expectedName))
                .andExpect(jsonPath("$.phoneLast4").value("3210"))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.phoneHmac").doesNotExist());
    }

    static Stream<Arguments> searches() {
        return Stream.of(
                argumentSet("GRW-E60 E.164", CARE_IN, "+919876543210", "IN", 1),
                argumentSet("GRW-E61 national format with spaces", CARE_IN, "98765 43210", "IN", 1),
                argumentSet("GRW-E62 unregistered number", CARE_IN, com.growercare.grower.support.TestPhones.nextIndian(), "IN", 0),   // a test-DB shard
                argumentSet("GRW-E63 out of scope looks like unregistered", RETAILER2_IN, "+919876543210", "IN", 0),
                argumentSet("GRW-E64 country outside the caller's scope", CARE_BR, "+919876543210", "IN", 0));
    }

    @ParameterizedTest
    @MethodSource("searches")
    void findByPhone(GcPrincipal actor, String phone, String country, int found) throws Exception {
        var result = search(actor, phone, country).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(found));
        if (found == 1) {
            result.andExpect(jsonPath("$.items[0].growerId").value(G1)).andExpect(jsonPath("$.items[0].displayName").value("R***** P****"));
        }
    }

    @Test
    void GRW_E60_anInvalidPhoneIs400() throws Exception {
        problem(search(CARE_IN, "12345", "IN"), 400, "VALIDATION_FAILED");
    }

    @Test
    void GRW_E65_thePhoneIsNeverAcceptedInAUrl() throws Exception {
        var status = as(CARE_IN, get("/growers").param("phone", "+919876543210")).andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(200);
    }
}
