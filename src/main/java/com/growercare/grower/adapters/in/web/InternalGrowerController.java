package com.growercare.grower.adapters.in.web;

import com.growercare.grower.adapters.in.web.GrowerDtos.GrowerReplicaView;
import com.growercare.grower.application.GrowerQueries;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** SPEC-GRW-01 R42: service-to-service reads (gc-security admits only allow-listed clients on /internal/**). */
@RestController
class InternalGrowerController {

    private final GrowerQueries queries;
    private final com.growercare.grower.application.GrowerContacts contacts;
    private final java.util.Set<String> contactClients;

    InternalGrowerController(GrowerQueries queries, com.growercare.grower.application.GrowerContacts contacts,
                             @org.springframework.beans.factory.annotation.Value("${gc.contact-clients}") String contactClients) {
        this.queries = queries;
        this.contacts = contacts;
        this.contactClients = java.util.Arrays.stream(contactClients.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** One contact read for a sender. */
    record ContactView(String phone, String language, String status) { }

    /**
     * SPEC-GRW-07 R320–R322: on top of the /internal/** rule (GC_INTERNAL_CLIENTS), only GC_CONTACT_CLIENTS may read a
     * phone (case-service may read growers, never phones). No caching anywhere.
     */
    @GetMapping("/internal/growers/{growerId}/contact")
    org.springframework.http.ResponseEntity<ContactView> contact(@PathVariable String growerId) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String client = auth instanceof com.growercare.security.web.ServiceAuthenticationToken service ? String.valueOf(service.getPrincipal()) : null;
        if (client == null || !contactClients.contains(client)) {
            throw new com.growercare.common.error.PermissionDeniedException("contact");
        }
        var c = contacts.contact(GrowerDtos.growerIdOrNotFound(growerId), client);
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ContactView(c.phone(), c.language(), c.status()));
    }

    @GetMapping("/internal/growers/{growerId}")
    GrowerReplicaView replica(@PathVariable String growerId) {
        return GrowerReplicaView.of(queries.replica(GrowerDtos.growerIdOrNotFound(growerId)));
    }
}
