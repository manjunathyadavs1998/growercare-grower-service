package com.growercare.grower.api;

import static com.growercare.grower.support.DevSeedPrincipals.ADMIN_IN;
import static com.growercare.grower.support.DevSeedPrincipals.AGRO_IN;
import static com.growercare.grower.support.DevSeedPrincipals.CARE_BR;
import static com.growercare.grower.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.grower.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER2_IN;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER_IN;
import static com.growercare.grower.support.DevSeedPrincipals.TM_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.grower.support.GrowerApiTestBase;
import com.growercare.grower.support.GrowerTestDatabase;
import com.growercare.grower.support.SeedGrowers;
import com.growercare.security.GcPrincipal;
import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** SPEC-GRW-02 R200–R212 (GRW-E200…E212): farms. */
class FarmApiIT extends GrowerApiTestBase {

    static final String G1 = Long.toString(SeedGrowers.G1.growerId());

    static Map<String, Object> farm(String name) {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("areaHa", 1.5);
        body.put("crops", List.of("GRAPE", "ONION"));
        body.put("location", Map.of("latitude", 20.011, "longitude", 73.79));
        return body;
    }

    ResultActions addFarm(GcPrincipal actor, String growerId, Map<String, Object> body, UUID key) throws Exception {
        return as(actor, post("/growers/{id}/farms", growerId).header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    String created(GcPrincipal actor, String growerId, Map<String, Object> body) throws Exception {
        var json = addFarm(actor, growerId, body, UUID.randomUUID()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.farmId");
    }

    ResultActions patchFarm(GcPrincipal actor, String growerId, String farmId, String ifMatch, String json) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/growers/{id}/farms/{f}", growerId, farmId)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return as(actor, request);
    }

    ResultActions archive(GcPrincipal actor, String growerId, String farmId, String ifMatch) throws Exception {
        return as(actor, post("/growers/{id}/farms/{f}/archive", growerId, farmId).header("If-Match", ifMatch));
    }

    List<String> farmEvents(String growerId, String farmId) {
        var schema = GrowerTestDatabase.ROUTER.forId(Long.parseLong(growerId)).orElseThrow().schemaName();
        return owner.sql("SELECT event_type || ' ' || payload::text FROM %s.outbox WHERE aggregate_type = 'farm' AND aggregate_id = ? ORDER BY occurred_at"
                .formatted(schema)).param(farmId).query(String.class).list();
    }

    /** A fresh grower registered by care.in (PENDING_CONSENT; farms are allowed, R204). */
    String freshGrower() throws Exception {
        return registerGrower(CARE_IN, assisted());
    }

    @Test
    void GRW_E200_theGrowerAddsAFarm() throws Exception {
        var json = addFarm(GROWER_IN1, G1, farm("Nashik plot 2"), UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.crops[1]").value("ONION"))
                .andExpect(jsonPath("$.location.latitude").value(20.011))
                .andExpect(jsonPath("$.locationPrecision").value("EXACT"))
                .andReturn().getResponse().getContentAsString();
        String farmId = JsonPath.read(json, "$.farmId");
        assertThat(farmEvents(G1, farmId)).singleElement().satisfies(e -> assertThat(e)
                .startsWith("grower.farm.created").contains("\"farmId\": \"" + farmId + "\"", "\"growerId\": \"" + G1 + "\"", "\"version\": 0")
                .doesNotContain("20.011", "Nashik"));
    }

    static Stream<Arguments> staffWriters() {
        return Stream.of(argumentSet("GRW-E201 agro.in (Pune)", AGRO_IN), argumentSet("GRW-E201 tm.in", TM_IN),
                argumentSet("GRW-E201 care.in", CARE_IN));
    }

    @ParameterizedTest
    @MethodSource("staffWriters")
    void staffInScopeAddFarms(GcPrincipal actor) throws Exception {
        addFarm(actor, G1, farm("Staff plot"), UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    void GRW_E202_aReplayReturnsTheFarmAndAnotherBodyConflicts() throws Exception {
        var grower = freshGrower();
        var key = UUID.randomUUID();
        var json = addFarm(CARE_IN, grower, farm("Replay plot"), key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        addFarm(CARE_IN, grower, farm("Replay plot"), key).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.farmId").value((String) JsonPath.read(json, "$.farmId")));
        problem(addFarm(CARE_IN, grower, farm("Other plot"), key), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                argumentSet("GRW-E203 blank name", Map.of("name", ""), "name"),
                argumentSet("GRW-E203 area 0", Map.of("areaHa", 0), "areaHa"),
                argumentSet("GRW-E203 no crops", Map.of("crops", List.of()), "crops"),
                argumentSet("GRW-E203 duplicate crop", Map.of("crops", List.of("GRAPE", "GRAPE")), "crops"),
                argumentSet("GRW-E203 unknown crop", Map.of("crops", List.of("FOO")), "crops[0]"),
                argumentSet("GRW-E203 six crops", Map.of("crops", List.of("GRAPE", "ONION", "SOYBEAN", "RAGI", "PADDY", "COTTON")), "crops"),
                argumentSet("GRW-E203 latitude 91", Map.of("location", Map.of("latitude", 91, "longitude", 73.8)), "location.latitude"),
                argumentSet("GRW-E203 only longitude", Map.of("location", Map.of("longitude", 73.8)), "location"));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void invalidFarmsAre400NamingTheField(Map<String, Object> change, String field) throws Exception {
        var body = farm("Invalid plot");
        body.putAll(change);
        problem(addFarm(GROWER_IN1, G1, body, UUID.randomUUID()), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));
    }

    @Test
    void GRW_E204_the51stActiveFarmIsRefused() throws Exception {
        var grower = freshGrower();
        var schema = GrowerTestDatabase.ROUTER.forId(Long.parseLong(grower)).orElseThrow().schemaName();
        owner.sql("INSERT INTO %s.farm (grower_id, name, area_ha, crops) SELECT ?, 'Bulk ' || i, 1, ARRAY['PADDY']::varchar(16)[] FROM generate_series(1, 50) i"
                .formatted(schema)).param(Long.parseLong(grower)).update();
        problem(addFarm(CARE_IN, grower, farm("One too many"), UUID.randomUUID()), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.farm-limit"));
    }

    @Test
    void GRW_E205_anInactiveGrowerGetsNoFarmsButAPendingOneDoes() throws Exception {
        var pending = freshGrower();
        addFarm(CARE_IN, pending, farm("Pending plot"), UUID.randomUUID()).andExpect(status().isCreated());
        var inactive = freshGrower();
        owner.sql("UPDATE %s.grower SET status = 'INACTIVE' WHERE grower_id = ?"
                .formatted(GrowerTestDatabase.ROUTER.forId(Long.parseLong(inactive)).orElseThrow().schemaName())).param(Long.parseLong(inactive)).update();
        problem(addFarm(CARE_IN, inactive, farm("Inactive plot"), UUID.randomUUID()), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.not-active"));
    }

    static Stream<Arguments> listers() {
        return Stream.of(argumentSet("GRW-E206 the grower", GROWER_IN1, 200), argumentSet("GRW-E206 retailer.in (partner 501)", RETAILER_IN, 200),
                argumentSet("GRW-E206 retailer2.in", RETAILER2_IN, 404));
    }

    @ParameterizedTest
    @MethodSource("listers")
    void farmsAreListedInTheGrowersScope(GcPrincipal actor, int expected) throws Exception {
        var result = as(actor, get("/growers/{id}/farms", G1)).andExpect(status().is(expected));
        if (expected == 200) {
            result.andExpect(jsonPath("$.items[*].farmId", hasItem("80011")));
        }
    }

    @Test
    void GRW_E207_aFarmIsOnlyReachableThroughItsGrower() throws Exception {
        problem(as(GROWER_IN1, get("/growers/{id}/farms/{f}", G1, "80012")), 404, "NOT_FOUND");
    }

    static Stream<Arguments> readers() {
        return Stream.of(argumentSet("GRW-E208 agro.in (FULL) sees the exact point", AGRO_IN, 18.52043, 73.856743, "EXACT"),
                argumentSet("GRW-E208 care.in (MASKED) sees the district", CARE_IN, 18.5, 73.9, "DISTRICT"));
    }

    @ParameterizedTest
    @MethodSource("readers")
    void locationPrecisionFollowsClearance(GcPrincipal actor, double lat, double lon, String precision) throws Exception {
        as(actor, get("/growers/{id}/farms/{f}", G1, "80011")).andExpect(status().isOk())
                .andExpect(jsonPath("$.location.latitude").value(lat))
                .andExpect(jsonPath("$.location.longitude").value(lon))
                .andExpect(jsonPath("$.locationPrecision").value(precision));
    }

    @Test
    void GRW_E209_aChangeBumpsTheVersionAndTheSameValueAgainDoesNot() throws Exception {
        var grower = freshGrower();
        var farmId = created(CARE_IN, grower, farm("Patch plot"));
        patchFarm(CARE_IN, grower, farmId, "\"0\"", "{\"areaHa\": 3.0}").andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        patchFarm(CARE_IN, grower, farmId, "\"1\"", "{\"areaHa\": 3}").andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        assertThat(farmEvents(grower, farmId)).hasSize(2).last().satisfies(e -> assertThat(e).startsWith("grower.farm.updated"));
    }

    /** Each row patches its own fresh farm (version 0), so rows never depend on each other. */
    static Stream<Arguments> refusedPatches() {
        return Stream.of(
                argumentSet("GRW-E210 stale If-Match", "\"7\"", "{\"name\": \"Stale\"}", 412, "STALE_VERSION", null),
                argumentSet("GRW-E210 no If-Match", null, "{\"name\": \"No header\"}", 428, "PRECONDITION_REQUIRED", null),
                argumentSet("GRW-E210 immutable field", "\"0\"", "{\"growerId\": \"1\"}", 422, "BUSINESS_RULE_VIOLATION", null),
                argumentSet("GRW-E214 empty location object", "\"0\"", "{\"location\": {}}", 400, "VALIDATION_FAILED", "location"),
                argumentSet("GRW-E214 latitude only", "\"0\"", "{\"location\": {\"latitude\": 18.5}}", 400, "VALIDATION_FAILED", "location"));
    }

    @ParameterizedTest
    @MethodSource("refusedPatches")
    void refusedPatchesChangeNothing(String ifMatch, String json, int expected, String code, String field) throws Exception {
        var grower = freshGrower();
        var farmId = created(CARE_IN, grower, farm("Refused patch plot"));
        var result = problem(patchFarm(CARE_IN, grower, farmId, ifMatch, json), expected, code);
        if (field != null) {
            result.andExpect(jsonPath("$.errors[0].field").value(field));
        }
        as(CARE_IN, get("/growers/{id}/farms/{f}", grower, farmId)).andExpect(header().string("ETag", "\"0\""));
    }

    @Test
    void GRW_E214_anExplicitNullClearsTheLocation() throws Exception {
        var grower = freshGrower();
        var farmId = created(CARE_IN, grower, farm("Clear plot"));
        patchFarm(CARE_IN, grower, farmId, "\"0\"", "{\"location\": null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.location").doesNotExist()).andExpect(header().string("ETag", "\"1\""));
    }

    @Test
    void GRW_E211_archivingIsIdempotentAndFreezesTheFarm() throws Exception {
        var grower = freshGrower();
        var farmId = created(CARE_IN, grower, farm("Archive plot"));
        archive(CARE_IN, grower, farmId, "\"0\"").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        archive(CARE_IN, grower, farmId, "\"1\"").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        assertThat(farmEvents(grower, farmId)).hasSize(2).last().satisfies(e -> assertThat(e).startsWith("grower.farm.archived"));
        problem(patchFarm(CARE_IN, grower, farmId, "\"1\"", "{\"name\": \"Too late\"}"), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.farm-archived"));
        as(CARE_IN, get("/growers/{id}/farms", grower)).andExpect(jsonPath("$.items.length()").value(0));
        as(CARE_IN, get("/growers/{id}/farms?includeArchived=true", grower)).andExpect(jsonPath("$.items.length()").value(1));
    }

    static Stream<Arguments> refusedWriters() {
        return Stream.of(argumentSet("GRW-E212 retailer.in (no grower:update)", RETAILER_IN, 403, "PERMISSION_DENIED"),
                argumentSet("GRW-E212 care.br (G1 out of scope)", CARE_BR, 404, "NOT_FOUND"),
                argumentSet("GRW-E212 admin.in (no grower permission)", ADMIN_IN, 403, "PERMISSION_DENIED"));
    }

    @ParameterizedTest
    @MethodSource("refusedWriters")
    void onlyGrowerEditorsInScopeWriteFarms(GcPrincipal actor, int expected, String code) throws Exception {
        problem(addFarm(actor, G1, farm("Refused plot"), UUID.randomUUID()), expected, code);
    }
}
