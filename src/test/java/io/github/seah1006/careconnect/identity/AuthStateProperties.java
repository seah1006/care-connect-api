package io.github.seah1006.careconnect.identity;

import io.github.seah1006.careconnect.common.security.RefreshSecrets;
import io.github.seah1006.careconnect.common.security.SecurityFixture;
import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import io.github.seah1006.careconnect.identity.application.AuthService;
import io.github.seah1006.careconnect.identity.application.AuthStore;
import io.github.seah1006.careconnect.identity.application.AuthenticationFailed;
import io.github.seah1006.careconnect.identity.domain.RefreshToken;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.*;
import static org.assertj.core.api.Assertions.*;

@Tag("INV-AUTH-001")
@Tag("INV-AUTH-002")
@Tag("INV-AUTH-003")
@Tag("INV-TEN-004")
class AuthStateProperties {
    private static final SecurityFixture SECURITY = new SecurityFixture();
    private static final String PASSWORD = "synthetic-password";
    private static final String HASH = SECURITY.passwords.encode(PASSWORD);

    @Provide Arbitrary<List<Integer>> commands() { return Arbitraries.integers().between(0, 3).list().ofMinSize(1).ofMaxSize(60); }

    @Property(tries = 1000)
    void rotationMatchesModelAfterEveryCommand(@ForAll("commands") List<Integer> commands) {
        var store = new MemoryAuthStore();
        var user = UUID.randomUUID();
        var org = UUID.randomUUID();
        store.users.put("synthetic-user", new AuthStore.User(user, HASH));
        store.memberships.put(user, Map.of(org, StaffRole.STAFF));
        var service = new AuthService(store, fastPasswords(), SECURITY.access, SECURITY.clock);
        List<String> secrets = new ArrayList<>();
        Set<UUID> closedFamilies = new HashSet<>();
        String current = null;
        for (int command : commands) {
            if (command == 0 || current == null) {
                var pair = service.login(org, "synthetic-user", PASSWORD);
                var jwt = SECURITY.decoder.decode(pair.accessToken());
                assertThat(jwt.getClaimAsString("org")).isEqualTo(org.toString());
                assertThat(jwt.getSubject()).isEqualTo(user.toString());
                current = pair.refreshToken();
                secrets.add(current);
            } else {
                String supplied = command == 2 ? secrets.getFirst() : current;
                var before = store.findToken(RefreshSecrets.hash(supplied)).orElseThrow();
                boolean shouldSucceed = command != 3 && before.status() == RefreshToken.Status.ACTIVE;
                if (command == 3) {
                    service.logout(supplied);
                    closedFamilies.add(before.familyId());
                } else if (shouldSucceed) {
                    var pair = service.refresh(supplied);
                    current = pair.refreshToken();
                    secrets.add(current);
                    assertThat(store.findToken(RefreshSecrets.hash(current)).orElseThrow().parentId()).isEqualTo(before.id());
                } else {
                    assertThatThrownBy(() -> service.refresh(supplied)).isInstanceOf(AuthenticationFailed.class);
                    if (before.status() == RefreshToken.Status.USED) closedFamilies.add(before.familyId());
                }
            }
            // TLA+: INV_AUTH_001, INV_AUTH_001_SingleActive and reuse => INV_AUTH_002.
            Map<UUID, Long> active = new HashMap<>();
            Map<UUID, Long> children = new HashMap<>();
            for (RefreshToken token : store.tokens.values()) {
                if (token.status() == RefreshToken.Status.ACTIVE) {
                    active.merge(token.familyId(), 1L, Long::sum);
                    assertThat(closedFamilies).doesNotContain(token.familyId());
                }
                if (token.parentId() != null) children.merge(token.parentId(), 1L, Long::sum);
            }
            assertThat(active.values()).allMatch(count -> count <= 1);
            assertThat(children.values()).allMatch(count -> count <= 1);
            for (String secret : secrets) {
                assertThat(store.findToken(RefreshSecrets.hash(secret)).orElseThrow().tokenHash())
                        .matches("[0-9a-f]{64}").isNotEqualTo(secret);
            }
            assertThat(TenantContext.current()).isEmpty();
        }
    }

    // State exploration uses the actual service with a cheap deterministic encoder;
    // production DelegatingPasswordEncoder is exercised separately in hashing properties.
    static org.springframework.security.crypto.password.PasswordEncoder fastPasswords() {
        return new org.springframework.security.crypto.password.PasswordEncoder() {
            @Override public String encode(CharSequence raw) { return HASH; }
            @Override public boolean matches(CharSequence raw, String encoded) {
                return PASSWORD.contentEquals(raw) && HASH.equals(encoded);
            }
        };
    }

    @Property(tries = 1000)
    void nonMemberCannotSelectOrganization(@ForAll long seed) {
        UUID member = new UUID(seed, 1), outsider = new UUID(seed, 2), user = new UUID(seed, 3);
        var store = new MemoryAuthStore();
        store.users.put("synthetic-user", new AuthStore.User(user, HASH));
        store.memberships.put(user, Map.of(member, StaffRole.ORG_ADMIN));
        var service = new AuthService(store, fastPasswords(), SECURITY.access, SECURITY.clock);
        assertThatThrownBy(() -> service.login(outsider, "synthetic-user", PASSWORD))
                .isInstanceOf(AuthenticationFailed.class);
        assertThat(store.tokens).isEmpty();
        var jwt = SECURITY.decoder.decode(service.login(member, "synthetic-user", PASSWORD).accessToken());
        assertThat(jwt.getClaimAsString("org")).isEqualTo(member.toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ORG_ADMIN");
    }
}
