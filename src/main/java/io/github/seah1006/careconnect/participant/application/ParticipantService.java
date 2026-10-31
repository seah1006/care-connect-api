package io.github.seah1006.careconnect.participant.application;

import io.github.seah1006.careconnect.common.authz.*;
import io.github.seah1006.careconnect.common.audit.*;
import static io.github.seah1006.careconnect.common.audit.AccessEvent.Action.*;
import static io.github.seah1006.careconnect.common.audit.AccessEvent.Outcome.*;
import io.github.seah1006.careconnect.common.pagination.*;
import io.github.seah1006.careconnect.participant.repository.ParticipantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(transactionManager = "identityTransactionManager", readOnly = true)
public class ParticipantService {
    private final CurrentActor currentActor;
    private final ParticipantRepository repository;
    private final CursorCodec cursors;
    private final AccessLog accessLog;
    private static final String CURSOR_KIND = "participants:id";

    public ParticipantService(CurrentActor currentActor, ParticipantRepository repository, CursorCodec cursors, AccessLog accessLog) {
        this.currentActor = currentActor;
        this.repository = repository;
        this.cursors = cursors;
        this.accessLog = accessLog;
    }

    public ParticipantView get(UUID id) {
        Actor actor = currentActor.require();
        var participant = repository.find(actor.orgId(), id).orElse(null);
        if (participant == null) {
            record(actor, READ, id, NOT_FOUND);
            throw new ResourceNotFound();
        }
        if (!AccessPolicy.permits(repository.facts(actor), participant.groupId())) {
            record(actor, READ, id, DENIED);
            throw new AccessDenied();
        }
        record(actor, READ, id, ALLOWED);
        return participant;
    }

    public Page list(String cursor, int size) {
        Actor actor = currentActor.require();
        if (size < 1 || size > 100) throw new InvalidCursorException();
        UUID after = null;
        if (cursor != null && !cursor.isEmpty()) {
            var decoded = cursors.decode(cursor);
            if (!CURSOR_KIND.equals(decoded.sortKey())) throw new InvalidCursorException();
            after = decoded.id();
        }
        var facts = repository.facts(actor);
        if (facts.role() == null) {
            accessLog.recordAll(List.of(new AccessEvent(actor.orgId(), actor.userId(), LIST, "participant", null, DENIED)));
            throw new AccessDenied();
        }
        var rows = repository.list(actor.orgId(), AccessPolicy.permits(facts, null), facts.assignedGroups(), after, size + 1);
        boolean more = rows.size() > size;
        var items = List.copyOf(more ? rows.subList(0, size) : rows);
        accessLog.recordAll(items.isEmpty()
                ? List.of(new AccessEvent(actor.orgId(), actor.userId(), LIST, "participant", null, ALLOWED))
                : items.stream().map(item -> new AccessEvent(actor.orgId(), actor.userId(), LIST,
                        "participant", item.id(), ALLOWED)).toList());
        return new Page(items, more ? cursors.encode(new Cursor(CURSOR_KIND, items.getLast().id())) : null);
    }

    private void record(Actor actor, AccessEvent.Action action, UUID id, AccessEvent.Outcome outcome) {
        accessLog.record(new AccessEvent(actor.orgId(), actor.userId(), action, "participant", id, outcome));
    }

    public record Page(List<ParticipantView> items, String nextCursor) {}
}
