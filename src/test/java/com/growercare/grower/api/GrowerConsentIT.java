package com.growercare.grower.api;

import static com.growercare.grower.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.grower.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER2_IN;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER_IN;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.grower.support.ConsentApiTestBase;
import com.growercare.grower.support.SeedGrowers;
import com.growercare.security.GcPrincipal;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-GRW-03 R120–R124 (GRW-E120…E124): consents after activation. */
class GrowerConsentIT extends ConsentApiTestBase {

    static final String G1 = Long.toString(SeedGrowers.G1.growerId());

    static Stream<Arguments> readers() {
        return Stream.of(
                argumentSet("GRW-E120 the grower themself", GROWER_IN1, 200),
                argumentSet("GRW-E120 care agent in IN", CARE_IN, 200),
                argumentSet("GRW-E120 retailer2.in, not in scope", RETAILER2_IN, 404));
    }

    @ParameterizedTest
    @MethodSource("readers")
    void currentConsentsAreReadableInScope(GcPrincipal actor, int expected) throws Exception {
        var result = as(actor, get("/growers/{id}/consents", G1)).andExpect(status().is(expected));
        if (expected == 200) {
            result.andExpect(jsonPath("$.items[?(@.purpose == 'SERVICE')].granted").value(true))
                    .andExpect(jsonPath("$.items[?(@.purpose == 'SERVICE')].noticeVersion").value(NOTICE))
                    .andExpect(jsonPath("$.items[?(@.purpose == 'SERVICE')].channel").value("SELF"));
        }
    }

    @Test
    void GRW_E121_theGrowerGrantsAndWithdrawsAnOptionalConsent() throws Exception {
        var g = activated();
        setConsent(g.user(), g.growerId(), "DATA_SHARING_RETAILER", true, NOTICE).andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true)).andExpect(jsonPath("$.channel").value("SELF"));
        setConsent(g.user(), g.growerId(), "DATA_SHARING_RETAILER", false, NOTICE).andExpect(status().isOk());
        as(g.user(), get("/growers/{id}/consents", g.growerId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.purpose == 'DATA_SHARING_RETAILER')].granted").value(false));
        var rows = owner.sql("SELECT count(*) FROM %s.grower_consent WHERE grower_id = ? AND purpose = 'DATA_SHARING_RETAILER'"
                .formatted(schemaOf(g.growerId()))).param(Long.parseLong(g.growerId())).query(Long.class).single();
        Assertions.assertThat(rows).isEqualTo(2);
        Assertions.assertThat(outbox(g.growerId(), "grower.consent.recorded")).hasSize(3);   // SERVICE at activation + 2
    }

    @Test
    void GRW_E122_serviceConsentCannotBeWithdrawnHere() throws Exception {
        problem(setConsent(GROWER_IN1, G1, "SERVICE", false, NOTICE), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.service-consent-required"));
    }

    static Stream<Arguments> notTheGrower() {
        return Stream.of(
                argumentSet("GRW-E123 care agent", CARE_IN),
                argumentSet("GRW-E123 retailer of the grower's partner", RETAILER_IN));
    }

    @ParameterizedTest
    @MethodSource("notTheGrower")
    void optionalConsentsAreTheGrowersOwnDecision(GcPrincipal actor) throws Exception {
        problem(setConsent(actor, G1, "MARKETING", true, NOTICE), 403, "PERMISSION_DENIED");
    }

    @Test
    void GRW_E124_aPendingGrowerHasNoConsentToChangeYet() throws Exception {
        var g = selfRegistered();
        problem(setConsent(g.user(), g.growerId(), "MARKETING", true, NOTICE), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.not-active"));
    }
}
