package com.growercare.grower.adapters.in.web;

import com.growercare.common.error.NotFoundException;
import com.growercare.common.error.ValidationException;
import com.growercare.grower.domain.GrowerRecord;
import com.growercare.grower.domain.GrowerRules;
import com.growercare.security.PiiClearance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public JSON contracts of grower-service (SPEC-GRW-01 §3). IDs are strings; no phone field ever (R22). */
final class GrowerDtos {

    private GrowerDtos() { }

    static final String ID = "^[1-9][0-9]{0,18}$";
    private static final java.util.regex.Pattern UUID_TEXT =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** R15 shape checks; name length and language support are checked again after trimming in the use case. */
    record RegisterRequest(
            @NotBlank @Pattern(regexp = ID) String territoryId,
            @NotBlank @Size(max = GrowerRules.MAX_NAME) String displayName,
            @NotBlank @Size(max = 32) String phone,
            @NotBlank @Size(max = 8) String preferredLanguage,
            @Pattern(regexp = ID) String preferredPartnerOrgId) {

        /** The phone is PII: never printed (logs, validation errors). */
        @Override
        public String toString() {
            return "RegisterRequest[territoryId=%s, preferredLanguage=%s, preferredPartnerOrgId=%s]"
                    .formatted(territoryId, preferredLanguage, preferredPartnerOrgId);
        }
    }

    /** R30: the phone travels in the body, never in the URL (R31). */
    record SearchRequest(@NotBlank @Size(max = 32) String phone, @NotNull @Pattern(regexp = "^[A-Z]{2}$") String countryCode) {
        @Override
        public String toString() {
            return "SearchRequest[countryCode=%s]".formatted(countryCode);
        }
    }

    record GrowerView(
            String growerId, String countryCode, String territoryId, String userId, String displayName, String phoneLast4,
            String preferredLanguage, String preferredPartnerOrgId, String status, String onboardingChannel,
            String onboardedByUserId, String onboardedViaOrgId, int version, Instant createdAt, Instant updatedAt) {

        /** R22: the name is masked unless the caller has FULL clearance; only the last 4 phone digits ever leave. */
        static GrowerView of(GrowerRecord g, PiiClearance clearance) {
            return new GrowerView(id(g.growerId()), g.countryCode(), id(g.territoryId()), id(g.userId()),
                    clearance == PiiClearance.FULL ? g.displayName() : GrowerRules.maskName(g.displayName()),
                    g.phoneLast4(), g.preferredLanguage(), id(g.preferredPartnerOrgId()), g.status(), g.onboardingChannel(),
                    id(g.onboardedByUserId()), id(g.onboardedViaOrgId()), g.version(), g.createdAt(), g.updatedAt());
        }
    }

    record GrowerPage(List<GrowerView> items) { }

    static String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    static Long optionalId(String value) {
        return value == null ? null : Long.parseLong(value);
    }

    /** R21: malformed path ids are indistinguishable from unknown ones. */
    static long growerIdOrNotFound(String raw) {
        if (raw == null || !raw.matches(ID)) {
            throw new NotFoundException("Grower");
        }
        return Long.parseLong(raw);
    }

    static String etag(int version) {
        return "\"" + version + "\"";
    }

    static UUID uuid(String raw, String field) {
        if (raw == null || !UUID_TEXT.matcher(raw).matches()) {
            throw ValidationException.of(field, "must be a UUID");
        }
        return UUID.fromString(raw);
    }
}
