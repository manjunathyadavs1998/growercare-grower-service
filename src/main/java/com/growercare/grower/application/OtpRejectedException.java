package com.growercare.grower.application;

/**
 * SPEC-GRW-03 R108: a wrong one-time code. A 422 like {@code BusinessRuleException}, plus {@code attemptsLeft}
 * (gc-common's exception family is sealed, so grower-service renders this one itself; see {@code OtpProblemHandler}).
 */
public final class OtpRejectedException extends RuntimeException {

    public static final String RULE = "grower.otp-invalid";

    private final int attemptsLeft;

    public OtpRejectedException(int attemptsLeft) {
        super("The code is not correct");
        this.attemptsLeft = attemptsLeft;
    }

    public int attemptsLeft() {
        return attemptsLeft;
    }
}
