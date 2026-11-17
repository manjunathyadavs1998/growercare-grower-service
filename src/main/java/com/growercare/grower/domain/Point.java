package com.growercare.grower.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** A WGS 84 point (SPEC-GRW-02 D2). Exact location: personal data under PRV-5, never logged. */
public record Point(BigDecimal latitude, BigDecimal longitude) {

    public Point {
        Objects.requireNonNull(latitude, "latitude");
        Objects.requireNonNull(longitude, "longitude");
    }

    @Override
    public String toString() {
        return "Point[redacted]";
    }
}
