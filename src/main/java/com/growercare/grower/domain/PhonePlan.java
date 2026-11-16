package com.growercare.grower.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A country's phone numbering plan, from reference data ({@code ref.country.calling_code}, {@code national_pattern}),
 * so country differences stay out of code (convention 4, SPEC-GRW-01 D3).
 *
 * @param callingCode     country calling code without '+', e.g. {@code "91"}
 * @param nationalPattern regex the national significant number must match in full, e.g. {@code "[6-9][0-9]{9}"}
 */
public record PhonePlan(String callingCode, String nationalPattern) {

    public PhonePlan {
        Objects.requireNonNull(callingCode, "callingCode");
        Objects.requireNonNull(nationalPattern, "nationalPattern");
        if (!callingCode.matches("[1-9][0-9]{0,2}")) {
            throw new IllegalArgumentException("calling code must be 1-3 digits: " + callingCode);
        }
        Pattern.compile(nationalPattern); // fail fast on bad reference data
    }

    boolean accepts(String nationalNumber) {
        return nationalNumber.matches(nationalPattern);
    }
}
