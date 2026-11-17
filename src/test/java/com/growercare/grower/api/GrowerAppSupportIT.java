package com.growercare.grower.api;

import static com.growercare.grower.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.grower.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER2_IN;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER_IN;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.grower.support.GrowerApiTestBase;
import com.growercare.grower.support.SeedGrowers;
import com.growercare.security.GcPrincipal;
import com.jayway.jsonpath.JsonPath;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-GRW-06 R300–R306 (GRW-E300…E306): what the grower app and the partner area need. */
class GrowerAppSupportIT extends GrowerApiTestBase {

    static final String G1 = Long.toString(SeedGrowers.G1.growerId());
    static final String G2 = Long.toString(SeedGrowers.G2.growerId());
    static final String G3 = Long.toString(SeedGrowers.G3.growerId());

    @Test
    void GRW_E300_aGrowerFindsTheirOwnGrower() throws Exception {
        as(GROWER_IN1, get("/growers/me")).andExpect(status().isOk()).andExpect(jsonPath("$.growerId").value(G1));
    }

    static Stream<Arguments> noOwnGrower() {
        return Stream.of(argumentSet("GRW-E301 a grower user who has not registered yet", newGrowerUser()),
                argumentSet("GRW-E301 a care agent", CARE_IN), argumentSet("GRW-E301 a retailer", RETAILER_IN));
    }

    @ParameterizedTest
    @MethodSource("noOwnGrower")
    void othersHaveNoOwnGrower(GcPrincipal actor) throws Exception {
        problem(as(actor, get("/growers/me")), 404, "NOT_FOUND");
    }

    static Stream<Arguments> notices() {
        return Stream.of(argumentSet("GRW-E302 India, Marathi", "IN", "mr", "in-2026-11", "GrowerCare सेवा"),
                argumentSet("GRW-E302 Brazil, Portuguese", "BR", "pt", "br-2026-11", "Serviço GrowerCare"));
    }

    @ParameterizedTest
    @MethodSource("notices")
    void theCurrentNoticesComeWithTheirText(String country, String language, String version, String serviceTitle) throws Exception {
        as(GROWER_IN1, get("/consent-notices?country={c}&language={l}", country, language)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[*].version", everyItem(org.hamcrest.Matchers.is(version))))
                .andExpect(jsonPath("$.items[?(@.purpose == 'SERVICE')].title").value(serviceTitle + " (" + version + ")"))
                .andExpect(jsonPath("$.items[?(@.purpose == 'SERVICE')].body", hasItem(startsWith("PLACEHOLDER."))));
    }

    static Stream<Arguments> noNotices() {
        return Stream.of(argumentSet("GRW-E303 a language without notices", "IN", "ta"),
                argumentSet("GRW-E303 an unknown country", "XX", "en"));
    }

    @ParameterizedTest
    @MethodSource("noNotices")
    void missingNoticesAre404(String country, String language) throws Exception {
        problem(as(GROWER_IN1, get("/consent-notices?country={c}&language={l}", country, language)), 404, "NOT_FOUND");
    }

    /** Every grower id on every page (other rows add newer growers, so the seed growers can be on later pages). */
    java.util.List<String> allPartnerGrowers(GcPrincipal actor) throws Exception {
        var ids = new java.util.ArrayList<String>();
        String cursor = null;
        do {
            var json = as(actor, cursor == null ? get("/growers?partner=mine") : get("/growers?partner=mine&cursor={c}", cursor))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            java.util.List<String> page = JsonPath.read(json, "$.items[*].growerId");
            ids.addAll(page);
            java.util.Map<String, Object> body = JsonPath.read(json, "$");
            cursor = (String) body.get("nextCursor");                  // null or absent on the last page
        } while (cursor != null);
        return ids;
    }

    @Test
    void GRW_E304_eachPartnerSeesItsOwnGrowers() throws Exception {
        org.assertj.core.api.Assertions.assertThat(allPartnerGrowers(RETAILER_IN)).contains(G1).doesNotContain(G2, G3);
        org.assertj.core.api.Assertions.assertThat(allPartnerGrowers(RETAILER2_IN)).contains(G2, G3).doesNotContain(G1)
                .doesNotHaveDuplicates();
    }

    static Stream<Arguments> notPartners() {
        return Stream.of(argumentSet("GRW-E305 a care agent", CARE_IN), argumentSet("GRW-E305 a grower", GROWER_IN1));
    }

    @ParameterizedTest
    @MethodSource("notPartners")
    void onlyPartnersListTheirGrowers(GcPrincipal actor) throws Exception {
        problem(as(actor, get("/growers?partner=mine")), 403, "PERMISSION_DENIED");
    }

    @Test
    void GRW_E306_aPreferredPartnerChangeMovesTheGrowerBetweenLists() throws Exception {
        var growerId = registerGrower(CARE_IN, with(assisted(), "preferredPartnerOrgId", "501"));
        org.assertj.core.api.Assertions.assertThat(allPartnerGrowers(RETAILER_IN)).contains(growerId);
        patch(CARE_IN, growerId, "\"0\"", "{\"preferredPartnerOrgId\": \"502\"}").andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(allPartnerGrowers(RETAILER_IN)).doesNotContain(growerId);
        org.assertj.core.api.Assertions.assertThat(allPartnerGrowers(RETAILER2_IN)).contains(growerId);
    }

    @Test
    void GRW_E304_pagesFollowTheCursorNewestFirst() throws Exception {
        for (int i = 0; i < 21; i++) {
            registerGrower(RETAILER_IN, assisted());                     // onboarded via 501, newest first in the list
        }
        var first = as(RETAILER_IN, get("/growers?partner=mine")).andExpect(jsonPath("$.items.length()").value(20))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.nextCursor");
        java.util.List<String> firstIds = JsonPath.read(first, "$.items[*].growerId");
        as(RETAILER_IN, get("/growers?partner=mine&cursor={c}", cursor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].growerId", not(hasItems(firstIds.toArray(String[]::new)))));
        problem(as(RETAILER_IN, get("/growers?partner=mine&cursor=%21%21")), 400, "VALIDATION_FAILED");
        problem(as(RETAILER_IN, get("/growers?partner=502")), 400, "VALIDATION_FAILED");
    }
}
