package com.growercare.grower.domain;

/**
 * A JSON merge patch of the editable grower fields (SPEC-GRW-01 R23, R29), after shape validation. A {@code null}
 * component means "not in the patch", except {@code preferredPartnerOrgId}, where {@link #partnerInPatch} tells
 * "not in the patch" from "set to null" (clear).
 */
public record GrowerChanges(
        String displayName,
        String preferredLanguage,
        Long territoryId,
        boolean partnerInPatch,
        Long preferredPartnerOrgId) {

    public boolean isEmpty() {
        return displayName == null && preferredLanguage == null && territoryId == null && !partnerInPatch;
    }
}
