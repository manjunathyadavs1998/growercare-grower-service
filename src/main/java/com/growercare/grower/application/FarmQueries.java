package com.growercare.grower.application;

import com.growercare.common.error.NotFoundException;
import com.growercare.grower.application.port.FarmRepository;
import com.growercare.grower.application.port.GrowerRepository;
import com.growercare.grower.domain.FarmRecord;
import com.growercare.grower.domain.GrowerAccess;
import com.growercare.security.RequiresPermission;
import com.growercare.security.web.CurrentPrincipal;
import com.growercare.shard.ShardId;
import com.growercare.shard.ShardRouter;
import java.util.List;
import org.springframework.stereotype.Service;

/** Farm reads: SPEC-GRW-02 R205, R206, R213. Location precision is applied by the web adapter (R207). */
@Service
public class FarmQueries {

    private final GrowerRepository growers;
    private final FarmRepository farms;
    private final GrowerAccess access;
    private final ShardRouter router;

    public FarmQueries(GrowerRepository growers, FarmRepository farms, GrowerAccess access, ShardRouter router) {
        this.growers = growers;
        this.farms = farms;
        this.access = access;
        this.router = router;
    }

    @RequiresPermission(GrowerAccess.READ)
    public List<FarmRecord> list(long growerId, boolean includeArchived) {
        return farms.list(readableGrower(growerId), growerId, includeArchived);
    }

    @RequiresPermission(GrowerAccess.READ)
    public FarmRecord get(long growerId, long farmId) {
        return farms.find(readableGrower(growerId), growerId, farmId).orElseThrow(() -> new NotFoundException("Grower"));
    }

    /** R213: for allow-listed service clients (gc-security restricts /internal/**); no end user, so no grower scope. */
    public FarmRecord replica(long growerId, long farmId) {
        return router.forId(growerId)
                .flatMap(shard -> farms.find(shard, growerId, farmId))
                .orElseThrow(() -> new NotFoundException("Farm"));
    }

    private ShardId readableGrower(long growerId) {
        var shard = router.forId(growerId).orElseThrow(() -> new NotFoundException("Grower"));
        var grower = growers.findById(shard, growerId).orElseThrow(() -> new NotFoundException("Grower"));
        access.require(CurrentPrincipal.require(), GrowerAccess.READ, grower.access());
        return shard;
    }
}
