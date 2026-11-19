package com.growercare.grower.adapters.in.web;

import com.growercare.common.error.PermissionDeniedException;
import com.growercare.security.web.ServiceAuthenticationToken;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SPEC-GRW-07 D1 (least privilege): gc-security admits every GC_INTERNAL_CLIENTS service token on /internal/**; each
 * internal route group then has its own allow-list, so a sender (notification-service) reads phones and nothing else,
 * and the replica readers (case-service) never read phones.
 */
@Component
class InternalAccess {

    private final Set<String> replicaClients;
    private final Set<String> contactClients;

    InternalAccess(@Value("${gc.replica-clients}") String replicaClients, @Value("${gc.contact-clients}") String contactClients) {
        this.replicaClients = parse(replicaClients);
        this.contactClients = parse(contactClients);
    }

    /** SPEC-GRW-01 R42 / GRW-02 R213: the replica views (grower, farm). */
    String requireReplicaReader() {
        return require(replicaClients, "replica");
    }

    /** SPEC-GRW-07 R320: the decrypted contact. */
    String requireContactReader() {
        return require(contactClients, "contact");
    }

    private static String require(Set<String> allowed, String what) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String client = auth instanceof ServiceAuthenticationToken service ? String.valueOf(service.getPrincipal()) : null;
        if (client == null || !allowed.contains(client)) {
            throw new PermissionDeniedException(what);
        }
        return client;
    }

    private static Set<String> parse(String clients) {
        return Arrays.stream(clients.split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
}
