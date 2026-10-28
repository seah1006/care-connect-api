package io.github.seah1006.careconnect.identity;

import io.github.seah1006.careconnect.common.security.RefreshSecrets;
import io.github.seah1006.careconnect.common.security.SecurityFixture;
import io.github.seah1006.careconnect.identity.application.*;
import io.github.seah1006.careconnect.identity.domain.RefreshToken;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("INV-AUTH-001") @Tag("INV-AUTH-002") @Tag("INV-TEN-004")
class AuthServiceTest {
    private final SecurityFixture security = new SecurityFixture();
    private final MemoryAuthStore store = new MemoryAuthStore();
    private final UUID user = UUID.randomUUID(), org = UUID.randomUUID();
    private final AuthService service;

    AuthServiceTest() {
        store.users.put("synthetic", new AuthStore.User(user, security.passwords.encode("synthetic-password")));
        store.memberships.put(user, Map.of(org, StaffRole.STAFF));
        service = new AuthService(store, security.passwords, security.access, security.clock);
    }

    private AuthService.Tokens login() { return service.login(org, "synthetic", "synthetic-password"); }

    @Test void reuseRevokesSuccessorAndLogoutIsIdempotent() {
        var first = login();
        assertThat(first.tokenType()).isEqualTo("Bearer");
        assertThat(first.expiresIn()).isEqualTo(900);
        var initial = store.findToken(RefreshSecrets.hash(first.refreshToken())).orElseThrow();
        assertThat(Duration.between(initial.issuedAt(), initial.expiresAt())).isEqualTo(Duration.ofDays(14));
        assertThat(initial.parentId()).isNull();
        var second = service.refresh(first.refreshToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        var used = store.findToken(initial.tokenHash()).orElseThrow();
        assertThat(used.status()).isEqualTo(RefreshToken.Status.USED);
        assertThat(used.usedAt()).isNotNull();
        assertThatThrownBy(() -> service.refresh(first.refreshToken())).isInstanceOf(AuthenticationFailed.class);
        assertThat(store.tokens.values()).noneMatch(t -> t.status() == RefreshToken.Status.ACTIVE);
        assertThatThrownBy(() -> service.refresh(second.refreshToken())).isInstanceOf(AuthenticationFailed.class);
        service.logout(first.refreshToken());
        service.logout("synthetic-unknown");
    }

    @Test void logoutRevokesOnlyItsFamily() {
        var first = login();
        var other = login();
        service.logout(first.refreshToken());
        assertThatThrownBy(() -> service.refresh(first.refreshToken())).isInstanceOf(AuthenticationFailed.class);
        assertThat(service.refresh(other.refreshToken()).refreshToken()).isNotBlank();
    }

    @Test void deniesUnknownWrongPasswordAndNonMemberEqually() {
        assertThatThrownBy(() -> service.login(org, "synthetic-missing", "synthetic-password"))
                .isInstanceOf(AuthenticationFailed.class).hasMessage("Authentication failed");
        assertThatThrownBy(() -> service.login(org, "synthetic", "wrong"))
                .isInstanceOf(AuthenticationFailed.class).hasMessage("Authentication failed");
        assertThatThrownBy(() -> service.login(UUID.randomUUID(), "synthetic", "synthetic-password"))
                .isInstanceOf(AuthenticationFailed.class).hasMessage("Authentication failed");
        assertThat(store.tokens).isEmpty();
    }

    @Test void expirationBoundaryIsRejectedWithoutExchange() {
        var pair = login();
        var token = store.findToken(RefreshSecrets.hash(pair.refreshToken())).orElseThrow();
        var future = new AuthService(store, security.passwords, security.access,
                Clock.fixed(token.expiresAt(), security.clock.getZone()));
        assertThatThrownBy(() -> future.refresh(pair.refreshToken())).isInstanceOf(AuthenticationFailed.class);
        assertThat(store.status(token.id())).isEqualTo(RefreshToken.Status.ACTIVE);
        assertThatThrownBy(() -> service.refresh("synthetic-unknown")).isInstanceOf(AuthenticationFailed.class);
        assertThat(store.tokens).hasSize(1);
    }

    @Test void membershipRemovalPreventsNewAccessAndCommitsFamilyRevocation() {
        var pair = login();
        store.memberships.clear();
        assertThatThrownBy(() -> service.refresh(pair.refreshToken())).isInstanceOf(AuthenticationFailed.class);
        assertThat(store.tokens.values()).noneMatch(t -> t.status() == RefreshToken.Status.ACTIVE);
    }
}
