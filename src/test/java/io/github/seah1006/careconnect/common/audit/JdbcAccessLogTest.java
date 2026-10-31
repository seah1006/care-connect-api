package io.github.seah1006.careconnect.common.audit;

import io.github.seah1006.careconnect.common.tenancy.TenantContext;
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
            @Override public int update(String sql, Object... args) {
                assertThat(TenantContext.require()).isEqualTo(args[1]);
                rows.add(args); return 1;
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
        doThrow(new IllegalStateException("synthetic-commit-error")).when(manager).commit(status);
        assertThatThrownBy(() -> log.record(event)).isExactlyInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("Access recording failed").hasNoCause();
        var broken = new JdbcAccessLog(new JdbcTemplate() {
            @Override public int update(String sql, Object... args) { throw new IllegalStateException("synthetic-db-error"); }
        }, Clock.systemUTC(), manager);
        assertThatThrownBy(() -> broken.record(event)).isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("Access recording failed").hasNoCause();
        verify(manager).rollback(status);
    }
}
