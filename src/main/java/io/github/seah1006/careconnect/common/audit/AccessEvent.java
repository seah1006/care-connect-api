package io.github.seah1006.careconnect.common.audit;

import java.util.Objects;
import java.util.UUID;

public record AccessEvent(UUID orgId, UUID actorUserId, Action action, String resourceType,
        UUID resourceId, Outcome outcome) {
    public enum Action { READ, LIST, UPDATE, DOWNLOAD }
    public enum Outcome { ALLOWED, DENIED, NOT_FOUND }

    public AccessEvent {
        Objects.requireNonNull(orgId);
        Objects.requireNonNull(actorUserId);
        Objects.requireNonNull(action);
        Objects.requireNonNull(resourceType);
        Objects.requireNonNull(outcome);
    }
}
