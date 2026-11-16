package com.growercare.grower.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SPEC-GRW-01 R22 (masking), R15 (language), R16/R17 (request hash), unit level. */
class GrowerRulesTest {

    @ParameterizedTest(name = "GRW-R22 \"{0}\" → \"{1}\"")
    @CsvSource(delimiter = '|', textBlock = """
            Ramesh Patil                  | R***** P****
            Lakshmi (assisted onboarding) | L****** (******** o**********
            A                             | A
            Joao Silva                    | J*** S****
            """)
    void namesAreMaskedWordByWord(String name, String masked) {
        assertThat(GrowerRules.maskName(name)).isEqualTo(masked);
    }

    @ParameterizedTest(name = "GRW-R15 language {0} → {1}")
    @CsvSource(textBlock = """
            mr,    true
            pt-BR, true
            en,    true
            xx,    false
            MR,    false
            mr-br, false
            ''  ,  false
            """)
    void supportedLanguages(String language, boolean supported) {
        assertThat(GrowerRules.isSupportedLanguage(language)).isEqualTo(supported);
    }

    @Test
    void requestHashIsStableAndSensitiveToEveryField() {
        var base = new NewGrowerCommand(1002L, "Anil Shinde", "+919988776655", "mr", 501L, UUID.randomUUID());
        var same = GrowerRules.requestHash(base, 102L);
        assertThat(GrowerRules.requestHash(base, 102L)).isEqualTo(same).hasSize(32);
        var variants = new NewGrowerCommand[] {
                new NewGrowerCommand(1003L, "Anil Shinde", "+919988776655", "mr", 501L, base.idempotencyKey()),
                new NewGrowerCommand(1002L, "Anil Shindé", "+919988776655", "mr", 501L, base.idempotencyKey()),
                new NewGrowerCommand(1002L, "Anil Shinde", "+919988776656", "mr", 501L, base.idempotencyKey()),
                new NewGrowerCommand(1002L, "Anil Shinde", "+919988776655", "hi", 501L, base.idempotencyKey()),
                new NewGrowerCommand(1002L, "Anil Shinde", "+919988776655", "mr", null, base.idempotencyKey())};
        for (var variant : variants) {
            assertThat(Arrays.equals(GrowerRules.requestHash(variant, 102L), same)).as(variant.toString()).isFalse();
        }
        assertThat(Arrays.equals(GrowerRules.requestHash(base, 103L), same)).as("other caller").isFalse();
        assertThat(base.toString()).doesNotContain("9988776655");   // PII never printed
    }
}
