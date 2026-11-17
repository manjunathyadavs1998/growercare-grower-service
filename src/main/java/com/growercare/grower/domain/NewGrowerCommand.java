package com.growercare.grower.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A registration request (SPEC-GRW-01 R10/R11) after HTTP shape validation. {@code phone} is the raw text the
 * client sent; it is normalised against the territory's country (D3, R15). {@link #toString()} never prints it.
 */
public record NewGrowerCommand(
        long territoryId,
        String displayName,
        String phone,
        String preferredLanguage,
        Long preferredPartnerOrgId,
        UUID idempotencyKey) {

    public NewGrowerCommand {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(phone, "phone");
        Objects.requireNonNull(preferredLanguage, "preferredLanguage");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    @Override
    public String toString() {
        return "NewGrowerCommand[territoryId=%d, preferredLanguage=%s, preferredPartnerOrgId=%s, idempotencyKey=%s]"
                .formatted(territoryId, preferredLanguage, preferredPartnerOrgId, idempotencyKey);
    }
}
