package com.growercare.grower.adapters.out.persistence;

import com.growercare.grower.domain.GrowerAccess;
import com.growercare.security.GcPrincipal;
import com.growercare.security.ScopeFilter;
import com.growercare.shard.ShardId;

/**
 * How the ABAC scope maps onto {@code <shard>.grower g} (SPEC-GRW-01 R1–R5; skill java-security-rbac):
 * <ul>
 *   <li>OWN — {@code g.user_id};</li>
 *   <li>ORG — preferred partner or onboarding partner;</li>
 *   <li>geography — {@code g.country_code} / {@code g.territory_id}.</li>
 * </ul>
 * BU and assignee never apply to growers: {@link GrowerAccess#growerView} clears BU restrictions and turns ASSIGNED
 * into TERRITORY before the filter runs, so the {@code bu}/{@code assignee} columns below are never referenced.
 * Queries must alias the grower table as {@code g}; every column is shard-local, so one mapping serves all shards.
 */
public final class GrowerAccessScope {

    static final ScopeFilter.Columns COLUMNS = new ScopeFilter.Columns(
            "g.country_code",
            "g.territory_id",
            "NULL",                       // unused: growers have no BU (R3)
            "g.user_id = {param}",
            "NULL",                       // unused: growers have no assignee (R3)
            "(g.preferred_partner_org_id = {param} OR g.onboarded_via_org_id = {param})");

    private GrowerAccessScope() { }

    /** {@code AND}-able predicate restricting {@code g} to growers the principal may {@code permission}. */
    public static ScopeFilter.SqlPredicate predicate(ScopeFilter filter, GcPrincipal principal, String permission, ShardId shard) {
        java.util.Objects.requireNonNull(shard, "shard");   // columns are shard-local; the caller qualifies the table
        return filter.forPermission(GrowerAccess.growerView(principal), permission, COLUMNS);
    }
}
