package com.growercare.grower.application.port;

import java.util.UUID;

/**
 * Sends a one-time code through notification-service (SPEC-GRW-03 D2, SPEC-NOT-01). Synchronous: the caller's
 * transaction rolls back when this throws.
 */
public interface MessageSender {

    /**
     * @param phoneE164      the decrypted phone; never logged
     * @param idempotencyKey the verification id, so a retried send never delivers two codes
     * @throws com.growercare.common.error.DependencyUnavailableException when the message was not accepted (R104)
     */
    void sendOtp(String phoneE164, String language, String code, int validMinutes, UUID idempotencyKey);
}
