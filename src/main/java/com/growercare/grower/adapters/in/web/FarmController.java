package com.growercare.grower.adapters.in.web;

import static com.growercare.grower.adapters.in.web.GrowerDtos.etag;
import static com.growercare.grower.adapters.in.web.GrowerDtos.growerIdOrNotFound;

import com.growercare.common.error.BusinessRuleException;
import com.growercare.common.error.ValidationException;
import com.growercare.grower.application.FarmCommands;
import com.growercare.grower.application.FarmCommands.FarmChanges;
import com.growercare.grower.application.FarmCommands.NewFarm;
import com.growercare.grower.application.FarmQueries;
import com.growercare.grower.domain.FarmRecord;
import com.growercare.grower.domain.FarmRules;
import com.growercare.grower.domain.GrowerAccess;
import com.growercare.security.PiiClearance;
import com.growercare.security.web.CurrentPrincipal;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** SPEC-GRW-02 §3: farms. Translation only; rules live in {@link FarmCommands} / {@link FarmRules}. */
@RestController
class FarmController {

    record LocationView(BigDecimal latitude, BigDecimal longitude) { }

    record FarmView(String farmId, String growerId, String name, BigDecimal areaHa, List<String> crops, LocationView location,
                    String locationPrecision, String status, int version, Instant createdAt, Instant updatedAt) {

        /** R207 / D3: exact point only with FULL clearance; the service view (R213) is always exact. */
        static FarmView of(FarmRecord f, boolean exact) {
            var point = f.location() == null ? null : exact ? f.location() : FarmRules.coarse(f.location());
            var location = point == null ? null : new LocationView(point.latitude(), point.longitude());
            return new FarmView(Long.toString(f.farmId()), Long.toString(f.growerId()), f.name(), f.areaHa(), f.crops(), location,
                    point == null ? null : exact ? "EXACT" : "DISTRICT", f.status(), f.version(), f.createdAt(), f.updatedAt());
        }
    }

    record FarmPage(List<FarmView> items) { }

    private final FarmCommands commands;
    private final FarmQueries queries;
    private final Clock clock;

    FarmController(FarmCommands commands, FarmQueries queries, Clock clock) {
        this.commands = commands;
        this.queries = queries;
        this.clock = clock;
    }

    @PostMapping("/growers/{growerId}/farms")
    ResponseEntity<FarmView> create(@PathVariable String growerId, @RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @RequestBody JsonNode body) {
        var id = growerIdOrNotFound(growerId);
        var result = commands.create(id, newFarm(body), GrowerDtos.uuid(idempotencyKey, "Idempotency-Key"));
        var view = view(result.farm());
        if (result.replayed()) {
            return ResponseEntity.ok().eTag(etag(view.version())).header("Idempotent-Replayed", "true").body(view);
        }
        return ResponseEntity.created(URI.create("/growers/" + growerId + "/farms/" + view.farmId())).eTag(etag(view.version())).body(view);
    }

    @GetMapping("/growers/{growerId}/farms")
    FarmPage list(@PathVariable String growerId, @RequestParam(defaultValue = "false") boolean includeArchived) {
        return new FarmPage(queries.list(growerIdOrNotFound(growerId), includeArchived).stream().map(this::view).toList());
    }

    @GetMapping("/growers/{growerId}/farms/{farmId}")
    ResponseEntity<FarmView> get(@PathVariable String growerId, @PathVariable String farmId) {
        var view = view(queries.get(growerIdOrNotFound(growerId), growerIdOrNotFound(farmId)));
        return ResponseEntity.ok().eTag(etag(view.version())).body(view);
    }

    @PatchMapping("/growers/{growerId}/farms/{farmId}")
    ResponseEntity<FarmView> update(@PathVariable String growerId, @PathVariable String farmId,
                                    @RequestHeader("If-Match") String ifMatch, @RequestBody(required = false) JsonNode body) {
        var view = view(commands.update(growerIdOrNotFound(growerId), growerIdOrNotFound(farmId), GrowerDtos.version(ifMatch), changes(body)));
        return ResponseEntity.ok().eTag(etag(view.version())).body(view);
    }

    @PostMapping("/growers/{growerId}/farms/{farmId}/archive")
    ResponseEntity<FarmView> archive(@PathVariable String growerId, @PathVariable String farmId, @RequestHeader("If-Match") String ifMatch) {
        var view = view(commands.archive(growerIdOrNotFound(growerId), growerIdOrNotFound(farmId), GrowerDtos.version(ifMatch)));
        return ResponseEntity.ok().eTag(etag(view.version())).body(view);
    }

    /** R213: the exact service view. */
    @GetMapping("/internal/growers/{growerId}/farms/{farmId}")
    FarmView replica(@PathVariable String growerId, @PathVariable String farmId) {
        return FarmView.of(queries.replica(growerIdOrNotFound(growerId), growerIdOrNotFound(farmId)), true);
    }

    private FarmView view(FarmRecord farm) {
        return FarmView.of(farm, CurrentPrincipal.require().piiClearanceFor(GrowerAccess.READ, clock.instant()) == PiiClearance.FULL);
    }

    // ------------------------------------------------------------------------------------------------ JSON input

    static final Set<String> EDITABLE = Set.of("name", "areaHa", "crops", "location");

    private static NewFarm newFarm(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ValidationException.of("body", "must be a JSON object");
        }
        var location = body.get("location");
        boolean hasLocation = location != null && !location.isNull();
        return new NewFarm(text(body, "name"), decimal(body, "areaHa"), crops(body.get("crops")),
                hasLocation ? coordinate(location, "latitude") : null, hasLocation ? coordinate(location, "longitude") : null, hasLocation);
    }

    /** R208/R209: a merge patch of the editable fields; any other field is 422 naming it. */
    private static FarmChanges changes(JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw ValidationException.of("body", "must be a JSON object with at least one of " + new TreeSet<>(EDITABLE));
        }
        for (var entry : body.properties()) {
            if (!EDITABLE.contains(entry.getKey())) {
                throw new BusinessRuleException("grower.field-immutable", "Field " + entry.getKey() + " cannot be changed here");
            }
        }
        var location = body.get("location");
        boolean inPatch = location != null;
        boolean clear = inPatch && location.isNull();
        return new FarmChanges(body.has("name") ? text(body, "name") : null, body.has("areaHa") ? decimal(body, "areaHa") : null,
                body.has("crops") ? crops(body.get("crops")) : null, inPatch,
                inPatch && !clear ? coordinate(location, "latitude") : null, inPatch && !clear ? coordinate(location, "longitude") : null);
    }

    private static String text(JsonNode body, String field) {
        var node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw ValidationException.of(field, "must be a string");
        }
        return node.asString();
    }

    private static BigDecimal decimal(JsonNode body, String field) {
        var node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw ValidationException.of(field, "must be a number");
        }
        return node.decimalValue();
    }

    private static List<String> crops(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw ValidationException.of("crops", "must be an array of crop codes");
        }
        var crops = new ArrayList<String>();
        for (var item : node) {
            crops.add(item.isString() ? item.asString() : null);
        }
        return crops;
    }

    private static BigDecimal coordinate(JsonNode location, String field) {
        if (!location.isObject()) {
            throw ValidationException.of("location", "must be an object with latitude and longitude");
        }
        var node = location.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw ValidationException.of("location." + field, "must be a number");
        }
        return node.decimalValue();
    }
}
