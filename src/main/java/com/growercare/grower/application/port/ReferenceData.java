package com.growercare.grower.application.port;

import com.growercare.grower.domain.PhonePlan;
import java.util.Optional;

/** Read-only replicas (schema {@code ref}). */
public interface ReferenceData {

    record Territory(long territoryId, String countryCode, boolean active) { }

    record Partner(long partnerOrgId, String countryCode, String status) {
        public boolean activeIn(String country) {
            return "ACTIVE".equals(status) && countryCode.equals(country);
        }
    }

    /** Territories of active countries only. */
    Optional<Territory> territory(long territoryId);

    /** The phone plan of an active country (D3). */
    Optional<PhonePlan> phonePlan(String countryCode);

    Optional<Partner> partner(long partnerOrgId);

    /** SPEC-GRW-03 D8: the current consent notice version (latest effective, not in the future). */
    Optional<String> currentNotice(String countryCode, String purpose, String language, java.time.Instant now);

    /** SPEC-GRW-02 D4: which of these crop codes exist. */
    java.util.Set<String> knownCrops(java.util.Collection<String> codes);
}
