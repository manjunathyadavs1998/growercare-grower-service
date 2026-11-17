package com.growercare.grower.adapters.out.persistence;

import com.growercare.common.sql.NamedSqlRegistry;
import com.growercare.grower.application.port.ReferenceData;
import com.growercare.grower.domain.PhonePlan;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ReferenceData} from the {@code ref} replicas ({@code sql/postgres/grower-ref.sql}). */
@Repository
class JdbcReferenceData implements ReferenceData {

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;

    JdbcReferenceData(JdbcClient jdbc, NamedSqlRegistry sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    @Override
    public Optional<Territory> territory(long territoryId) {
        return jdbc.sql(sql.get("grower-ref.territory")).param("territoryId", territoryId)
                .query((rs, n) -> new Territory(rs.getLong("territory_id"), rs.getString("country_code"), rs.getBoolean("active")))
                .optional();
    }

    @Override
    public Optional<PhonePlan> phonePlan(String countryCode) {
        return jdbc.sql(sql.get("grower-ref.phonePlan")).param("countryCode", countryCode)
                .query((rs, n) -> new PhonePlan(rs.getString("calling_code"), rs.getString("national_pattern")))
                .optional();
    }

    @Override
    public Optional<Partner> partner(long partnerOrgId) {
        return jdbc.sql(sql.get("grower-ref.partner")).param("partnerOrgId", partnerOrgId)
                .query((rs, n) -> new Partner(rs.getLong("partner_org_id"), rs.getString("country_code"), rs.getString("status")))
                .optional();
    }

    @Override
    public Optional<String> currentNotice(String countryCode, String purpose, String language, java.time.Instant now) {
        return jdbc.sql(sql.get("grower-ref.currentNotice"))
                .param("countryCode", countryCode).param("purpose", purpose).param("language", language)
                .param("now", java.sql.Timestamp.from(now))
                .query(String.class).optional();
    }
}
