package io.github.seah1006.careconnect.participant;

import io.github.seah1006.careconnect.common.authz.AccessPolicy;
import io.github.seah1006.careconnect.common.authz.AccessPolicy.Facts;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import java.util.*;
import net.jqwik.api.*;
import static org.assertj.core.api.Assertions.*;

@Tag("INV-AUTHZ-003")
class AccessPolicyProperties {
    @Provide Arbitrary<UUID> groups() { return Arbitraries.integers().between(0, 12).map(n -> new UUID(0, n)); }
    @Provide Arbitrary<Set<UUID>> assignments() { return groups().set().ofMaxSize(12); }
    @Provide Arbitrary<StaffRole> roles() { return Arbitraries.of(StaffRole.values()).injectNull(0.3); }
    @Provide Arbitrary<UUID> targets() { return groups().injectNull(0.3); }

    @Property(tries = 500)
    void rulesMatchAndMissingMembershipAlwaysDenies(@ForAll("roles") StaffRole role,
            @ForAll("assignments") Set<UUID> groups, @ForAll boolean all, @ForAll("targets") UUID target) {
        boolean expected = role == StaffRole.ORG_ADMIN || (role == StaffRole.STAFF
                && (all || (target != null && groups.contains(target))));
        assertThat(AccessPolicy.permits(new Facts(role, groups, all), target)).isEqualTo(expected);
        assertThat(AccessPolicy.permits(new Facts(null, groups, all), target)).isFalse();
    }

    @Property(tries = 300)
    void addingAssignmentsOrEnablingViewAllCannotReduceAccess(@ForAll("assignments") Set<UUID> groups,
            @ForAll("assignments") Set<UUID> extra, @ForAll boolean all, @ForAll("targets") UUID target) {
        Set<UUID> expanded = new HashSet<>(groups); expanded.addAll(extra);
        boolean before = AccessPolicy.permits(new Facts(StaffRole.STAFF, groups, all), target);
        assertThat(!before || AccessPolicy.permits(new Facts(StaffRole.STAFF, expanded, all), target)).isTrue();
        assertThat(AccessPolicy.permits(new Facts(StaffRole.STAFF, groups, true), target)).isTrue();
    }
}
