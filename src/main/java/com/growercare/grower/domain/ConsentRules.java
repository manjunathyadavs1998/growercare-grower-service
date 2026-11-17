package com.growercare.grower.domain;

import java.time.Duration;
import java.util.regex.Pattern;

/** Fixed rules of phone verification and consent (SPEC-GRW-03 D3–D5). Framework-free. */
public final class ConsentRules {

    private ConsentRules() { }

    /** D5: what a grower consents to. SERVICE is required to activate and cannot be withdrawn here. */
    public enum Purpose { SERVICE, DATA_SHARING_RETAILER, MARKETING }

    /** D5 / PRD D3: the grower confirmed themself, or someone recorded it for them (assisted). */
    public enum Channel { SELF, ASSISTED }

    public enum VerificationStatus { OPEN, CONSUMED, EXHAUSTED }

    /** The only verification purpose in M1 (D6: phone change comes with SPEC-GRW-05). */
    public static final String ACTIVATION = "ACTIVATION";

    public static final Duration CODE_TTL = Duration.ofMinutes(5);
    public static final int MAX_ATTEMPTS = 5;
    public static final Duration RATE_WINDOW = Duration.ofMinutes(15);
    public static final int MAX_STARTS_PER_WINDOW = 3;
    /** R112: the language a code is sent in when the grower's own has no notice in their country. */
    public static final String FALLBACK_LANGUAGE = "en";

    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");

    public static boolean isCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    /** @return the purpose, or null when the text names none */
    public static Purpose purpose(String raw) {
        if (raw == null) {
            return null;
        }
        for (var p : Purpose.values()) {
            if (p.name().equals(raw)) {
                return p;
            }
        }
        return null;
    }
}
