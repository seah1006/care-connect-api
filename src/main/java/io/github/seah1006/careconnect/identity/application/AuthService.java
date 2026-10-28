package io.github.seah1006.careconnect.identity.application;

import io.github.seah1006.careconnect.common.security.AccessTokens;
import io.github.seah1006.careconnect.common.security.RefreshSecrets;
import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import io.github.seah1006.careconnect.identity.domain.RefreshToken;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    public record Tokens(String accessToken, String tokenType, long expiresIn, String refreshToken) {}

    private final AuthStore store;
    private final PasswordEncoder passwords;
    private final AccessTokens access;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(AuthStore store, PasswordEncoder passwords, AccessTokens access, Clock clock) {
        this.store = store;
        this.passwords = passwords;
        this.access = access;
        this.clock = clock;
        this.dummyHash = passwords.encode(RefreshSecrets.generate());
    }

    public Tokens login(UUID orgId, String username, String password) {
        return TenantContext.callAs(orgId, () -> store.transaction(() -> {
            var user = store.findUser(username);
            boolean matches = passwords.matches(password, user.map(AuthStore.User::passwordHash).orElse(dummyHash));
            if (user.isEmpty() || !matches) throw new AuthenticationFailed();
            StaffRole role = store.membership(user.get().id(), orgId).orElseThrow(AuthenticationFailed::new);
            return issue(UUID.randomUUID(), user.get().id(), orgId, role, null);
        }));
    }

    public Tokens refresh(String secret) {
        RefreshToken token = store.findToken(RefreshSecrets.hash(secret)).orElseThrow(AuthenticationFailed::new);
        // Return a failed outcome from the transaction so reuse revocation commits before the 401.
        Tokens result = TenantContext.callAs(token.orgId(), () -> store.transaction(() -> {
            store.lockFamily(token.familyId());
            if (!token.expiresAt().isAfter(clock.instant())) return null;
            if (store.markUsed(token.id(), clock.instant()) != 1) {
                if (store.status(token.id()) == RefreshToken.Status.USED) store.revokeFamily(token.familyId());
                return null;
            }
            var role = store.membership(token.userId(), token.orgId());
            if (role.isEmpty()) {
                store.revokeFamily(token.familyId());
                return null;
            }
            return issue(token.familyId(), token.userId(), token.orgId(), role.get(), token.id());
        }));
        if (result == null) throw new AuthenticationFailed();
        return result;
    }

    public void logout(String secret) {
        store.transaction(() -> {
            store.findToken(RefreshSecrets.hash(secret)).ifPresent(token -> {
                store.lockFamily(token.familyId());
                store.revokeFamily(token.familyId());
            });
            return null;
        });
    }

    private Tokens issue(UUID family, UUID user, UUID org, StaffRole role, UUID parent) {
        String secret = RefreshSecrets.generate();
        var now = clock.instant();
        store.insert(new RefreshToken(UUID.randomUUID(), family, user, org, RefreshSecrets.hash(secret), parent,
                RefreshToken.Status.ACTIVE, now, now.plus(Duration.ofDays(14)), null));
        return new Tokens(access.issue(user, org, role), "Bearer", 900, secret);
    }
}
