package io.github.seah1006.careconnect.participant;

import io.github.seah1006.careconnect.common.audit.*;
import io.github.seah1006.careconnect.common.authz.*;
import io.github.seah1006.careconnect.common.pagination.CursorCodec;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import io.github.seah1006.careconnect.participant.application.*;
import io.github.seah1006.careconnect.participant.repository.ParticipantRepository;
import java.util.*;
import net.jqwik.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.seah1006.careconnect.common.audit.AccessEvent.Action.*;
import static io.github.seah1006.careconnect.common.audit.AccessEvent.Outcome.*;

@Tag("INV-AUDIT-001") @Tag("INV-AUDIT-003")
@Tag("INV-AUTHZ-002") @Tag("INV-AUTHZ-003") @Tag("INV-AUTHZ-005")
class ParticipantAuditProperties {
    static class MemoryRepository extends ParticipantRepository {
        final Map<UUID, ParticipantView> participants = new HashMap<>();
        AccessPolicy.Facts facts;
        MemoryRepository() { super(new JdbcTemplate()); }
        @Override public Optional<ParticipantView> find(UUID org, UUID id) { return Optional.ofNullable(participants.get(id)); }
        @Override public AccessPolicy.Facts facts(Actor actor) { return facts; }
        @Override public List<ParticipantView> list(UUID org, boolean all, Set<UUID> groups, UUID after, int limit) {
            return participants.values().stream().filter(p -> all || p.groupId() != null && groups.contains(p.groupId()))
                    .sorted(Comparator.comparing(p -> p.id().toString())).limit(limit).toList();
        }
    }
    static class MemoryLog implements AccessLog {
        final List<AccessEvent> events = new ArrayList<>();
        int remaining = Integer.MAX_VALUE;
        int batches;
        @Override public void recordAll(List<AccessEvent> batch) {
            batches++;
            for (var event : batch) {
                if (remaining-- <= 0) throw new DataAccessResourceFailureException("synthetic-internal-error");
            }
            events.addAll(batch);
        }
    }

    @Property(tries = 1000)
    void randomFactChangesAlwaysRecordTheExactResult(@ForAll long seed) {
        Random random = new Random(seed);
        Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID());
        UUID group = UUID.randomUUID(), missing = UUID.randomUUID();
        var repository = new MemoryRepository();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            repository.participants.put(id, new ParticipantView(id, "synthetic-" + i, i % 2 == 0 ? group : null));
        }
        var log = new MemoryLog();
        var service = new ParticipantService(new CurrentActor() { @Override public Actor require() { return actor; } },
                repository, new CursorCodec(new byte[32]), log);
        for (int step = 0; step < 20; step++) {
            int role = random.nextInt(3);
            repository.facts = new AccessPolicy.Facts(role == 0 ? null : role == 1 ? StaffRole.STAFF : StaffRole.ORG_ADMIN,
                    random.nextBoolean() ? Set.of(group) : Set.of(), random.nextBoolean());
            log.events.clear();
            UUID id = random.nextBoolean() ? missing : new ArrayList<>(repository.participants.keySet()).get(random.nextInt(5));
            var participant = repository.participants.get(id);
            AccessEvent.Outcome outcome;
            if (participant == null) {
                outcome = NOT_FOUND;
                assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResourceNotFound.class);
            } else if (!AccessPolicy.permits(repository.facts, participant.groupId())) {
                outcome = DENIED;
                assertThatThrownBy(() -> service.get(id)).isInstanceOf(AccessDenied.class);
            } else {
                outcome = ALLOWED;
                assertThat(service.get(id)).isEqualTo(participant);
            }
            assertThat(log.events).containsExactly(new AccessEvent(actor.orgId(), actor.userId(), READ, "participant", id, outcome));
            log.events.clear();
            log.batches = 0;
            if (role == 0) {
                assertThatThrownBy(() -> service.list(null, 3)).isInstanceOf(AccessDenied.class);
                assertThat(log.events).containsExactly(new AccessEvent(actor.orgId(), actor.userId(), LIST, "participant", null, DENIED));
            } else {
                var expected = repository.participants.values().stream()
                        .filter(p -> AccessPolicy.permits(repository.facts, p.groupId()))
                        .sorted(Comparator.comparing(p -> p.id().toString())).limit(3).toList();
                var page = service.list(null, 3);
                assertThat(page.items()).isEqualTo(expected);
                var events = expected.stream().map(p -> new AccessEvent(actor.orgId(), actor.userId(), LIST, "participant", p.id(), ALLOWED)).toList();
                assertThat(log.events).containsExactlyElementsOf(events.isEmpty()
                        ? List.of(new AccessEvent(actor.orgId(), actor.userId(), LIST, "participant", null, ALLOWED)) : events);
            }
            assertThat(log.batches).isEqualTo(1);
            log.events.clear();
            log.remaining = 0;
            assertThatThrownBy(() -> service.get(id)).isInstanceOf(DataAccessResourceFailureException.class);
            assertThatThrownBy(() -> service.list(null, 3)).isInstanceOf(DataAccessResourceFailureException.class);
            if (role != 0 && AccessPolicy.permits(repository.facts, null)) {
                log.remaining = 1;
                assertThatThrownBy(() -> service.list(null, 3)).isInstanceOf(DataAccessResourceFailureException.class);
            }
            assertThat(log.events).isEmpty();
            log.remaining = Integer.MAX_VALUE;
        }
    }
}
