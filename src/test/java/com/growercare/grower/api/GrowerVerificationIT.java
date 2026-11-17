package com.growercare.grower.api;

import static com.growercare.grower.support.DevSeedPrincipals.ADMIN_IN;
import static com.growercare.grower.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER2_IN;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.grower.support.ConsentApiTestBase;
import com.growercare.grower.support.SeedGrowers;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** SPEC-GRW-03 R101–R112, R131 (GRW-E101…E114, E131): phone verification and activation. */
class GrowerVerificationIT extends ConsentApiTestBase {

    @Test
    void GRW_E101_startingSendsASixDigitCodeToTheGrowersPhone() throws Exception {
        var g = selfRegistered();
        var json = start(g.user(), g.growerId())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.purpose").value("ACTIVATION"))
                .andExpect(jsonPath("$.phoneLast4").value(g.phone().substring(g.phone().length() - 4)))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        var sent = messages.forVerification(JsonPath.read(json, "$.verificationId")).orElseThrow();
        assertThat(sent.phone()).isEqualTo(g.phone());
        assertThat(sent.language()).isEqualTo("mr");
        assertThat(sent.code()).matches("[0-9]{6}");
        assertThat(sent.minutes()).isEqualTo(5);
    }

    static Stream<Arguments> starters() {
        return Stream.of(
                argumentSet("GRW-E102 retailer.in onboarded the grower", RETAILER_IN, 202),
                argumentSet("GRW-E102 retailer2.in, not in scope", RETAILER2_IN, 404),
                argumentSet("GRW-E102 admin.in, no grower:consent", ADMIN_IN, 403));
    }

    @ParameterizedTest
    @MethodSource("starters")
    void whoMayStartAVerification(com.growercare.security.GcPrincipal actor, int expected) throws Exception {
        var g = assistedBy(RETAILER_IN);
        start(actor, g.growerId()).andExpect(status().is(expected));
    }

