package io.github.seah1006.careconnect.common.audit;

import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class JdbcAccessLog implements AccessLog {
    private static final String REQUEST_ID = "requestId";
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public JdbcAccessLog(JdbcTemplate jdbc, Clock clock,
            @Qualifier("identityTransactionManager") PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void recordAll(List<AccessEvent> events) {
        if (events.isEmpty()) return;
        try {
            UUID requestId;
            String ip = null;
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                var request = attributes.getRequest();
                requestId = (UUID) request.getAttribute(REQUEST_ID);
                if (requestId == null) {
                    requestId = UUID.randomUUID();
                    request.setAttribute(REQUEST_ID, requestId);
                }
                ip = request.getRemoteAddr();
            } else {
                requestId = UUID.randomUUID();
            }
            UUID id = requestId;
            String clientIp = ip;
            TenantContext.callAs(events.getFirst().orgId(), () -> {
                transaction.executeWithoutResult(status -> jdbc.batchUpdate("""
                        insert into access_log(id, org_id, actor_user_id, action, resource_type, resource_id,
                            outcome, occurred_at, client_ip, request_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, events.stream().map(event -> new Object[] {
                        UUID.randomUUID(), event.orgId(), event.actorUserId(), event.action().name(),
                        event.resourceType(), event.resourceId(), event.outcome().name(),
                        Timestamp.from(clock.instant()), clientIp, id }).toList()));
                return null;
            });
        } catch (RuntimeException failure) {
            throw new DataAccessResourceFailureException("Access recording failed", failure);
        }
    }
}
