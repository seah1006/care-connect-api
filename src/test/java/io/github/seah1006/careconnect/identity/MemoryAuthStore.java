package io.github.seah1006.careconnect.identity;

import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import io.github.seah1006.careconnect.identity.application.AuthStore;
import io.github.seah1006.careconnect.identity.domain.RefreshToken;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public class MemoryAuthStore implements AuthStore {
    public final Map<String, User> users = new HashMap<>();
    public final Map<UUID, Map<UUID, StaffRole>> memberships = new HashMap<>();
    public final Map<UUID, RefreshToken> tokens = new LinkedHashMap<>();

    @Override public synchronized <T> T transaction(Supplier<T> operation) { return operation.get(); }
    @Override public Optional<User> findUser(String username) { return Optional.ofNullable(users.get(username)); }
    @Override public Optional<StaffRole> membership(UUID user, UUID org) {
        if (!TenantContext.require().equals(org)) throw new IllegalStateException("Wrong tenant");
        return Optional.ofNullable(memberships.getOrDefault(user, Map.of()).get(org));
    }
    @Override public Optional<RefreshToken> findToken(String hash) {
        return tokens.values().stream().filter(t -> t.tokenHash().equals(hash)).findFirst();
    }
    @Override public void lockFamily(UUID family) {}
    @Override public int markUsed(UUID id, Instant now) {
        RefreshToken token = tokens.get(id);
        if (token.status() != RefreshToken.Status.ACTIVE) return 0;
        tokens.put(id, withStatus(token, RefreshToken.Status.USED, now));
        return 1;
    }
    @Override public RefreshToken.Status status(UUID id) { return tokens.get(id).status(); }
    @Override public void insert(RefreshToken token) { tokens.put(token.id(), token); }
    @Override public void revokeFamily(UUID family) {
        tokens.replaceAll((id, token) -> token.familyId().equals(family) && token.status() == RefreshToken.Status.ACTIVE
                ? withStatus(token, RefreshToken.Status.REVOKED, token.usedAt()) : token);
    }
    public static RefreshToken withStatus(RefreshToken t, RefreshToken.Status status, Instant used) {
        return new RefreshToken(t.id(), t.familyId(), t.userId(), t.orgId(), t.tokenHash(), t.parentId(), status,
                t.issuedAt(), t.expiresAt(), used);
    }
}
