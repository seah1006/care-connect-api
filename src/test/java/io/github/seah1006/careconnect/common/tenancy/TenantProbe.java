package io.github.seah1006.careconnect.common.tenancy;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity @Table(name = "tenant_probe")
public class TenantProbe implements OrgOwned {
    @Id private UUID id;
    @TenantId private UUID orgId;
    private String label;

    protected TenantProbe() {}
    public UUID organizationId() { return orgId; }
}
