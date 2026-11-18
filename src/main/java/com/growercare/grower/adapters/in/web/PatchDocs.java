package com.growercare.grower.adapters.in.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * SPEC-GRW-07 D4: the request bodies the controllers read as JSON trees (merge patch needs "absent" vs "null"),
 * described for the OpenAPI contract by their fields. Documentation only; never deserialised.
 */
final class PatchDocs {

    private PatchDocs() { }

    @Schema(name = "Location", description = "A point; both coordinates or neither")
    record Location(@Schema(minimum = "-90", maximum = "90") BigDecimal latitude, @Schema(minimum = "-180", maximum = "180") BigDecimal longitude) { }

    @Schema(name = "FarmCreateRequest", requiredProperties = {"name", "areaHa", "crops"})
    record FarmCreate(@Schema(maxLength = 120) String name, @Schema(minimum = "0.01", maximum = "100000", multipleOf = 0.01, description = "hectares, > 0 with at most 2 decimals (R202)") BigDecimal areaHa,
                      @Schema(description = "1 to 5 crop codes (SPEC-GRW-02 D4)") List<String> crops, Location location) { }

    @Schema(name = "FarmPatch", description = "JSON merge patch: absent = unchanged; location null clears it")
    record FarmPatch(@Schema(maxLength = 120) String name, @Schema(minimum = "0.01", maximum = "100000", multipleOf = 0.01, description = "hectares, > 0 with at most 2 decimals (R202)") BigDecimal areaHa, List<String> crops, Location location) { }

    @Schema(name = "GrowerPatch", description = "JSON merge patch: absent = unchanged; preferredPartnerOrgId null clears it")
    record GrowerPatch(@Schema(maxLength = 120) String displayName, @Schema(maxLength = 8) String preferredLanguage,
                       @Schema(pattern = "^[1-9][0-9]{0,18}$") String preferredPartnerOrgId,
                       @Schema(pattern = "^[1-9][0-9]{0,18}$") String territoryId) { }
}
