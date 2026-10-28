package io.github.seah1006.careconnect.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

@Entity
public class Organization {
    @Id private UUID id;
    private String name;

    protected Organization() {}
}
