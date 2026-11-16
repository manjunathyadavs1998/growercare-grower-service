package com.growercare.grower.domain;

import java.nio.ByteBuffer;

/**
 * SPEC-GRW-01 D1: where a new grower lives. The shard is chosen from the phone HMAC, so the grower row and its
 * phone-directory row share a shard and register / find-by-phone stay single-shard. The grower id is then generated
 * by that shard ({@code next_id()}), so every later read routes by decoding the id.
 */
public final class GrowerPlacement {

    public static final int SHARDS_PER_COUNTRY = 64;
    private static final int HMAC_BYTES = 32;

    private GrowerPlacement() { }

    /** {@code unsigned(first 8 bytes of the HMAC) mod 64}; an HMAC is already uniform, so no extra mixing. */
    public static int shardNo(byte[] phoneHmac) {
        if (phoneHmac == null || phoneHmac.length != HMAC_BYTES) {
            throw new IllegalArgumentException("phone HMAC must be " + HMAC_BYTES + " bytes");
        }
        return (int) Long.remainderUnsigned(ByteBuffer.wrap(phoneHmac).getLong(), SHARDS_PER_COUNTRY);
    }
}
