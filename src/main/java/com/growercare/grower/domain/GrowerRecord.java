package com.growercare.grower.domain;

import java.time.Instant;

/**
 * A stored grower without phone material: only {@code phoneLast4} ever leaves the persistence layer (SPEC-GRW-01 D3).
 */
public record GrowerRecord(
        long growerId,
        String countryCode,
        long territoryId,
        Long userId,
        String displayName,
        String phoneLast4,
        String preferredLanguage,
        Long preferredPartnerOrgId,
        String status,
        String onboardingChannel,
        Long onboardedByUserId,
        Long onboardedViaOrgId,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    /** What access decisions look at (R1–R3). */
    public GrowerAccess.Attributes access() {
        return new GrowerAccess.Attributes(countryCode, territoryId, userId, preferredPartnerOrgId, onboardedViaOrgId);
    }
}
