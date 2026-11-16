package com.growercare.grower.support;

import com.growercare.grower.domain.GrowerAccess;
import com.growercare.shard.ShardId;
import java.util.List;

/** The SPEC-GRW-01 §2 fixture growers as seeded (db/grower/shard-dev-seed), with their access attributes. */
public final class SeedGrowers {

    private SeedGrowers() { }

    public record Seed(String name, long growerId, ShardId shard, GrowerAccess.Attributes access) { }

    public static final Seed G1 = new Seed("G1", 110165911145454593L, new ShardId("IN", 39),
            new GrowerAccess.Attributes("IN", 1002L, 1L, 501L, null));
    public static final Seed G2 = new Seed("G2", 110165911145445378L, new ShardId("IN", 30),
            new GrowerAccess.Attributes("IN", 1003L, 2L, 502L, null));
    public static final Seed G3 = new Seed("G3", 110165911145455619L, new ShardId("IN", 40),
            new GrowerAccess.Attributes("IN", 1005L, null, null, 502L));
    public static final Seed G4 = new Seed("G4", 110165911142966276L, new ShardId("BR", 41),
            new GrowerAccess.Attributes("BR", 2002L, 3L, null, null));

    public static List<Seed> all() {
        return List.of(G1, G2, G3, G4);
    }
}
