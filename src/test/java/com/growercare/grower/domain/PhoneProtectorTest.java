package com.growercare.grower.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.util.Arrays;
import java.util.Base64;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-GRW-01 D3 (GRW-E83, E84): phone HMAC for lookup and AES-256-GCM for the later audited unmask. */
class PhoneProtectorTest {

    static final PhonePlan IN = new PhonePlan("91", "[6-9][0-9]{9}");
    static final PhoneNumber A = PhoneNumber.parse("+919876543210", IN).orElseThrow();
    static final PhoneNumber B = PhoneNumber.parse("+919876543211", IN).orElseThrow();

    static byte[] key(char fill) {
        var bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    static final PhoneProtector PROTECTOR = new PhoneProtector(key('h'), key('e'), "k1");
    static final PhoneProtector OTHER_KEYS = new PhoneProtector(key('x'), key('y'), "k2");

    static Stream<Arguments> hmacs() {
        return Stream.of(
                argumentSet("GRW-E83 same number → same HMAC", A, PROTECTOR, true),
                argumentSet("GRW-E83 different number → different HMAC", B, PROTECTOR, false),
                argumentSet("GRW-E83 other key → different HMAC", A, OTHER_KEYS, false));
    }

    @ParameterizedTest
    @MethodSource("hmacs")
    void hmac(PhoneNumber other, PhoneProtector protector, boolean equal) {
        var reference = PROTECTOR.hmac(A);
        var candidate = protector.hmac(other);
        assertThat(reference).hasSize(32);
        assertThat(Arrays.equals(reference, candidate)).isEqualTo(equal);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roundTrips")
    void encryption(String name, Function<PhoneProtector.Sealed, String> decrypt, String expected) {
        var sealed = PROTECTOR.encrypt(A);
        assertThat(sealed.keyId()).isEqualTo("k1");
        if (expected == null) {
            assertThatThrownBy(() -> decrypt.apply(sealed)).isInstanceOf(IllegalArgumentException.class);
        } else {
            assertThat(decrypt.apply(sealed)).isEqualTo(expected);
        }
    }

    static Stream<Arguments> roundTrips() {
        return Stream.of(
                Arguments.of("GRW-E84 decrypt → original", (Function<PhoneProtector.Sealed, String>) s -> PROTECTOR.decrypt(s), "+919876543210"),
                Arguments.of("GRW-E84 same number twice → different ciphertexts", (Function<PhoneProtector.Sealed, String>)
                        s -> Arrays.equals(s.ciphertext(), PROTECTOR.encrypt(A).ciphertext()) ? "same" : "different", "different"),
                Arguments.of("GRW-E84 tampered ciphertext → rejected", (Function<PhoneProtector.Sealed, String>) s -> {
                    var bytes = s.ciphertext().clone();
                    bytes[bytes.length - 1] ^= 1;
                    return PROTECTOR.decrypt(new PhoneProtector.Sealed(bytes, s.keyId()));
                }, null),
                Arguments.of("GRW-E84 other key → rejected", (Function<PhoneProtector.Sealed, String>) s -> OTHER_KEYS.decrypt(s), null));
    }

    static Stream<Arguments> badKeys() {
        return Stream.of(
                argumentSet("GRW-E84 HMAC key not 32 bytes", new byte[16], key('e')),
                argumentSet("GRW-E84 encryption key not 32 bytes", key('h'), new byte[31]));
    }

    @ParameterizedTest
    @MethodSource("badKeys")
    void rejectsShortKeys(byte[] hmacKey, byte[] encryptionKey) {
        assertThatThrownBy(() -> new PhoneProtector(hmacKey, encryptionKey, "k1")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "GRW-E84 base64 keys from configuration: {0}")
    @MethodSource("base64Keys")
    void fromBase64(String name, String hmacKey, boolean valid) {
        var encryptionKey = Base64.getEncoder().encodeToString(key('e'));
        if (valid) {
            assertThat(PhoneProtector.fromBase64(hmacKey, encryptionKey, "k1").hmac(A)).isEqualTo(PROTECTOR.hmac(A));
        } else {
            assertThatThrownBy(() -> PhoneProtector.fromBase64(hmacKey, encryptionKey, "k1")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    static Stream<Arguments> base64Keys() {
        return Stream.of(
                Arguments.of("valid", Base64.getEncoder().encodeToString(key('h')), true),
                Arguments.of("not base64", "%%%", false));
    }
}
