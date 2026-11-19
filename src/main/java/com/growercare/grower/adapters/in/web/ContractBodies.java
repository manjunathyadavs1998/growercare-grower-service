package com.growercare.grower.adapters.in.web;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import java.util.function.Function;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SPEC-GRW-07 D4: the controllers read these bodies as JSON trees (a merge patch must tell "absent" from "null"), so
 * springdoc would document Jackson's JsonNode bean. This customizer describes them by their fields instead
 * ({@link PatchDocs}), in one place rather than as annotations on every controller signature.
 */
@Configuration
class ContractBodies {

    private static final String JSON = "application/json";
    private static final String MERGE_PATCH = "application/merge-patch+json";

    @Bean
    OpenApiCustomizer jsonTreeBodies() {
        return api -> {
            var converters = ModelConverters.getInstance(true);
            for (var type : new Class<?>[] {PatchDocs.FarmCreate.class, PatchDocs.FarmPatch.class, PatchDocs.GrowerPatch.class}) {
                converters.readAll(type).forEach((name, schema) -> api.getComponents().addSchemas(name, schema));
            }
            body(api, "/growers/{growerId}/farms", p -> p.getPost(), JSON, "FarmCreateRequest");
            body(api, "/growers/{growerId}/farms/{farmId}", p -> p.getPatch(), MERGE_PATCH, "FarmPatch");
            body(api, "/growers/{growerId}", p -> p.getPatch(), MERGE_PATCH, "GrowerPatch");
            api.getComponents().getSchemas().keySet().removeIf(name -> name.startsWith("JsonNode"));
        };
    }

    private static void body(OpenAPI api, String path, Function<PathItem, Operation> operation,
                             String mediaType, String schema) {
        var item = api.getPaths() == null ? null : api.getPaths().get(path);
        var op = item == null ? null : operation.apply(item);
        if (op == null) {
            throw new IllegalStateException("contract: no operation for " + path);
        }
        var required = op.getRequestBody() != null && Boolean.TRUE.equals(op.getRequestBody().getRequired());
        op.setRequestBody(new RequestBody().required(required).content(new Content().addMediaType(mediaType,
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + schema)))));
    }
}
