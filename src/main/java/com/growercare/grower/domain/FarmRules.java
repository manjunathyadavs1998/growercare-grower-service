package com.growercare.grower.domain;

import com.growercare.common.error.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Farm input rules and location precision (SPEC-GRW-02 R202, R207, D3, D9). Framework-free. */
public final class FarmRules {

    private FarmRules() { }

    public static final BigDecimal MAX_AREA_HA = new BigDecimal("100000");
    public static final int MAX_CROPS = 5;
    public static final int MAX_ACTIVE_FARMS = 50;
    private static final BigDecimal LAT = new BigDecimal("90");
    private static final BigDecimal LON = new BigDecimal("180");

    /** R202: name as for growers (1–120 characters, no control characters); returns it stripped. */
    public static String name(String raw) {
        if (!GrowerRules.isValidName(raw)) {
            throw ValidationException.of("name", "must be 1-" + GrowerRules.MAX_NAME + " characters without control characters");
        }
        return raw.strip();
    }

    /** R202: 0 < area ≤ 100000 ha, at most 2 decimals. */
    public static BigDecimal area(BigDecimal area) {
        if (area == null || area.signum() <= 0 || area.compareTo(MAX_AREA_HA) > 0 || area.stripTrailingZeros().scale() > 2) {
            throw ValidationException.of("areaHa", "must be more than 0 and at most 100000 hectares, with at most 2 decimals");
        }
        return area.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** R202: 1–5 distinct crop codes, each known (D4). */
    public static List<String> crops(List<String> crops, Set<String> known) {
        if (crops == null || crops.isEmpty() || crops.size() > MAX_CROPS) {
            throw ValidationException.of("crops", "must list 1-" + MAX_CROPS + " crops");
        }
        var seen = new HashSet<String>();
        for (int i = 0; i < crops.size(); i++) {
            var crop = crops.get(i);
            if (crop == null || !known.contains(crop)) {
                throw ValidationException.of("crops[" + i + "]", "is not a known crop code");
            }
            if (!seen.add(crop)) {
                throw ValidationException.of("crops", "lists " + crop + " more than once");
            }
        }
        return List.copyOf(crops);
    }

    /** R202: both coordinates or none, within range; stored with 6 decimals (about 0.1 m). */
    public static Point location(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null) {
            throw ValidationException.of("location", "needs both latitude and longitude");
        }
        if (latitude.abs().compareTo(LAT) > 0) {
            throw ValidationException.of("location.latitude", "must be between -90 and 90");
        }
        if (longitude.abs().compareTo(LON) > 0) {
            throw ValidationException.of("location.longitude", "must be between -180 and 180");
        }
        return new Point(latitude.setScale(6, RoundingMode.HALF_UP), longitude.setScale(6, RoundingMode.HALF_UP));
    }

    /** D3/R207: what a reader without FULL clearance sees: 1 decimal (about 11 km, district level). */
    public static Point coarse(Point exact) {
        return new Point(exact.latitude().setScale(1, RoundingMode.HALF_UP), exact.longitude().setScale(1, RoundingMode.HALF_UP));
    }

    /** R201: SHA-256 over every field (length-prefixed), so a replay must be the same request by the same caller. */
    public static byte[] requestHash(long callerUserId, long growerId, String name, BigDecimal area, List<String> crops, Point location) {
        var fields = new ArrayList<String>(List.of(Long.toString(callerUserId), Long.toString(growerId), name,
                area.stripTrailingZeros().toPlainString(), String.join(",", crops)));
        fields.add(location == null ? "-" : location.latitude().toPlainString() + "," + location.longitude().toPlainString());
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var field : fields) {
                var bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
