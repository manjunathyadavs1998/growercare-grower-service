package com.growercare.grower.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A validated phone number in E.164 form (SPEC-GRW-01 D3). It is PII: {@link #toString()} shows only the last four
 * digits, so the number cannot leak into logs by accident. The plain number exists only in memory while a request
 * is handled; it is stored as an HMAC, a ciphertext and {@link #lastFour()}.
 */
public final class PhoneNumber {

    /** Separators people type: spaces, dashes, dots, brackets. */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-.()]");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private final String e164;

    private PhoneNumber(String e164) {
        this.e164 = e164;
    }

    /**
     * Normalises {@code raw} with the country's plan: separators and a leading trunk {@code 0} are removed, and a
     * {@code +<cc>} prefix must carry the plan's own calling code.
     *
     * @return the number, or empty when it is not a valid number of that country (R15)
     */
    public static Optional<PhoneNumber> parse(String raw, PhonePlan plan) {
        if (raw == null) {
            return Optional.empty();
        }
        var compact = SEPARATORS.matcher(raw.strip()).replaceAll("");
        String national;
        if (compact.startsWith("+")) {
            if (!compact.startsWith("+" + plan.callingCode())) {
                return Optional.empty();
            }
            national = compact.substring(plan.callingCode().length() + 1);
        } else {
            national = compact.startsWith("0") ? compact.substring(1) : compact;
        }
        if (!DIGITS.matcher(national).matches() || !plan.accepts(national)) {
            return Optional.empty();
        }
        return Optional.of(new PhoneNumber("+" + plan.callingCode() + national));
    }

    public String e164() {
        return e164;
    }

    public String lastFour() {
        return e164.substring(e164.length() - 4);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PhoneNumber phone && phone.e164.equals(e164);
    }

    @Override
    public int hashCode() {
        return e164.hashCode();
    }

    @Override
    public String toString() {
        return "PhoneNumber[***" + lastFour() + "]";
    }
}
