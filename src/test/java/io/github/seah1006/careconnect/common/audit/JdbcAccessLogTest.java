package io.github.seah1006.careconnect.common.audit;

import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import io.github.seah1006.careconnect.common.authz.AccessPolicy;
import io.github.seah1006.careconnect.common.authz.Actor;
import io.github.seah1006.careconnect.common.authz.CurrentActor;
import io.github.seah1006.careconnect.common.pagination.CursorCodec;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import io.github.seah1006.careconnect.participant.application.ParticipantService;
import io.github.seah1006.careconnect.participant.application.ParticipantView;
import io.github.seah1006.careconnect.participant.repository.ParticipantRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.*;
import org.springframework.web.context.request.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("INV-AUDIT-001") @Tag("INV-AUDIT-003") @Tag("INV-TEN-002")
class JdbcAccessLogTest {
    @Test void injectedTimeRequestIdentityRemoteAddressAndTenantAreUsed() {
        var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenAnswer(call -> {
            assertThat(((TransactionDefinition) call.getArgument(0)).getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(TenantContext.require()).isNotNull();
            return status;
        });
        List<Object[]> rows = new ArrayList<>();
        var jdbc = new JdbcTemplate() {
            @Override public int[] batchUpdate(String sql, List<? extends Object[]> batch) {
                for (Object[] args : batch) assertThat(TenantContext.require()).isEqualTo(args[1]);
                rows.addAll(batch); return new int[batch.size()];
            }
        };
        var time = Instant.parse("2026-10-31T00:00:00Z");
        var log = new JdbcAccessLog(jdbc, Clock.fixed(time, ZoneOffset.UTC), manager);
        var event = new AccessEvent(UUID.randomUUID(), UUID.randomUUID(), AccessEvent.Action.READ,
                "participant", UUID.randomUUID(), AccessEvent.Outcome.ALLOWED);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10"); request.addHeader("X-Forwarded-For", "198.51.100.20");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            log.record(event); log.record(event);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)[0]).isNotEqualTo(rows.get(1)[0]);
            assertThat(rows.get(0)).containsExactly(rows.get(0)[0], event.orgId(), event.actorUserId(), "READ",
                    "participant", event.resourceId(), "ALLOWED", Timestamp.from(time), "192.0.2.10", rows.get(0)[9]);
            assertThat(rows.get(0)[9]).isEqualTo(rows.get(1)[9]).isInstanceOf(UUID.class);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            log.record(event);
            assertThat(rows.get(2)[9]).isNotEqualTo(rows.get(0)[9]);
        } finally { RequestContextHolder.resetRequestAttributes(); }
        log.record(event);
        assertThat(rows.getLast()[8]).isNull();
        assertThat(rows.getLast()[9]).isNotEqualTo(rows.get(0)[9]);
        verify(manager, times(4)).commit(status);
        assertThat(TenantContext.current()).isEmpty();
        var commitFailure = new IllegalStateException("synthetic-commit-error");
        doThrow(commitFailure).when(manager).commit(status);
        assertThatThrownBy(() -> log.record(event)).isExactlyInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("Access recording failed").hasCauseReference(commitFailure);
        var dbFailure = new IllegalStateException("synthetic-db-error");
        var broken = new JdbcAccessLog(new JdbcTemplate() {
            @Override public int[] batchUpdate(String sql, List<? extends Object[]> batch) { throw dbFailure; }
        }, Clock.systemUTC(), manager);
        assertThatThrownBy(() -> broken.record(event)).isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("Access recording failed").hasCauseReference(dbFailure);
        verify(manager).rollback(status);
    }

    @Test void listStartsOneNewTransactionAndOneBatchForTheEntirePage() {
        var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenAnswer(call -> {
            assertThat(((TransactionDefinition) call.getArgument(0)).getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return status;
        });
        var jdbc = mock(JdbcTemplate.class);
        var log = new JdbcAccessLog(jdbc, Clock.systemUTC(), manager);
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID());
        var current = mock(CurrentActor.class);
        when(current.require()).thenReturn(actor);
        var repository = mock(ParticipantRepository.class);
        when(repository.facts(actor)).thenReturn(new AccessPolicy.Facts(StaffRole.ORG_ADMIN, Set.of(), false));
        var items = List.of(
                new ParticipantView(UUID.randomUUID(), "synthetic-a", null),
                new ParticipantView(UUID.randomUUID(), "synthetic-b", null));
        when(repository.list(actor.orgId(), true, Set.of(), null, 21)).thenReturn(items);
        var service = new ParticipantService(current, repository, new CursorCodec(new byte[32]), log);
        assertThat(service.list(null, 20).items()).isEqualTo(items);
        verify(manager, times(1)).getTransaction(any());
        verify(manager, times(1)).commit(status);
        verify(jdbc, times(1)).batchUpdate(contains("insert into access_log"), org.mockito.ArgumentMatchers.<List<Object[]>>argThat(batch -> {
            assertThat(batch).hasSize(2);
            assertThat(batch.stream().map(row -> row[5])).containsExactlyElementsOf(items.stream().map(item -> item.id()).toList());
            assertThat(batch.stream().map(row -> row[9]).distinct()).hasSize(1);
            return true;
        }));
        verifyNoMoreInteractions(manager, jdbc);
    }

    @Test void emptyBatchDoesNotStartATransaction() {
        var manager = mock(PlatformTransactionManager.class);
        var jdbc = mock(JdbcTemplate.class);
        new JdbcAccessLog(jdbc, Clock.systemUTC(), manager).recordAll(List.of());
        verifyNoInteractions(manager, jdbc);
    }
}
