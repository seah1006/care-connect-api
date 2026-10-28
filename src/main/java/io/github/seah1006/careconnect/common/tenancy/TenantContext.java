package io.github.seah1006.careconnect.common.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class TenantContext {
    private static final ScopedValue<UUID> CURRENT = ScopedValue.newInstance();

    private TenantContext() {}

    public static Optional<UUID> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    public static UUID require() {
        return current().orElseThrow(() -> new IllegalStateException("Tenant context is required"));
    }

    public static <T, X extends Throwable> T callAs(UUID orgId, ScopedValue.CallableOp<T, X> operation) throws X {
        return ScopedValue.where(CURRENT, Objects.requireNonNull(orgId)).call(operation);
    }
}
