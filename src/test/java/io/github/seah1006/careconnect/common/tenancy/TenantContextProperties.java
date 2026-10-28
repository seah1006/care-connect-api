package io.github.seah1006.careconnect.common.tenancy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.Size;
import static org.assertj.core.api.Assertions.*;

@Tag("INV-TEN-003")
class TenantContextProperties {
    @Property(tries = 1000)
    void nestedScopesAndFailuresAlwaysUnwind(@ForAll @Size(max = 20) List<Boolean> failures,
            @ForAll long high, @ForAll long low) throws Exception {
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class);
        nest(failures, 0, new UUID(high, low));
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(() -> new TenantIdentifierResolver().resolveCurrentTenantIdentifier())
                .isInstanceOf(IllegalStateException.class);
    }

    private void nest(List<Boolean> failures, int level, UUID org) {
        if (level == failures.size()) return;
        try {
            TenantContext.callAs(org, () -> {
                assertThat(TenantContext.require()).isEqualTo(org);
                assertThat(new TenantIdentifierResolver().resolveCurrentTenantIdentifier()).isEqualTo(org);
                assertThat(new TenantIdentifierResolver().validateExistingCurrentSessions()).isTrue();
                nest(failures, level + 1, new UUID(org.getMostSignificantBits() + 1, org.getLeastSignificantBits()));
                assertThat(TenantContext.require()).isEqualTo(org);
                if (failures.get(level)) throw new IllegalArgumentException("synthetic failure");
                return null;
            });
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("synthetic failure");
        }
    }

    @Property(tries = 100)
    void unrelatedThreadNeverInheritsTenant(@ForAll long seed) throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            TenantContext.<Void, Exception>callAs(new UUID(seed, ~seed), () -> {
                assertThat(pool.submit(TenantContext::current).get()).isEmpty();
                return null;
            });
            assertThat(pool.submit(TenantContext::current).get()).isEmpty();
        }
        assertThat(TenantContext.current()).isEmpty();
    }
}