    @Test
    void GRW_E103_anActiveGrowerIsNotVerifiedAgain() throws Exception {
        problem(start(CARE_IN, Long.toString(SeedGrowers.G1.growerId())), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.already-active"));
    }

    @Test
    void GRW_E104_aFourthStartWithinFifteenMinutesIsRateLimited() throws Exception {
        var g = selfRegistered();
        for (int i = 0; i < 3; i++) {
            started(g.user(), g.growerId());
        }
        problem(start(g.user(), g.growerId()), 422, "BUSINESS_RULE_VIOLATION").andExpect(jsonPath("$.rule").value("grower.otp-rate-limited"));
        assertThat(messages.countTo(g.phone())).isEqualTo(3);
    }

    @Test
    void GRW_E105_aRefusedSendLeavesNoVerification() throws Exception {
        var g = selfRegistered();
        messages.failing.set(true);
        try {
            problem(start(g.user(), g.growerId()), 503, "DEPENDENCY_UNAVAILABLE");
        } finally {
            messages.failing.set(false);
        }
        assertThat(owner.sql("SELECT count(*) FROM %s.grower_verification WHERE grower_id = ?".formatted(schemaOf(g.growerId())))
                .param(Long.parseLong(g.growerId())).query(Long.class).single()).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void GRW_E106_E131_theRightCodeAndServiceConsentActivateTheGrower(CapturedOutput output) throws Exception {
        var g = selfRegistered();
        var vid = started(g.user(), g.growerId());
        var code = codeOf(vid);
        var json = confirm(g.user(), g.growerId(), vid, confirmation(code, "SERVICE", true, "MARKETING", true))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();

        var rows = owner.sql("SELECT purpose || ':' || granted || ':' || notice_version || ':' || language || ':' || channel"
                        + " FROM %s.grower_consent WHERE grower_id = ? ORDER BY purpose".formatted(schemaOf(g.growerId())))
                .param(Long.parseLong(g.growerId())).query(String.class).list();
        assertThat(rows).containsExactly("MARKETING:true:in-2026-11:mr:SELF", "SERVICE:true:in-2026-11:mr:SELF");
        assertThat(outbox(g.growerId(), "grower.updated")).singleElement()
                .satisfies(e -> assertThat((String) e.get("payload")).contains("\"changedFields\": [\"status\"]", "\"version\": 1"));
        var recorded = outbox(g.growerId(), "grower.consent.recorded");
        assertThat(recorded).hasSize(2);
        // E131: the code exists only as a 32-byte HMAC
        var hmac = owner.sql("SELECT code_hmac FROM %s.grower_verification WHERE verification_id = ?::uuid".formatted(schemaOf(g.growerId())))
                .param(vid).query(byte[].class).single();
        assertThat(hmac).hasSize(32);
        assertThat(json).doesNotContain(code);
        assertThat(recorded.toString()).doesNotContain(code, g.phone().substring(3), "Anil");
        assertThat(output.getAll()).doesNotContain(code, g.phone().substring(3));
    }

    @Test
    void GRW_E107_aRetailerConfirmsTheCodeTheGrowerReadsOut() throws Exception {
        var g = assistedBy(RETAILER_IN);
        var vid = started(RETAILER_IN, g.growerId());
        confirm(RETAILER_IN, g.growerId(), vid, confirmation(codeOf(vid), "SERVICE", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        var row = owner.sql("SELECT channel || ':' || recorded_by_user_id FROM %s.grower_consent WHERE grower_id = ?".formatted(schemaOf(g.growerId())))
                .param(Long.parseLong(g.growerId())).query(String.class).single();
        assertThat(row).isEqualTo("ASSISTED:201");
    }

    static Stream<Arguments> refusedWithoutAttempt() {
        return Stream.of(
                argumentSet("GRW-E108 SERVICE missing", List.of("MARKETING", true), null, "grower.service-consent-required"),
                argumentSet("GRW-E108 SERVICE not granted", List.of("SERVICE", false), null, "grower.service-consent-required"),
                argumentSet("GRW-E108 outdated notice", List.of("SERVICE", true), "in-2025-01", "grower.notice-outdated"));
    }

    @ParameterizedTest
    @MethodSource("refusedWithoutAttempt")
    void consentAndNoticeErrorsCostNoAttempt(List<Object> consent, String notice, String rule) throws Exception {
        var g = selfRegistered();
        var vid = started(g.user(), g.growerId());
        var body = confirmation(codeOf(vid), consent.toArray());
        if (notice != null) {
            body.put("noticeVersion", notice);
        }
        problem(confirm(g.user(), g.growerId(), vid, body), 422, "BUSINESS_RULE_VIOLATION").andExpect(jsonPath("$.rule").value(rule));
        assertThat(attempts(g.growerId(), vid)).isZero();
    }

    @Test
    void GRW_E109_wrongCodesCountDownAndTheRightOneStillWorks() throws Exception {
        var g = selfRegistered();
        var vid = started(g.user(), g.growerId());
        for (int left = 4; left >= 1; left--) {
            problem(confirm(g.user(), g.growerId(), vid, confirmation(wrongCodeFor(vid), "SERVICE", true)), 422, "BUSINESS_RULE_VIOLATION")
                    .andExpect(jsonPath("$.rule").value("grower.otp-invalid"))
                    .andExpect(jsonPath("$.attemptsLeft").value(left));
        }
        confirm(g.user(), g.growerId(), vid, confirmation(codeOf(vid), "SERVICE", true)).andExpect(status().isOk());
    }

    @Test
    void GRW_E110_theFifthWrongCodeLocksTheVerificationAndNewStarts() throws Exception {
        var g = selfRegistered();
        var vid = started(g.user(), g.growerId());
        for (int i = 0; i < 4; i++) {
            confirm(g.user(), g.growerId(), vid, confirmation(wrongCodeFor(vid), "SERVICE", true)).andExpect(status().is(422));
        }
        problem(confirm(g.user(), g.growerId(), vid, confirmation(wrongCodeFor(vid), "SERVICE", true)), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.otp-locked"));
        problem(confirm(g.user(), g.growerId(), vid, confirmation(codeOf(vid), "SERVICE", true)), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.otp-locked"));
        problem(start(g.user(), g.growerId()), 422, "BUSINESS_RULE_VIOLATION").andExpect(jsonPath("$.rule").value("grower.otp-rate-limited"));
    }

    @Test
    void GRW_E111_anExpiredCodeIsRefused() throws Exception {
        var g = selfRegistered();
        var vid = started(g.user(), g.growerId());
        owner.sql("UPDATE %s.grower_verification SET expires_at = now() - interval '1 second' WHERE verification_id = ?::uuid"
                .formatted(schemaOf(g.growerId()))).param(vid).update();
        problem(confirm(g.user(), g.growerId(), vid, confirmation(codeOf(vid), "SERVICE", true)), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.otp-expired"));
    }

    @Test
    void GRW_E111_aConsumedCodeIsRefused() throws Exception {
        var g = selfRegistered();
        var vid = started(g.user(), g.growerId());
        confirm(g.user(), g.growerId(), vid, confirmation(codeOf(vid), "SERVICE", true)).andExpect(status().isOk());
        problem(confirm(g.user(), g.growerId(), vid, confirmation(codeOf(vid), "SERVICE", true)), 422, "BUSINESS_RULE_VIOLATION")
                .andExpect(jsonPath("$.rule").value("grower.otp-used"));
    }

    @Test
    void GRW_E112_aVerificationIsOnlyReachableThroughItsOwnGrower() throws Exception {
        var a = selfRegistered();
        var b = selfRegistered();
        var bVid = started(b.user(), b.growerId());
        problem(confirm(a.user(), a.growerId(), bVid, confirmation(codeOf(bVid), "SERVICE", true)), 404, "NOT_FOUND");
        problem(confirm(a.user(), a.growerId(), UUID.randomUUID().toString(), confirmation("123456", "SERVICE", true)), 404, "NOT_FOUND");
        problem(confirm(a.user(), a.growerId(), "not-a-uuid", confirmation("123456", "SERVICE", true)), 404, "NOT_FOUND");
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                argumentSet("GRW-E113 5-digit code", Map.of("code", "12345"), "code"),
                argumentSet("GRW-E113 7-digit code", Map.of("code", "1234567"), "code"),
                argumentSet("GRW-E113 code with a letter", Map.of("code", "12a456"), "code"),
                argumentSet("GRW-E113 unknown purpose", Map.of("consents", List.of(Map.of("purpose", "FOO", "granted", true))), "consents[0].purpose"),
                argumentSet("GRW-E113 SERVICE twice", Map.of("consents", List.of(Map.of("purpose", "SERVICE", "granted", true),
                        Map.of("purpose", "SERVICE", "granted", true))), "consents"),
                argumentSet("GRW-E113 language without a notice", Map.of("language", "xx"), "language"),
                argumentSet("GRW-E113 no consents", Map.of("consents", List.of()), "consents"));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void invalidConfirmationsAre400AndCostNoAttempt(Map<String, Object> change, String field) throws Exception {
        var g = selfRegistered();
        var vid = started(g.user(), g.growerId());
        var body = confirmation(codeOf(vid), "SERVICE", true);
        body.putAll(change);
        problem(confirm(g.user(), g.growerId(), vid, body), 400, "VALIDATION_FAILED").andExpect(jsonPath("$.errors[*].field", hasItem(field)));
        assertThat(attempts(g.growerId(), vid)).isZero();
    }

    @Test
    void GRW_E114_aLanguageWithoutANoticeGetsTheCodeInEnglish() throws Exception {
        var vid = started(RETAILER2_IN, Long.toString(SeedGrowers.G3.growerId()));
        assertThat(messages.forVerification(vid).orElseThrow().language()).isEqualTo("en");
    }
}
