package io.github.seah1006.careconnect.common.tenancy;

import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

public final class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {
    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.require();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
