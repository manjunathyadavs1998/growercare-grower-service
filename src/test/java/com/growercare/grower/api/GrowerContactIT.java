package com.growercare.grower.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.grower.support.GrowerApiTestBase;
import com.growercare.grower.support.SeedGrowers;
import com.growercare.testsupport.TestJwts;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-GRW-07 R320–R322 (GRW-E320…E322): a sender reads an active grower's phone; every read is audited. */
class GrowerContactIT extends GrowerApiTestBase {

    static final String NOTIFICATION_SERVICE = TestJwts.service("notification-service").bearer();

    long audits(long growerId) {
        return owner.sql("SELECT count(*) FROM dir.grower_contact_read WHERE grower_id = ? AND client_id = 'notification-service'")
                .param(growerId).query(Long.class).single();
    }

    @Test
    void GRW_E320_notificationServiceReadsTheDecryptedPhoneAndAnAuditRowIsWritten() throws Exception {
        long g1 = SeedGrowers.G1.growerId();
        long before = audits(g1);
        mvc.perform(get("/internal/growers/{id}/contact", Long.toString(g1)).header("Authorization", NOTIFICATION_SERVICE))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.phone").value("+919876543210"))
                .andExpect(jsonPath("$.language").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(audits(g1)).isEqualTo(before + 1);
        var stored = owner.sql("SELECT row_to_json(r)::text FROM dir.grower_contact_read r WHERE grower_id = ? ORDER BY read_id DESC LIMIT 1")
                .param(g1).query(String.class).single();
        assertThat(stored).doesNotContain("9876543210");                                    // never the number
    }

    static Stream<Arguments> notActiveOrUnknown() {
        return Stream.of(
                argumentSet("GRW-E321 G3 is PENDING_CONSENT", Long.toString(SeedGrowers.G3.growerId())),
                argumentSet("GRW-E321 unknown grower", "999"),
                argumentSet("GRW-E321 malformed id", "abc"));
    }

    @ParameterizedTest
    @MethodSource("notActiveOrUnknown")
    void onlyActiveGrowersHaveAContact(String growerId) throws Exception {
        problem(mvc.perform(get("/internal/growers/{id}/contact", growerId).header("Authorization", NOTIFICATION_SERVICE)), 404, "NOT_FOUND");
    }

    static Stream<Arguments> notAllowed() {
        return Stream.of(
                argumentSet("GRW-E322 a grower's own token", (Supplier<String>) () -> TestJwts.user(TestJwts.GROWER_IN1).bearer(), 403),
                argumentSet("GRW-E322 case-service (may read growers, not phones)", (Supplier<String>) () -> TestJwts.service("case-service").bearer(), 403),
                argumentSet("GRW-E322 no token", (Supplier<String>) () -> null, 401));
    }

    @ParameterizedTest
    @MethodSource("notAllowed")
    void onlyContactClientsReadPhones(Supplier<String> bearer, int expected) throws Exception {
        var request = get("/internal/growers/{id}/contact", Long.toString(SeedGrowers.G1.growerId()));
        var token = bearer.get();
        if (token != null) {
            request.header("Authorization", token);
        }
        mvc.perform(request).andExpect(status().is(expected));
    }
}
