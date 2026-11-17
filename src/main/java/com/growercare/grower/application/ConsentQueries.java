package com.growercare.grower.application;

import com.growercare.common.error.NotFoundException;
import com.growercare.grower.application.port.ConsentRepository;
import com.growercare.grower.application.port.ConsentRepository.Consent;
import com.growercare.grower.application.port.GrowerRepository;
import com.growercare.grower.domain.GrowerAccess;
import com.growercare.security.RequiresPermission;
import com.growercare.security.web.CurrentPrincipal;
import com.growercare.shard.ShardRouter;
import java.util.List;
import org.springframework.stereotype.Service;

/** SPEC-GRW-03 R120: the current consent per purpose, for whoever may read the grower. */
@Service
public class ConsentQueries {

    private final GrowerRepository growers;
    private final ConsentRepository consents;
    private final GrowerAccess access;
    private final ShardRouter router;

    private final com.growercare.grower.application.port.ReferenceData ref;
    private final java.time.Clock clock;

    public ConsentQueries(GrowerRepository growers, ConsentRepository consents, GrowerAccess access, ShardRouter router,
                          com.growercare.grower.application.port.ReferenceData ref, java.time.Clock clock) {
        this.growers = growers;
        this.consents = consents;
        this.access = access;
        this.router = router;
        this.ref = ref;
        this.clock = clock;
    }

    /**
     * SPEC-GRW-06 R302/R303: the current notice per purpose, with its text, for a country and language. Not personal
     * data: any authenticated user. Nothing for that country and language is 404.
     */
    public List<com.growercare.grower.application.port.ReferenceData.Notice> notices(String countryCode, String language) {
        if (countryCode == null || !countryCode.matches("^[A-Z]{2}$") || language == null || !language.matches("^[a-z]{2}(-[A-Z]{2})?$")) {
            throw new NotFoundException("Notice");
        }
        var notices = ref.currentNotices(countryCode, language, clock.instant());
        if (notices.isEmpty()) {
            throw new NotFoundException("Notice");
        }
        return notices;
    }

    @RequiresPermission(GrowerAccess.READ)
    public List<Consent> current(long growerId) {
        var shard = router.forId(growerId).orElseThrow(() -> new NotFoundException("Grower"));
        var grower = growers.findById(shard, growerId).orElseThrow(() -> new NotFoundException("Grower"));
        access.require(CurrentPrincipal.require(), GrowerAccess.READ, grower.access());
        return consents.currentConsents(shard, growerId);
    }
}
