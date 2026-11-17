package com.growercare.grower.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.grower.support.GrowerApiTestBase;
import com.growercare.grower.support.SeedGrowers;
import com.growercare.grower.support.TestPhones;
import com.growercare.testsupport.TestJwts;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-GRW-01 R42 (GRW-E72): the replica view case-service reads (SPEC-CASE-06). */
class InternalGrowerApiIT extends GrowerApiTestBase {

    static final String CASE_SERVICE = TestJwts.service("case-service").bearer();

    @Test
    void GRW_E72_caseServiceReadsTheUnmaskedReplicaViewWithThePhoneHmac() throws Exception {
        var g1 = Long.toString(SeedGrowers.G1.growerId());
        var json = mvc.perform(get("/internal/growers/{id}", g1).header("Authorization", CASE_SERVICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.growerId").value(g1))
                .andExpect(jsonPath("$.countryCode").value("IN"))
                .andExpect(jsonPath("$.territoryId").value("1002"))
                .andExpect(jsonPath("$.userId").value("1"))
                .andExpect(jsonPath("$.displayName").value("Ramesh Patil"))     // unmasked: a service, not a person
                .andExpect(jsonPath("$.phoneLast4").value("3210"))
                .andExpect(jsonPath("$.preferredPartnerOrgId").value("501"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").isNumber())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String hmac = com.jayway.jsonpath.JsonPath.read(json, "$.phoneHmac");
        assertThat(Base64.getDecoder().decode(hmac)).isEqualTo(TestPhones.hmacOf("+919876543210"));
    }

    static Stream<Arguments> notFound() {
        return Stream.of(
                argumentSet("GRW-E72 unknown id", "999"),
                argumentSet("GRW-E72 malformed id", "abc"),
                argumentSet("GRW-E72 id above Long.MAX_VALUE", "9999999999999999999"));
    }

    @ParameterizedTest
    @MethodSource("notFound")
    void unknownGrowersAre404(String growerId) throws Exception {
        problem(mvc.perform(get("/internal/growers/{id}", growerId).header("Authorization", CASE_SERVICE)), 404, "NOT_FOUND");
    }
}
