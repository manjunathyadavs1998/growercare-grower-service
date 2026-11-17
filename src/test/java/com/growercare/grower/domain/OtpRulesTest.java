package com.growercare.grower.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SPEC-GRW-03 D3, R108, R111, R131: code format, HMAC binding, constant-time comparison. */
class OtpRulesTest {

    static final PhoneProtector KEYS = new PhoneProtector(new byte[32], new byte[32], "test");

    @ParameterizedTest(name = "GRW-E113 code \"{0}\" valid: {1}")
    @CsvSource(textBlock = """
            # code,    valid
            123456,    true
            000000,    true
            12345,     false
            1234567,   false
            12a456,    false
            ' 23456',  false
            """)
    void codesAreExactlySixDigits(String code, boolean valid) {
        assertThat(ConsentRules.isCode(code)).isEqualTo(valid);
    }

    @Test
    void GRW_E131_codesAreSixRandomDigitsAndStoredAsA32ByteHmac() {
        var codes = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            var code = KEYS.newOtpCode();
            assertThat(ConsentRules.isCode(code)).isTrue();
            codes.add(code);
        }
        assertThat(codes).hasSizeGreaterThan(150);
        assertThat(KEYS.otpHmac(UUID.randomUUID(), "123456")).hasSize(32);
    }

    @ParameterizedTest(name = "GRW-E109 {0}")
    @CsvSource(textBlock = """
            # case,                         submitted, same verification, matches
            the right code,                 482913,    true,              true
            a wrong code,                   482914,    true,              false
            'the right code of another verification', 482913, false, false
            """)
    void aCodeMatchesOnlyItsOwnVerification(String name, String submitted, boolean sameVerification, boolean matches) {
        var vid = UUID.randomUUID();
        var stored = KEYS.otpHmac(vid, "482913");
        assertThat(KEYS.otpMatches(sameVerification ? vid : UUID.randomUUID(), submitted, stored)).isEqualTo(matches);
    }

    @ParameterizedTest(name = "GRW-E113 purpose {0} known: {1}")
    @CsvSource(textBlock = """
            SERVICE,               true
            DATA_SHARING_RETAILER, true
            MARKETING,             true
            FOO,                   false
            service,               false
            """)
    void purposesAreTheSpecsThree(String raw, boolean known) {
        assertThat(ConsentRules.purpose(raw) != null).isEqualTo(known);
    }
}
