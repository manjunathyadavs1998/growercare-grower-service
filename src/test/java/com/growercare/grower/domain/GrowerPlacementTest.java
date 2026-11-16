package com.growercare.grower.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** SPEC-GRW-01 D1 / GRW-E32 (unit part): a grower's shard is unsigned(first 8 bytes of its phone HMAC) mod 64. */
class GrowerPlacementTest {

    static Stream<Arguments> hmacs() {
        return Stream.of(
                // the dev-seed growers (db/grower/dev-seed), HMAC with the dev key
                argumentSet("GRW-E32 G1", "9c1fd28b1659c0e76e881c2f67b3d46f4c6d0ec8b4ad63cf71a478e1f80fada5", 39),
                argumentSet("GRW-E32 G2", "0e51d8ac07189a1eebdb9c057b4a32593d272c75f624850d8dbe81f278eaed3c", 30),
                argumentSet("GRW-E32 G3", "6752a1f363c58ae8ada7f9809ed57a7d35a64e3241d07dfef7ea164939820d7b", 40),
                argumentSet("GRW-E32 G4", "585e5ab1ed1b18e93ee25888f65e56422a0454288732edf2257e0528a70f2bb6", 41),
                argumentSet("GRW-E32 all ones (unsigned, not negative)", "ff".repeat(32), 63),
                argumentSet("GRW-E32 all zeros", "00".repeat(32), 0));
    }

    @ParameterizedTest
    @MethodSource("hmacs")
    void shardOfHmac(String hmacHex, int shard) {
        assertThat(GrowerPlacement.shardNo(HexFormat.of().parseHex(hmacHex))).isEqualTo(shard);
    }

    @ParameterizedTest(name = "GRW-E32 rejects a {0}-byte HMAC")
    @ValueSource(ints = {0, 7, 31, 33})
    void rejectsMalformedHmac(int length) {
        assertThatThrownBy(() -> GrowerPlacement.shardNo(new byte[length])).isInstanceOf(IllegalArgumentException.class);
    }
}
