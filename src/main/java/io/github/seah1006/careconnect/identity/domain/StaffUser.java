package io.github.seah1006.careconnect.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "staff_user")
public class StaffUser {
    @Id private UUID id;
    private String username;
    private String passwordHash;
    private Instant createdAt;

    protected StaffUser() {}
}
