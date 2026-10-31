package io.github.seah1006.careconnect.participant.domain;

import io.github.seah1006.careconnect.common.tenancy.OrgOwned;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
public class Participant implements OrgOwned {
    @Id private UUID id;
    @TenantId private UUID orgId;
    private UUID groupId;
    private String displayName;

    protected Participant() {}
}
