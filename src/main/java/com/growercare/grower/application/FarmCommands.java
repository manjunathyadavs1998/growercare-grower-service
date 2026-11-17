package com.growercare.grower.application;

import com.growercare.common.error.BusinessRuleException;
import com.growercare.common.error.ConflictException;
import com.growercare.common.error.ErrorCode;
import com.growercare.common.error.NotFoundException;
import com.growercare.common.error.StaleVersionException;
import com.growercare.common.web.TraceIds;
import com.growercare.grower.application.port.FarmRepository;
import com.growercare.grower.application.port.FarmRepository.FarmRow;
import com.growercare.grower.application.port.GrowerRepository;
import com.growercare.grower.application.port.ReferenceData;
import com.growercare.grower.domain.FarmRecord;
import com.growercare.grower.domain.FarmRules;
import com.growercare.grower.domain.GrowerAccess;
import com.growercare.grower.domain.Point;
import com.growercare.outbox.OutboxWriter;
import com.growercare.security.RequiresPermission;
import com.growercare.security.web.CurrentPrincipal;
import com.growercare.shard.ShardId;
import com.growercare.shard.ShardRouter;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Farm write use cases: SPEC-GRW-02 R200–R212. */
@Service
public class FarmCommands {

    /** What a create request carries (validated here). */
    public record NewFarm(String name, BigDecimal areaHa, List<String> crops, BigDecimal latitude, BigDecimal longitude,
                          boolean hasLocation) {
        @Override
        public String toString() {
            return "NewFarm[crops=%s]".formatted(crops);
        }
    }

    /** A merge patch: null = unchanged; {@code locationInPatch} with null coordinates clears the location. */
    public record FarmChanges(String name, BigDecimal areaHa, List<String> crops, boolean locationInPatch,
                              BigDecimal latitude, BigDecimal longitude) {
        @Override
        public String toString() {
            return "FarmChanges[crops=%s, locationInPatch=%s]".formatted(crops, locationInPatch);
        }
    }

    public record CreateResult(FarmRecord farm, boolean replayed) { }

    private final GrowerRepository growers;
    private final FarmRepository farms;
    private final ReferenceData ref;
    private final GrowerAccess access;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final ShardRouter router;

    public FarmCommands(GrowerRepository growers, FarmRepository farms, ReferenceData ref, GrowerAccess access,
                        OutboxWriter outbox, TransactionTemplate tx, ShardRouter router) {
        this.growers = growers;
        this.farms = farms;
        this.ref = ref;
        this.access = access;
        this.outbox = outbox;
        this.tx = tx;
        this.router = router;
    }

    @RequiresPermission(GrowerAccess.UPDATE)
    public CreateResult create(long growerId, NewFarm cmd, UUID idempotencyKey) {
        var principal = CurrentPrincipal.require();
        var shard = writableGrower(growerId);

        var name = FarmRules.name(cmd.name());
        var area = FarmRules.area(cmd.areaHa());
        var crops = FarmRules.crops(cmd.crops(), knownCrops(cmd.crops()));
        Point location = cmd.hasLocation() ? FarmRules.location(cmd.latitude(), cmd.longitude()) : null;
        var hash = FarmRules.requestHash(principal.userId(), growerId, name, area, crops, location);

        var previous = farms.findIdempotency(shard, growerId, idempotencyKey);
        if (previous.isPresent()) {
            return replay(shard, growerId, previous.get(), principal.userId(), hash);
        }
        var row = new FarmRow(name, area, crops, location);
        Long farmId = tx.execute(status -> {
            var growerStatus = farms.lockGrowerStatus(shard, growerId).orElseThrow(() -> new NotFoundException("Grower"));
            requireNotInactive(growerStatus);                                                       // R204
            if (farms.countActive(shard, growerId) >= FarmRules.MAX_ACTIVE_FARMS) {               // R203
                throw new BusinessRuleException("grower.farm-limit", "A grower can have at most " + FarmRules.MAX_ACTIVE_FARMS + " active farms");
            }
            long id = farms.insert(shard, growerId, row);
            if (!farms.insertIdempotency(shard, growerId, idempotencyKey, principal.userId(), hash, id)) {
                status.setRollbackOnly();                                                           // a concurrent retry won
                return null;
            }
            event(shard, "grower.farm.created", id, growerId, 0);
            return id;
        });
        if (farmId == null) {
            return replay(shard, growerId, farms.findIdempotency(shard, growerId, idempotencyKey).orElseThrow(), principal.userId(), hash);
        }
        return new CreateResult(farms.find(shard, growerId, farmId).orElseThrow(), false);
    }

