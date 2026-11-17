package com.growercare.grower.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A stored farm (SPEC-GRW-02). The point is exact here; readers without FULL clearance get {@link FarmRules#coarse}. */
public record FarmRecord(long farmId, long growerId, String name, BigDecimal areaHa, List<String> crops, Point location,
                         String status, int version, Instant createdAt, Instant updatedAt) {

    public FarmRecord {
        crops = List.copyOf(crops);
    }

    public boolean archived() {
        return "ARCHIVED".equals(status);
    }
}
