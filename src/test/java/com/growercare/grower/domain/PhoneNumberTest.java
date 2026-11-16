package com.growercare.grower.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-GRW-01 D3, R15 (phone field): normalisation with the country's phone plan from reference data. */
class PhoneNumberTest {

    static final PhonePlan IN = new PhonePlan("91", "[6-9][0-9]{9}");
    static final PhonePlan BR = new PhonePlan("55", "[1-9][0-9]{9,10}");

    static Stream<Arguments> valid() {
        return Stream.of(
                argumentSet("GRW-E80 IN international with spaces", IN, "+91 98765 43210", "+919876543210"),
                argumentSet("GRW-E80 IN national with dash", IN, "98765-43210", "+919876543210"),
                argumentSet("GRW-E80 IN trunk zero in brackets", IN, "(0)9876543210", "+919876543210"),
                argumentSet("GRW-E80 IN already E.164", IN, "+919876543210", "+919876543210"),
                argumentSet("GRW-E81 BR international mobile", BR, "+55 11 91234 5678", "+5511912345678"),
                argumentSet("GRW-E81 BR national mobile", BR, "11912345678", "+5511912345678"),
                argumentSet("GRW-E81 BR national landline (10 digits)", BR, "1132345678", "+551132345678"));
    }

    @ParameterizedTest
    @MethodSource("valid")
    void normalises(PhonePlan plan, String raw, String e164) {
        assertThat(PhoneNumber.parse(raw, plan)).hasValueSatisfying(phone -> assertThat(phone.e164()).isEqualTo(e164));
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                argumentSet("GRW-E82 other country's calling code", IN, "+55 11 91234 5678"),
                argumentSet("GRW-E82 too short", IN, "12345"),
                argumentSet("GRW-E82 IN mobile must start 6-9", IN, "5876543210"),
                argumentSet("GRW-E82 11 digits for IN", IN, "98765432101"),
                argumentSet("GRW-E82 empty", IN, ""),
                argumentSet("GRW-E82 letters", IN, "98765abc10"),
                argumentSet("GRW-E82 null", IN, null));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void rejects(PhonePlan plan, String raw) {
        assertThat(PhoneNumber.parse(raw, plan)).isEmpty();
    }

    static Stream<Arguments> display() {
        return Stream.of(
                argumentSet("GRW-E85 IN", IN, "+919876543210", "3210"),
                argumentSet("GRW-E85 BR", BR, "+5511912345678", "5678"));
    }

    @ParameterizedTest
    @MethodSource("display")
    void lastFourAndToString(PhonePlan plan, String raw, String lastFour) {
        var phone = PhoneNumber.parse(raw, plan).orElseThrow();
        assertThat(phone.lastFour()).isEqualTo(lastFour);
        assertThat(phone.toString()).doesNotContain(phone.e164().substring(1, phone.e164().length() - 4)).contains(lastFour);
    }
}
