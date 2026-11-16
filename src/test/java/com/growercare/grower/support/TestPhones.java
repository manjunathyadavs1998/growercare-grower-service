package com.growercare.grower.support;

import com.growercare.grower.domain.GrowerPlacement;
import com.growercare.grower.domain.PhoneNumber;
import com.growercare.grower.domain.PhonePlan;
import com.growercare.grower.domain.PhoneProtector;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fresh phone numbers for registration tests. The light test database only has a few shard schemas, and a grower is
 * placed by its phone (SPEC-GRW-01 D1), so a usable number must hash (dev key) into one of them. Deterministic: the
 * candidates are scanned in order, each number is handed out once per JVM.
 */
public final class TestPhones {

    /** The dev keys from .env.example (also used by the dev seed and src/test/resources/config/application.yml). */
    public static final PhoneProtector DEV_KEYS = PhoneProtector.fromBase64(
            "ZGV2LW9ubHktcGhvbmUtaG1hYy1rZXktMzItYnl0ZXM=", "ZGV2LW9ubHktcGhvbmUtZW5jcnlwdGlvbi1rZXktMzI=", "dev-1");
    public static final PhonePlan IN = new PhonePlan("91", "[6-9][0-9]{9}");

    private static final AtomicLong NEXT_IN = new AtomicLong(9_000_000_000L);

    private TestPhones() { }

    /** A not-yet-used Indian mobile number (E.164) that lands in a shard of the test database. */
    public static String nextIndian() {
        while (true) {
            var national = Long.toString(NEXT_IN.getAndIncrement());
            var phone = PhoneNumber.parse(national, IN).orElseThrow();
            if (GrowerTestDatabase.TEST_SHARDS.contains(shardOf(phone, "IN"))) {
                return phone.e164();
            }
        }
    }

    public static com.growercare.shard.ShardId shardOf(PhoneNumber phone, String country) {
        return new com.growercare.shard.ShardId(country, GrowerPlacement.shardNo(DEV_KEYS.hmac(phone)));
    }

    public static byte[] hmacOf(String e164) {
        return DEV_KEYS.hmac(PhoneNumber.parse(e164, IN).orElseThrow());
    }
}
