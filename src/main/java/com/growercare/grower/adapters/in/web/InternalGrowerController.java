package com.growercare.grower.adapters.in.web;

import com.growercare.grower.adapters.in.web.GrowerDtos.GrowerReplicaView;
import com.growercare.grower.application.GrowerContacts;
import com.growercare.grower.application.GrowerQueries;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service reads; each route group has its own allow-list (InternalAccess). */
@RestController
class InternalGrowerController {

    /** SPEC-GRW-07: one contact read for a sender. */
    record ContactView(String phone, String language, String status) { }

    private final GrowerQueries queries;
    private final GrowerContacts contacts;
    private final InternalAccess access;

    InternalGrowerController(GrowerQueries queries, GrowerContacts contacts, InternalAccess access) {
        this.queries = queries;
        this.contacts = contacts;
        this.access = access;
    }

    /** SPEC-GRW-07 R320–R322: GC_CONTACT_CLIENTS only; never cached. */
    @GetMapping("/internal/growers/{growerId}/contact")
    ResponseEntity<ContactView> contact(@PathVariable String growerId) {
        var client = access.requireContactReader();
        var c = contacts.contact(GrowerDtos.growerIdOrNotFound(growerId), client);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ContactView(c.phone(), c.language(), c.status()));
    }

    /** SPEC-GRW-01 R42: the replica view, GC_REPLICA_CLIENTS only. */
    @GetMapping("/internal/growers/{growerId}")
    GrowerReplicaView replica(@PathVariable String growerId) {
        access.requireReplicaReader();
        return GrowerReplicaView.of(queries.replica(GrowerDtos.growerIdOrNotFound(growerId)));
    }
}
