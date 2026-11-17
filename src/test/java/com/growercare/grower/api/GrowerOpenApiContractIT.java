package com.growercare.grower.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.grower.support.GrowerApiTestBase;
import com.growercare.testsupport.OpenApiContract;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** SPEC-PLAT-01 R1/R3 (PLAT-E21/E22) for grower-service; the export is committed to contracts/ (drift fails CI). */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class GrowerOpenApiContractIT extends GrowerApiTestBase {

    JsonNode api() throws Exception {
        return OpenApiContract.parse(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    static Stream<Arguments> operations() {
        return Stream.of(
                argumentSet("PLAT-E21 POST /growers", "/growers", "post"),
                argumentSet("PLAT-E21 GET /growers/{growerId}", "/growers/{growerId}", "get"),
                argumentSet("PLAT-E21 PATCH /growers/{growerId}", "/growers/{growerId}", "patch"),
                argumentSet("PLAT-E21 POST /growers/search", "/growers/search", "post"),
                argumentSet("PLAT-E21 GET /internal/growers/{growerId}", "/internal/growers/{growerId}", "get"),
                argumentSet("PLAT-E21 POST /growers/{growerId}/verifications", "/growers/{growerId}/verifications", "post"),
                argumentSet("PLAT-E21 POST …/verifications/{verificationId}/confirm",
                        "/growers/{growerId}/verifications/{verificationId}/confirm", "post"),
                argumentSet("PLAT-E21 GET /growers/{growerId}/consents", "/growers/{growerId}/consents", "get"),
                argumentSet("PLAT-E21 PUT /growers/{growerId}/consents/{purpose}", "/growers/{growerId}/consents/{purpose}", "put"),
                argumentSet("PLAT-E21 POST /growers/{growerId}/farms", "/growers/{growerId}/farms", "post"),
                argumentSet("PLAT-E21 GET /growers/{growerId}/farms", "/growers/{growerId}/farms", "get"),
                argumentSet("PLAT-E21 GET /growers/{growerId}/farms/{farmId}", "/growers/{growerId}/farms/{farmId}", "get"),
                argumentSet("PLAT-E21 PATCH /growers/{growerId}/farms/{farmId}", "/growers/{growerId}/farms/{farmId}", "patch"),
                argumentSet("PLAT-E21 POST …/farms/{farmId}/archive", "/growers/{growerId}/farms/{farmId}/archive", "post"),
                argumentSet("PLAT-E21 GET /internal/growers/{growerId}/farms/{farmId}", "/internal/growers/{growerId}/farms/{farmId}", "get"));
    }

    @ParameterizedTest
    @MethodSource("operations")
    void documentedOperation(String path, String method) throws Exception {
        assertThat(OpenApiContract.hasOperation(api(), path, method)).as("%s %s documented", method, path).isTrue();
    }

    @Test
    void platE22BearerSchemeAndContractExport() throws Exception {
        var api = api();
        assertThat(api.path("components").path("securitySchemes").path("bearer").path("scheme").asString()).isEqualTo("bearer");
        assertThat(api.path("security").toString()).contains("bearer");
        assertThat(OpenApiContract.export(api, "grower-service")).exists();
    }
}
