package io.github.seah1006.careconnect.identity.application;

import io.github.seah1006.careconnect.identity.domain.RefreshToken;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface AuthStore {
    record User(UUID id, String passwordHash) {}

    <T> T transaction(Supplier<T> operation);
    Optional<User> findUser(String username);
    Optional<StaffRole> membership(UUID userId, UUID orgId);
    Optional<RefreshToken> findToken(String hash);
    void lockFamily(UUID familyId);
    int markUsed(UUID id, Instant now);
    RefreshToken.Status status(UUID id);
    void insert(RefreshToken token);
    void revokeFamily(UUID familyId);
}