    @RequiresPermission(GrowerAccess.UPDATE)
    public FarmRecord update(long growerId, long farmId, int expectedVersion, FarmChanges changes) {
        var shard = writableGrower(growerId);
        var current = farms.find(shard, growerId, farmId).orElseThrow(() -> new NotFoundException("Grower"));
        if (current.archived()) {
            throw new BusinessRuleException("grower.farm-archived", "An archived farm cannot be changed");      // R211
        }
        var name = changes.name() == null ? current.name() : FarmRules.name(changes.name());
        var area = changes.areaHa() == null ? current.areaHa() : FarmRules.area(changes.areaHa());
        var crops = changes.crops() == null ? current.crops() : FarmRules.crops(changes.crops(), knownCrops(changes.crops()));
        Point location = current.location();
        if (changes.locationInPatch()) {
            location = changes.latitude() == null && changes.longitude() == null ? null
                    : FarmRules.location(changes.latitude(), changes.longitude());
        }
        boolean changed = !name.equals(current.name()) || area.compareTo(current.areaHa()) != 0 || !crops.equals(current.crops())
                || !samePoint(location, current.location());
        if (!changed) {
            if (current.version() != expectedVersion) {
                throw new StaleVersionException("Farm", expectedVersion);
            }
            return current;                                                                         // R208: no change, no event
        }
        var row = new FarmRow(name, area, crops, location);
        return tx.execute(status -> {
            int version = farms.update(shard, growerId, farmId, expectedVersion, row)
                    .orElseThrow(() -> new StaleVersionException("Farm", expectedVersion));
            event(shard, "grower.farm.updated", farmId, growerId, version);
            return farms.find(shard, growerId, farmId).orElseThrow();
        });
    }

    @RequiresPermission(GrowerAccess.UPDATE)
    public FarmRecord archive(long growerId, long farmId, int expectedVersion) {
        var shard = writableGrower(growerId);
        var current = farms.find(shard, growerId, farmId).orElseThrow(() -> new NotFoundException("Grower"));
        if (current.archived()) {
            return current;                                                                         // R210: idempotent
        }
        return tx.execute(status -> {
            int version = farms.archive(shard, growerId, farmId, expectedVersion)
                    .orElseThrow(() -> new StaleVersionException("Farm", expectedVersion));
            event(shard, "grower.farm.archived", farmId, growerId, version);
            return farms.find(shard, growerId, farmId).orElseThrow();
        });
    }

    /** Routing + the caller's UPDATE scope on the grower (404 otherwise, R212). */
    private ShardId writableGrower(long growerId) {
        var shard = router.forId(growerId).orElseThrow(() -> new NotFoundException("Grower"));
        var grower = growers.findById(shard, growerId).orElseThrow(() -> new NotFoundException("Grower"));
        access.require(CurrentPrincipal.require(), GrowerAccess.UPDATE, grower.access());
        return shard;
    }

    private java.util.Set<String> knownCrops(List<String> crops) {
        return crops == null ? java.util.Set.of() : ref.knownCrops(crops);
    }

    private static void requireNotInactive(String status) {
        if ("INACTIVE".equals(status)) {
            throw new BusinessRuleException("grower.not-active", "Farms cannot be added to an inactive grower");
        }
    }

    private CreateResult replay(ShardId shard, long growerId, FarmRepository.IdempotencyRecord previous, long userId, byte[] hash) {
        if (previous.requestedByUserId() != userId || !Arrays.equals(previous.requestHash(), hash)) {
            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was already used for a different request");
        }
        return new CreateResult(farms.find(shard, growerId, previous.farmId()).orElseThrow(), true);
    }

    private static boolean samePoint(Point a, Point b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.latitude().compareTo(b.latitude()) == 0 && a.longitude().compareTo(b.longitude()) == 0;
    }

    /** D7: ids and version only; consumers fetch the farm. */
    private void event(ShardId shard, String type, long farmId, long growerId, int version) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("farmId", Long.toString(farmId));
        payload.put("growerId", Long.toString(growerId));
        payload.put("version", version);
        outbox.append(shard.schemaName() + ".outbox", new OutboxWriter.Event("farm", Long.toString(farmId), type, payload),
                TraceIds.currentOrNull());
    }
}
