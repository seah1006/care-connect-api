package io.github.seah1006.careconnect.participant.application;

import java.util.UUID;

public record ParticipantView(UUID id, String displayName, UUID groupId) {}
