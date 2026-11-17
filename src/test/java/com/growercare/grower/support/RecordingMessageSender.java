package com.growercare.grower.support;

import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.grower.application.port.MessageSender;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test double for notification-service (SPEC-GRW-03 §2 fixture): records every send and can fail (R104). */
public final class RecordingMessageSender implements MessageSender {

    public record Sent(String phone, String language, String code, int minutes, UUID idempotencyKey) { }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    public final AtomicBoolean failing = new AtomicBoolean();

    @Override
    public void sendOtp(String phoneE164, String language, String code, int validMinutes, UUID idempotencyKey) {
        if (failing.get()) {
            throw new DependencyUnavailableException("notification-service", null);
        }
        sent.add(new Sent(phoneE164, language, code, validMinutes, idempotencyKey));
    }

    /** The message sent for a verification (its id is the idempotency key). */
    public Optional<Sent> forVerification(String verificationId) {
        return sent.stream().filter(s -> s.idempotencyKey().toString().equals(verificationId)).findFirst();
    }

    public long countTo(String phone) {
        return sent.stream().filter(s -> s.phone().equals(phone)).count();
    }
}
