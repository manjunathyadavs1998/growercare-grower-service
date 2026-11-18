package com.growercare.grower.adapters.in.web;

import static com.growercare.grower.adapters.in.web.GrowerDtos.etag;
import static com.growercare.grower.adapters.in.web.GrowerDtos.growerIdOrNotFound;
import static com.growercare.grower.adapters.in.web.GrowerDtos.optionalId;

import com.growercare.grower.adapters.in.web.GrowerDtos.GrowerPage;
import com.growercare.grower.adapters.in.web.GrowerDtos.GrowerView;
import com.growercare.grower.adapters.in.web.GrowerDtos.RegisterRequest;
import com.growercare.grower.adapters.in.web.GrowerDtos.SearchRequest;
import com.growercare.grower.application.GrowerCommands;
import com.growercare.grower.application.GrowerQueries;
import com.growercare.grower.domain.GrowerAccess;
import com.growercare.grower.domain.GrowerRecord;
import com.growercare.grower.domain.NewGrowerCommand;
import com.growercare.security.web.CurrentPrincipal;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/** SPEC-GRW-01 §3. Translation only: HTTP ⇄ commands/queries; rules and authorization live in the application layer. */
@RestController
class GrowerController {

    private final GrowerCommands commands;
    private final GrowerQueries queries;
    private final Clock clock;

    GrowerController(GrowerCommands commands, GrowerQueries queries, Clock clock) {
        this.commands = commands;
        this.queries = queries;
        this.clock = clock;
    }

    @PostMapping("/growers")
    ResponseEntity<GrowerView> register(@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody RegisterRequest body) {
        var result = commands.register(new NewGrowerCommand(GrowerDtos.id(body.territoryId(), "territoryId"), body.displayName(), body.phone(),
                body.preferredLanguage(), optionalId(body.preferredPartnerOrgId(), "preferredPartnerOrgId"),
                GrowerDtos.uuid(idempotencyKey, "Idempotency-Key")));
        var view = view(result.record());
        if (result.replayed()) {
            return ResponseEntity.ok().eTag(etag(view.version())).header("Idempotent-Replayed", "true").body(view);
        }
        return ResponseEntity.created(URI.create("/growers/" + view.growerId())).eTag(etag(view.version())).body(view);
    }

    /** SPEC-GRW-06 R300: the caller's own grower (a literal path, so it wins over {growerId}). */
    @GetMapping("/growers/me")
    ResponseEntity<GrowerView> me() {
        var view = view(queries.me());
        return ResponseEntity.ok().eTag(etag(view.version())).body(view);
    }

    record PartnerGrowerPage(java.util.List<GrowerView> items, String nextCursor) { }

    /** SPEC-GRW-06 R304: the caller's partner org's growers. Only {@code partner=mine}: the org is never a request input. */
    @GetMapping("/growers")
    PartnerGrowerPage partnerGrowers(@org.springframework.web.bind.annotation.RequestParam String partner,
                                     @org.springframework.web.bind.annotation.RequestParam(required = false) String cursor) {
        if (!"mine".equals(partner)) {
            throw com.growercare.common.error.ValidationException.of("partner", "must be mine");
        }
        var page = queries.partnerGrowers(GrowerDtos.partnerCursor(cursor));
        return new PartnerGrowerPage(page.items().stream().map(this::view).toList(), GrowerDtos.partnerCursor(page.next()));
    }

    @GetMapping("/growers/{growerId}")
    ResponseEntity<GrowerView> get(@PathVariable String growerId) {
        var view = view(queries.get(growerIdOrNotFound(growerId)));
        return ResponseEntity.ok().eTag(etag(view.version())).body(view);
    }

    /** R23–R29: JSON merge patch of the editable fields under {@code If-Match}. */
    @PatchMapping("/growers/{growerId}")
    ResponseEntity<GrowerView> update(@PathVariable String growerId, @RequestHeader("If-Match") String ifMatch,
                                      @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/merge-patch+json", schema = @Schema(implementation = PatchDocs.GrowerPatch.class))) @RequestBody(required = false) JsonNode body) {
        var id = growerIdOrNotFound(growerId);
        var view = view(commands.update(id, GrowerDtos.version(ifMatch), GrowerDtos.changes(body)));
        return ResponseEntity.ok().eTag(etag(view.version())).body(view);
    }

    /** R30/R31: a POST so the phone number never appears in a URL or an access log. */
    @PostMapping("/growers/search")
    GrowerPage search(@Valid @RequestBody SearchRequest body) {
        return new GrowerPage(queries.findByPhone(body.phone(), body.countryCode()).stream().map(this::view).toList());
    }

    private GrowerView view(GrowerRecord record) {
        return GrowerView.of(record, CurrentPrincipal.require().piiClearanceFor(GrowerAccess.READ, clock.instant()));
    }
}
