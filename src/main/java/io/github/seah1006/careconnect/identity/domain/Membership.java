package io.github.seah1006.careconnect.identity.domain;

import io.github.seah1006.careconnect.common.tenancy.OrgOwned;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
public class Membership implements OrgOwned {
    @Id private UUID id;
    @TenantId private UUID orgId;
    private UUID userId;
    @Enumerated(EnumType.STRING) private StaffRole role;

    protected Membership() {}
}
