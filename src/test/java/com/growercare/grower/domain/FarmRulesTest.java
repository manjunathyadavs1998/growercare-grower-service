package com.growercare.grower.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.common.error.ValidationException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-GRW-02 R202, R207 (GRW-E203, E208): farm input rules and location precision. */
class FarmRulesTest {

    static final Set<String> KNOWN = Set.of("GRAPE", "ONION", "SOYBEAN", "RAGI", "PADDY", "COTTON");

    @ParameterizedTest(name = "GRW-E203 area {0} valid: {1}")
    @CsvSource(textBlock = """
            # area,     valid
            0.01,       true
            2.5,        true
            100000,     true
            0,          false
            -1,         false
            100000.01,  false
            1.234,      false
            """)
    void area(String area, boolean valid) {
        if (valid) {
            assertThat(FarmRules.area(new BigDecimal(area))).isEqualByComparingTo(area);
        } else {
            assertThatThrownBy(() -> FarmRules.area(new BigDecimal(area))).isInstanceOf(ValidationException.class)
                    .satisfies(e -> assertThat(((ValidationException) e).errors().getFirst().field()).isEqualTo("areaHa"));
        }
    }

    static Stream<Arguments> crops() {
        return Stream.of(
                argumentSet("GRW-E203 no crops", List.of(), "crops"),
                argumentSet("GRW-E203 duplicate crop", List.of("GRAPE", "GRAPE"), "crops"),
                argumentSet("GRW-E203 unknown crop", List.of("FOO"), "crops[0]"),
                argumentSet("GRW-E203 unknown second crop", List.of("GRAPE", "FOO"), "crops[1]"),
                argumentSet("GRW-E203 six crops", List.of("GRAPE", "ONION", "SOYBEAN", "RAGI", "PADDY", "COTTON"), "crops"));
    }

    @ParameterizedTest
    @MethodSource("crops")
    void invalidCropsNameTheField(List<String> crops, String field) {
        assertThatThrownBy(() -> FarmRules.crops(crops, KNOWN)).isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errors().getFirst().field()).isEqualTo(field));
    }

    @ParameterizedTest(name = "GRW-E203 location {0},{1} → field {2}")
    @CsvSource(nullValues = "NULL", textBlock = """
            91,    73.8,  location.latitude
            -90.1, 73.8,  location.latitude
            18.5,  180.5, location.longitude
            NULL,  73.8,  location
            18.5,  NULL,  location
            """)
    void invalidLocationsNameTheField(String lat, String lon, String field) {
        assertThatThrownBy(() -> FarmRules.location(lat == null ? null : new BigDecimal(lat), lon == null ? null : new BigDecimal(lon)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errors().getFirst().field()).isEqualTo(field));
    }

    @ParameterizedTest(name = "GRW-E208 {0},{1} → {2},{3}")
    @CsvSource(textBlock = """
            18.520430,  73.856743,  18.5,  73.9
            -21.177600, -47.810300, -21.2, -47.8
            19.95,      73.75,      20.0,  73.8
            """)
    void readersWithoutFullClearanceSeeDistrictPrecision(String lat, String lon, String coarseLat, String coarseLon) {
        var coarse = FarmRules.coarse(FarmRules.location(new BigDecimal(lat), new BigDecimal(lon)));
        assertThat(coarse.latitude()).isEqualByComparingTo(coarseLat);
        assertThat(coarse.longitude()).isEqualByComparingTo(coarseLon);
        assertThat(coarse.toString()).doesNotContain(lat);
    }

    @ParameterizedTest(name = "GRW-E202 {0}")
    @CsvSource(textBlock = """
            same request,       Nashik plot 2, 1.5,  true
            other name,         Other,         1.5,  false
            same area written as 1.50, Nashik plot 2, 1.50, true
            """)
    void theRequestHashCoversEveryField(String name, String farmName, String area, boolean same) {
        var crops = List.of("GRAPE");
        var base = FarmRules.requestHash(1, 7, "Nashik plot 2", new BigDecimal("1.5"), crops, null);
        var other = FarmRules.requestHash(1, 7, farmName, new BigDecimal(area), crops, null);
        assertThat(Arrays.equals(base, other)).isEqualTo(same);
    }
}
