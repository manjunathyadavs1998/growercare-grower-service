package com.growercare.grower.api;

import static com.growercare.grower.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.grower.support.DevSeedPrincipals.GROWER_IN2;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER_IN;
import static com.growercare.grower.support.DevSeedPrincipals.TM_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.grower.support.GrowerApiTestBase;
import com.growercare.grower.support.GrowerTestDatabase;
import com.growercare.grower.support.SeedGrowers;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-GRW-01 R23–R29 (GRW-E43…E52): updating growers. Each test works on a freshly registered grower. */
class GrowerUpdateIT extends GrowerApiTestBase {

    static final String G1 = Long.toString(SeedGrowers.G1.growerId());

    /** A new assisted grower in Pune, registered by care.in, with preferred partner 501. */
    private String freshGrower() throws Exception {
        return registerGrower(CARE_IN, with(assisted(), "preferredPartnerOrgId", "501"));
    }

    private List<String> eventsOf(String growerId) {
        var shard = GrowerTestDatabase.ROUTER.forId(Long.parseLong(growerId)).orElseThrow();
        return owner.sql("SELECT event_type || ' ' || (payload -> 'data')::text FROM %s.outbox WHERE aggregate_id = ? ORDER BY occurred_at"
                .formatted(shard.schemaName())).param(growerId).query(String.class).list();
    }

    @Test
    void GRW_E43_anUpdateBumpsTheVersionAndNamesOnlyTheChangedFields() throws Exception {
        var id = freshGrower();
        patch(CARE_IN, id, "\"0\"", "{\"preferredLanguage\":\"hi\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.preferredLanguage").value("hi"));
        var events = eventsOf(id);
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).startsWith("grower.updated").contains("\"changedFields\": [\"preferredLanguage\"]", "\"version\": 1")
                .doesNotContain("Anil", "phone");
    }

    @Test
    void GRW_E44_ifMatchIsRequiredAndMustBeCurrent() throws Exception {
        var id = freshGrower();
        problem(patch(CARE_IN, id, null, "{\"preferredLanguage\":\"hi\"}"), 428, "PRECONDITION_REQUIRED");
        problem(patch(CARE_IN, id, "\"7\"", "{\"preferredLanguage\":\"hi\"}"), 412, "STALE_VERSION")
                .andExpect(jsonPath("$.expectedVersion").value(7));
    }

    static Stream<Arguments> immutable() {
        return Stream.of(
                argumentSet("GRW-E45 phone (D5)", "phone", "\"+919000000001\""),
                argumentSet("GRW-E45 status", "status", "\"ACTIVE\""),
                argumentSet("GRW-E45 countryCode", "countryCode", "\"BR\""),
                argumentSet("GRW-E45 userId", "userId", "\"950\""),
                argumentSet("GRW-E45 unknown field", "foo", "1"));
    }

    @ParameterizedTest
    @MethodSource("immutable")
    void immutableAndUnknownFieldsAreRejectedByName(String field, String value) throws Exception {
        var id = freshGrower();
        problem(patch(CARE_IN, id, "\"0\"", "{\"" + field + "\":" + value + "}"), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.field-immutable"))
                .andExpect(jsonPath("$.detail", containsString(field)));
    }

    @Test
    void GRW_E46_aCountryMoveIsNotAnUpdate() throws Exception {
        problem(patch(CARE_IN, freshGrower(), "\"0\"", "{\"territoryId\":\"2002\"}"), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.country-change"));
    }

    @Test
    void GRW_E47_theTargetTerritoryMustBeInScope() throws Exception {
        problem(patch(TM_IN, freshGrower(), "\"0\"", "{\"territoryId\":\"1005\"}"), 404, "NOT_FOUND");
    }

    @Test
    void GRW_E48_aGrowerMovesTheirOwnFarmWithinTheCountry() throws Exception {
        var user = newGrowerUser();
        var id = registerGrower(user, assisted());
        patch(user, id, "\"0\"", "{\"territoryId\":\"1003\",\"displayName\":\"Anil S. Shinde\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.territoryId").value("1003"))
                .andExpect(jsonPath("$.displayName").value("Anil S. Shinde"));
        assertThat(eventsOf(id).get(1)).contains("\"changedFields\": [\"displayName\", \"territoryId\"]");
    }

    @Test
    void GRW_E49_anotherGrowerCannotEvenSeeIt() throws Exception {
        problem(patch(GROWER_IN2, G1, "\"0\"", "{\"preferredLanguage\":\"hi\"}"), 404, "NOT_FOUND");
    }

    @Test
    void GRW_E49a_retailersOnboardButDoNotEdit() throws Exception {
        var id = registerGrower(RETAILER_IN, assisted());
        problem(patch(RETAILER_IN, id, "\"0\"", "{\"preferredLanguage\":\"hi\"}"), 403, "PERMISSION_DENIED");
    }

    @Test
    void GRW_E49b_thePreferredPartnerMustBeActiveInTheCountry() throws Exception {
        problem(patch(CARE_IN, freshGrower(), "\"0\"", "{\"preferredPartnerOrgId\":\"504\"}"), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.partner-invalid"));
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                argumentSet("GRW-E50 empty patch", "{}", "body"),
                argumentSet("GRW-E50 not an object", "[]", "body"),
                argumentSet("GRW-E50 territoryId as a number", "{\"territoryId\":12}", "territoryId"),
                argumentSet("GRW-E50 displayName null", "{\"displayName\":null}", "displayName"),
                argumentSet("GRW-E50 unsupported language", "{\"preferredLanguage\":\"xx\"}", "preferredLanguage"),
                argumentSet("GRW-E50 control character in the name", "{\"displayName\":\"Anil\\u0000\"}", "displayName"));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void malformedPatchesAre400(String json, String field) throws Exception {
        problem(patch(CARE_IN, freshGrower(), "\"0\"", json), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));
    }

    @Test
    void GRW_E51_aPatchThatChangesNothingKeepsTheVersionAndPublishesNothing() throws Exception {
        var id = freshGrower();
        patch(CARE_IN, id, "\"0\"", "{\"preferredLanguage\":\"mr\",\"territoryId\":\"1002\"}")
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""));
        assertThat(eventsOf(id)).hasSize(1);   // only grower.registered
    }

    @Test
    void GRW_E52_nullClearsThePreferredPartner() throws Exception {
        var id = freshGrower();
        patch(CARE_IN, id, "\"0\"", "{\"preferredPartnerOrgId\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredPartnerOrgId").doesNotExist());
        assertThat(eventsOf(id).get(1)).contains("\"changedFields\": [\"preferredPartnerOrgId\"]");
    }
}
