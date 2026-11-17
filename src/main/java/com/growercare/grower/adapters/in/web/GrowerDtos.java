package com.growercare.grower.adapters.in.web;

import com.growercare.common.error.BusinessRuleException;
import com.growercare.common.error.NotFoundException;
import com.growercare.common.error.ValidationException;
import com.growercare.grower.application.port.GrowerRepository;
import com.growercare.grower.domain.GrowerChanges;
import com.growercare.grower.domain.GrowerRecord;
import com.growercare.grower.domain.GrowerRules;
import com.growercare.security.PiiClearance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

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

    /** R42: what case-service replicates. Unmasked (a service, not a person); the phone only as HMAC (base64). */
    record GrowerReplicaView(String growerId, String countryCode, String territoryId, String userId, String displayName,
                             String phoneHmac, String phoneLast4, String preferredPartnerOrgId, String status, int version) {
        static GrowerReplicaView of(GrowerRepository.Replica r) {
            var g = r.grower();
            return new GrowerReplicaView(id(g.growerId()), g.countryCode(), id(g.territoryId()), id(g.userId()), g.displayName(),
                    Base64.getEncoder().encodeToString(r.phoneHmac()), g.phoneLast4(), id(g.preferredPartnerOrgId()),
                    g.status(), g.version());
        }
    }

    // ------------------------------------------------------------------------------------------------ PATCH (R23–R29)

    static final Set<String> EDITABLE = Set.of("displayName", "preferredLanguage", "territoryId", "preferredPartnerOrgId");

    /**
     * Reads a JSON merge patch (R23). Unknown or immutable fields are a 422 naming the field (R25); a body that is not
     * a non-empty object, or a field of the wrong type, is a 400 naming the field (R28). Null clears only the partner.
     */
    static GrowerChanges changes(JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw ValidationException.of("body", "must be a JSON object with at least one of " + new TreeSet<>(EDITABLE));
        }
        for (var entry : body.properties()) {
            if (!EDITABLE.contains(entry.getKey())) {
                throw new BusinessRuleException("grower.field-immutable",
                        "Field " + entry.getKey() + " cannot be changed here");
            }
        }
        var territory = body.get("territoryId");
        var partner = body.get("preferredPartnerOrgId");
        return new GrowerChanges(
                text(body, "displayName"),
                text(body, "preferredLanguage"),
                territory == null ? null : id(idText(territory, "territoryId"), "territoryId"),
                partner != null,
                partner == null || partner.isNull() ? null : id(idText(partner, "preferredPartnerOrgId"), "preferredPartnerOrgId"));
    }

    private static String text(JsonNode body, String field) {
        var node = body.get(field);
        if (node == null) {
            return null;
        }
        if (!node.isString()) {
            throw ValidationException.of(field, "must be a string");
        }
        return node.asString();
    }

    private static String idText(JsonNode node, String field) {
        if (!node.isString() || !node.asString().matches(ID)) {
            throw ValidationException.of(field, "must be an id as a string, e.g. \"1002\"");
        }
        return node.asString();
    }

    private static final java.util.regex.Pattern ETAG = java.util.regex.Pattern.compile("^(?:W/)?\"(\\d{1,9})\"$");

    /** {@code If-Match: "7"} (weak form accepted) → 7; anything else is a 400 on the header (R24). */
    static int version(String ifMatch) {
        var m = ETAG.matcher(ifMatch.strip());
        if (!m.matches()) {
            throw ValidationException.of("If-Match", "must be the ETag of the current version, e.g. \"3\"");
        }
        return Integer.parseInt(m.group(1));
    }

    static String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    /**
     * R15: a body id. The {@link #ID} pattern also admits 19-digit values above {@code Long.MAX_VALUE}, so overflow is a
     * 400 on the field here, never a 500.
     */
    static long id(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ValidationException.of(field, "must be a positive 64-bit id");
        }
    }

    static Long optionalId(String raw, String field) {
        return raw == null ? null : id(raw, field);
    }

    /** R21: malformed path ids (including 19-digit overflow) are indistinguishable from unknown ones. */
    static long growerIdOrNotFound(String raw) {
        if (raw == null || !raw.matches(ID)) {
            throw new NotFoundException("Grower");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new NotFoundException("Grower");
        }
    }

    /** SPEC-GRW-06 R304: an opaque keyset cursor "createdAt|growerId", base64url. */
    static String partnerCursor(com.growercare.grower.application.port.GrowerRepository.PartnerGrower last) {
        if (last == null) {
            return null;
        }
        var text = last.createdAt() + "|" + last.growerId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static com.growercare.grower.application.port.GrowerRepository.PartnerGrower partnerCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            var text = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
            int sep = text.indexOf('|');
            return new com.growercare.grower.application.port.GrowerRepository.PartnerGrower(
                    Long.parseLong(text.substring(sep + 1)), Instant.parse(text.substring(0, sep)));
        } catch (RuntimeException e) {
            throw ValidationException.of("cursor", "is not a cursor returned by this API");
        }
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
