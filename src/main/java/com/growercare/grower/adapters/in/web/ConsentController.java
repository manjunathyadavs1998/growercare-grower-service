package com.growercare.grower.adapters.in.web;

import static com.growercare.grower.adapters.in.web.GrowerDtos.etag;
import static com.growercare.grower.adapters.in.web.GrowerDtos.growerIdOrNotFound;

import com.growercare.grower.adapters.in.web.GrowerDtos.GrowerView;
import com.growercare.grower.application.ConsentCommands;
import com.growercare.grower.application.ConsentCommands.ConsentChoice;
import com.growercare.grower.application.ConsentCommands.Confirmation;
import com.growercare.grower.application.ConsentQueries;
import com.growercare.grower.application.port.ConsentRepository.Consent;
import com.growercare.grower.domain.GrowerAccess;
import com.growercare.security.web.CurrentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** SPEC-GRW-03 §3: phone verification and consent. Translation only; rules live in {@link ConsentCommands}. */
@RestController
class ConsentController {

    record StartRequest(String purpose) { }

    record VerificationView(String verificationId, String purpose, Instant expiresAt, String phoneLast4) { }

    record ConsentChoiceRequest(String purpose, Boolean granted) { }

    record ConfirmRequest(String code, String noticeVersion, String language, List<ConsentChoiceRequest> consents) {
        /** The code is a secret: never printed. */
        @Override
        public String toString() {
            return "ConfirmRequest[noticeVersion=%s, language=%s]".formatted(noticeVersion, language);
        }
    }

    record SetConsentRequest(Boolean granted, String noticeVersion, String language) { }

    record ConsentView(String purpose, boolean granted, String noticeVersion, String language, String channel,
                       String recordedByUserId, Instant recordedAt) {
        static ConsentView of(Consent c) {
            return new ConsentView(c.purpose().name(), c.granted(), c.noticeVersion(), c.language(), c.channel().name(),
                    Long.toString(c.recordedByUserId()), c.recordedAt());
        }
    }

    record ConsentPage(List<ConsentView> items) { }

    private final ConsentCommands commands;
    private final ConsentQueries queries;
    private final Clock clock;

    ConsentController(ConsentCommands commands, ConsentQueries queries, Clock clock) {
        this.commands = commands;
        this.queries = queries;
        this.clock = clock;
    }

    @PostMapping("/growers/{growerId}/verifications")
    ResponseEntity<VerificationView> start(@PathVariable String growerId, @RequestBody StartRequest body) {
        var started = commands.start(growerIdOrNotFound(growerId), body.purpose());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new VerificationView(started.verificationId().toString(),
                started.purpose(), started.expiresAt(), started.phoneLast4()));
    }

    @PostMapping("/growers/{growerId}/verifications/{verificationId}/confirm")
    ResponseEntity<GrowerView> confirm(@PathVariable String growerId, @PathVariable String verificationId,
                                       @RequestBody ConfirmRequest body) {
        var choices = body.consents() == null ? null
                : body.consents().stream().map(c -> c == null ? null : new ConsentChoice(c.purpose(), c.granted())).toList();
        var record = commands.confirm(growerIdOrNotFound(growerId), uuidOrNull(verificationId),
                new Confirmation(body.code(), body.noticeVersion(), body.language(), choices));
        var view = GrowerView.of(record, CurrentPrincipal.require().piiClearanceFor(GrowerAccess.READ, clock.instant()));
        return ResponseEntity.ok().eTag(etag(view.version())).body(view);
    }

    record NoticeView(String purpose, String version, String title, String body) { }

    record NoticePage(List<NoticeView> items) { }

    /** SPEC-GRW-06 R302: what the grower is asked to agree to, in their language. */
    @GetMapping("/consent-notices")
    NoticePage notices(@org.springframework.web.bind.annotation.RequestParam String country,
                       @org.springframework.web.bind.annotation.RequestParam String language) {
        return new NoticePage(queries.notices(country, language).stream()
                .map(n -> new NoticeView(n.purpose(), n.version(), n.title(), n.body())).toList());
    }

    @GetMapping("/growers/{growerId}/consents")
    ConsentPage consents(@PathVariable String growerId) {
        return new ConsentPage(queries.current(growerIdOrNotFound(growerId)).stream().map(ConsentView::of).toList());
    }

    @PutMapping("/growers/{growerId}/consents/{purpose}")
    ConsentView setConsent(@PathVariable String growerId, @PathVariable String purpose, @RequestBody SetConsentRequest body) {
        return ConsentView.of(commands.setConsent(growerIdOrNotFound(growerId), purpose, body.granted(), body.noticeVersion(),
                body.language()));
    }

    /** R110: a malformed verification id is as unknown as a missing one. */
    private static UUID uuidOrNull(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
