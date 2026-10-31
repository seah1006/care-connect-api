package io.github.seah1006.careconnect.common.authz;

import java.util.UUID;

public record Actor(UUID userId, UUID orgId) {}
