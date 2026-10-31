package io.github.seah1006.careconnect.common.authz;

import io.github.seah1006.careconnect.identity.domain.StaffRole;
import java.util.Set;
import java.util.UUID;

public final class AccessPolicy {
    private AccessPolicy() {}

    public record Facts(StaffRole role, Set<UUID> assignedGroups, boolean staffViewAll) {}

    public static boolean permits(Facts facts, UUID targetGroup) {
        if (facts.role() == null) return false;
        if (facts.role() == StaffRole.ORG_ADMIN) return true;
        return facts.staffViewAll() || (targetGroup != null && facts.assignedGroups().contains(targetGroup));
    }
}
