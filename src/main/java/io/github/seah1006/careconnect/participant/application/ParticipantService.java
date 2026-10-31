package io.github.seah1006.careconnect.participant.application;

import io.github.seah1006.careconnect.common.authz.*;
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
    private static final String CURSOR_KIND = "participants:id";

    public ParticipantService(CurrentActor currentActor, ParticipantRepository repository, CursorCodec cursors) {
        this.currentActor = currentActor;
        this.repository = repository;
        this.cursors = cursors;
    }

    public ParticipantView get(UUID id) {
        Actor actor = currentActor.require();
        var participant = repository.find(actor.orgId(), id).orElseThrow(ResourceNotFound::new);
        if (!AccessPolicy.permits(repository.facts(actor), participant.groupId())) throw new AccessDenied();
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
        if (facts.role() == null) throw new AccessDenied();
        var rows = repository.list(actor.orgId(), AccessPolicy.permits(facts, null), facts.assignedGroups(), after, size + 1);
        boolean more = rows.size() > size;
        var items = List.copyOf(more ? rows.subList(0, size) : rows);
        return new Page(items, more ? cursors.encode(new Cursor(CURSOR_KIND, items.getLast().id())) : null);
    }

    public record Page(List<ParticipantView> items, String nextCursor) {}
}
