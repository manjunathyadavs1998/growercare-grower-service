package com.growercare.grower.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.security.GcPrincipal;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Helpers for SPEC-GRW-03 tests: start and confirm verifications, set consents, fresh growers. */
public abstract class ConsentApiTestBase extends GrowerApiTestBase {

    protected static final String NOTICE = "in-2026-11";

    /** A grower that registered themself (PENDING_CONSENT, R100), with its phone. */
    protected record Fresh(GcPrincipal user, String growerId, String phone) { }

    protected Fresh selfRegistered() throws Exception {
        var user = newGrowerUser();
        var body = assisted();
        return new Fresh(user, registerGrower(user, body), (String) body.get("phone"));
    }

    /** A grower registered by {@code actor} (assisted, PENDING_CONSENT). */
    protected Fresh assistedBy(GcPrincipal actor) throws Exception {
        var body = assisted();
        return new Fresh(actor, registerGrower(actor, body), (String) body.get("phone"));
    }

    protected ResultActions start(GcPrincipal actor, String growerId) throws Exception {
        return as(actor, post("/growers/{id}/verifications", growerId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"ACTIVATION\"}"));
    }

    /** Starts a verification (202) and returns its id. */
    protected String started(GcPrincipal actor, String growerId) throws Exception {
        var json = start(actor, growerId).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.verificationId");
    }

    protected String codeOf(String verificationId) {
        return messages.forVerification(verificationId).orElseThrow().code();
    }

    /** A wrong code that differs from the sent one. */
    protected String wrongCodeFor(String verificationId) {
        var code = codeOf(verificationId);
        return code.equals("000000") ? "111111" : "000000";
    }

    protected static Map<String, Object> confirmation(String code, Object... purposeGranted) {
        var consents = new ArrayList<Map<String, Object>>();
        for (int i = 0; i + 1 < purposeGranted.length; i += 2) {
            var c = new LinkedHashMap<String, Object>();
            c.put("purpose", purposeGranted[i]);
            c.put("granted", purposeGranted[i + 1]);
            consents.add(c);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("noticeVersion", NOTICE);
        body.put("language", "mr");
        body.put("consents", consents);
        return body;
    }

    protected ResultActions confirm(GcPrincipal actor, String growerId, String verificationId, Map<String, Object> body) throws Exception {
        return as(actor, post("/growers/{id}/verifications/{vid}/confirm", growerId, verificationId)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    /** A self-registered grower taken through the whole activation (R105). */
    protected Fresh activated() throws Exception {
        var g = selfRegistered();
        var vid = started(g.user(), g.growerId());
        confirm(g.user(), g.growerId(), vid, confirmation(codeOf(vid), "SERVICE", true)).andExpect(status().isOk());
        return g;
    }

    protected ResultActions setConsent(GcPrincipal actor, String growerId, String purpose, boolean granted, String notice) throws Exception {
        var body = Map.of("granted", granted, "noticeVersion", notice, "language", "mr");
        return as(actor, put("/growers/{id}/consents/{purpose}", growerId, purpose).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    protected static String schemaOf(String growerId) {
        return GrowerTestDatabase.ROUTER.forId(Long.parseLong(growerId)).orElseThrow().schemaName();
    }

    protected List<Map<String, Object>> outbox(String growerId, String eventType) {
        return owner.sql("SELECT payload::text AS payload FROM %s.outbox WHERE aggregate_id = ? AND event_type = ? ORDER BY occurred_at"
                        .formatted(schemaOf(growerId)))
                .param(growerId).param(eventType).query().listOfRows();
    }

    protected int attempts(String growerId, String verificationId) {
        return owner.sql("SELECT attempts FROM %s.grower_verification WHERE verification_id = ?::uuid".formatted(schemaOf(growerId)))
                .param(verificationId).query(Integer.class).single();
    }
}
