package io.github.seah1006.careconnect.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshToken(UUID id, UUID familyId, UUID userId, UUID orgId, String tokenHash,
        UUID parentId, Status status, Instant issuedAt, Instant expiresAt, Instant usedAt) {
    public enum Status { ACTIVE, USED, REVOKED }
}
