package com.growercare.grower.adapters.out.persistence;

import com.growercare.common.sql.NamedSqlRegistry;
import com.growercare.grower.application.port.FarmRepository;
import com.growercare.grower.domain.FarmRecord;
import com.growercare.grower.domain.Point;
import com.growercare.shard.ShardId;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link FarmRepository} with named SQL ({@code sql/postgres/grower-farm.sql}). */
@Repository
class JdbcFarmRepository implements FarmRepository {

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;

    JdbcFarmRepository(JdbcClient jdbc, NamedSqlRegistry sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    private String statement(String name, ShardId shard) {
        return sql.render("grower-farm." + name, shard.schemaName());
    }

    @Override
    public Optional<FarmRecord> find(ShardId shard, long growerId, long farmId) {
        return jdbc.sql(statement("findFarm", shard)).param("farmId", farmId).param("growerId", growerId)
                .query(JdbcFarmRepository::farm).optional();
    }

    @Override
    public List<FarmRecord> list(ShardId shard, long growerId, boolean includeArchived) {
        return jdbc.sql(statement("listFarms", shard)).param("growerId", growerId).param("includeArchived", includeArchived)
                .query(JdbcFarmRepository::farm).list();
    }

    @Override
    public Optional<String> lockGrowerStatus(ShardId shard, long growerId) {
        return jdbc.sql(statement("lockGrowerStatus", shard)).param("growerId", growerId).query(String.class).optional();
    }

    @Override
    public int countActive(ShardId shard, long growerId) {
        return jdbc.sql(statement("countActive", shard)).param("growerId", growerId).query(Integer.class).single();
    }

    @Override
    public long insert(ShardId shard, long growerId, FarmRow row) {
        return withRow(jdbc.sql(statement("insertFarm", shard)).param("growerId", growerId), row).query(Long.class).single();
    }

    @Override
    public Optional<Integer> update(ShardId shard, long growerId, long farmId, int expectedVersion, FarmRow row) {
        return withRow(jdbc.sql(statement("updateFarm", shard)), row)
                .param("farmId", farmId).param("growerId", growerId).param("expectedVersion", expectedVersion)
                .query(Integer.class).optional();
    }

    @Override
    public Optional<Integer> archive(ShardId shard, long growerId, long farmId, int expectedVersion) {
        return jdbc.sql(statement("archiveFarm", shard))
                .param("farmId", farmId).param("growerId", growerId).param("expectedVersion", expectedVersion)
                .query(Integer.class).optional();
    }

    @Override
    public Optional<IdempotencyRecord> findIdempotency(ShardId shard, long growerId, UUID key) {
        return jdbc.sql(statement("findIdempotency", shard)).param("growerId", growerId).param("key", key)
                .query((rs, n) -> new IdempotencyRecord(rs.getLong("requested_by_user_id"), rs.getBytes("request_hash"), rs.getLong("farm_id")))
                .optional();
    }

    @Override
    public boolean insertIdempotency(ShardId shard, long growerId, UUID key, long userId, byte[] hash, long farmId) {
        return jdbc.sql(statement("insertIdempotency", shard))
                .param("growerId", growerId).param("key", key).param("userId", userId).param("hash", hash).param("farmId", farmId)
                .query(Long.class).optional().isPresent();
    }

    private static JdbcClient.StatementSpec withRow(JdbcClient.StatementSpec spec, FarmRow row) {
        var location = row.location();
        return spec.param("name", row.name()).param("areaHa", row.areaHa())
                .param("crops", row.crops().toArray(String[]::new))
                .param("latitude", location == null ? null : location.latitude(), java.sql.Types.NUMERIC)
                .param("longitude", location == null ? null : location.longitude(), java.sql.Types.NUMERIC);
    }

    private static FarmRecord farm(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal lat = rs.getBigDecimal("latitude");
        BigDecimal lon = rs.getBigDecimal("longitude");
        return new FarmRecord(rs.getLong("farm_id"), rs.getLong("grower_id"), rs.getString("name"), rs.getBigDecimal("area_ha"),
                List.of((String[]) rs.getArray("crops").getArray()), lat == null ? null : new Point(lat, lon),
                rs.getString("status"), rs.getInt("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
