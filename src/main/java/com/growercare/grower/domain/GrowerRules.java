package com.growercare.grower.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Pure grower rules (SPEC-GRW-01). */
public final class GrowerRules {

    public static final int MAX_NAME = 120;
    private static final Pattern LANGUAGE = Pattern.compile("^([a-z]{2})(-[A-Z]{2})?$");
    private static final Set<String> ISO_LANGUAGES = Set.of(Locale.getISOLanguages());

    private GrowerRules() { }

    /** R22: every word keeps its first character (same algorithm as case-service, so both show the same mask). */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Arrays.stream(name.split(" ", -1))
                .map(word -> word.isEmpty() ? word : word.charAt(0) + "*".repeat(word.length() - 1))
                .collect(Collectors.joining(" "));
    }

    /** R15: an ISO 639-1 language, optionally with an ISO 3166 region ({@code mr}, {@code pt-BR}). */
    public static boolean isSupportedLanguage(String language) {
        if (language == null) {
            return false;
        }
        var m = LANGUAGE.matcher(language);
        return m.matches() && ISO_LANGUAGES.contains(m.group(1));
    }

    /**
     * R16/R17: SHA-256 over the canonical request and the caller, so a replay with the same key must be the same request
     * by the same user. Fields are separated by NUL, which cannot occur in validated input.
     */
    public static byte[] requestHash(NewGrowerCommand cmd, long callerUserId) {
        var canonical = String.join("\u0000", Long.toString(callerUserId), Long.toString(cmd.territoryId()), cmd.displayName(),
                cmd.phone(), cmd.preferredLanguage(), String.valueOf(cmd.preferredPartnerOrgId()));
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
