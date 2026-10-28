package io.github.seah1006.careconnect.identity;

import io.github.seah1006.careconnect.identity.repository.JdbcAuthStore;
import io.github.seah1006.careconnect.identity.domain.RefreshToken;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("INV-AUTH-001") @Tag("INV-AUTH-002") @Tag("INV-AUTH-003")
class JdbcAuthStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final JdbcAuthStore store = new JdbcAuthStore(jdbc, manager);
    private final UUID id = UUID.randomUUID(), family = UUID.randomUUID(), user = UUID.randomUUID(), org = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-28T00:00:00Z");

    @Test void transactionCommitsFailedOutcomeButRollsBackUnexpectedExceptions() {
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        assertThat(store.transaction(() -> "result")).isEqualTo("result");
        assertThat(store.transaction(() -> (String) null)).isNull();
        verify(manager, times(2)).commit(status);
        var failure = new IllegalStateException("synthetic");
        assertThatThrownBy(() -> store.transaction(() -> { throw failure; })).isSameAs(failure);
        verify(manager).rollback(status);
    }

    @Test void compareAndSetAndFamilyOperationsReachDatabase() {
        String cas = "UPDATE refresh_token SET status='USED', used_at=? WHERE id=? AND status='ACTIVE'";
        when(jdbc.update(cas, Timestamp.from(now), id)).thenReturn(1, 0);
        assertThat(store.markUsed(id, now)).isEqualTo(1);
        assertThat(store.markUsed(id, now)).isZero();
        verify(jdbc, times(2)).update(cas, Timestamp.from(now), id);
        when(jdbc.queryForObject("select status from refresh_token where id = ?", String.class, id)).thenReturn("USED");
        assertThat(store.status(id)).isEqualTo(RefreshToken.Status.USED);
        store.lockFamily(family);
        verify(jdbc).query(eq("select pg_advisory_xact_lock(?)"), any(RowCallbackHandler.class),
                eq(family.getMostSignificantBits() ^ family.getLeastSignificantBits()));
        store.revokeFamily(family);
        verify(jdbc).update("update refresh_token set status='REVOKED' where family_id=? and status='ACTIVE'", family);
    }

    @Test void persistsOnlyHashAndMapsAllNullableFields() throws Exception {
        String hash = "a".repeat(64);
        for (Instant used : new Instant[]{null, now}) {
            var token = new RefreshToken(id, family, user, org, hash, null, RefreshToken.Status.ACTIVE, now, now.plusSeconds(10), used);
            store.insert(token);
            verify(jdbc).update(contains("insert into refresh_token"), eq(id), eq(family), eq(user), eq(org), eq(hash),
                    isNull(), eq("ACTIVE"), eq(Timestamp.from(now)), eq(Timestamp.from(now.plusSeconds(10))),
                    eq(used == null ? null : Timestamp.from(used)));
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(id);
            when(rs.getObject("family_id", UUID.class)).thenReturn(family);
            when(rs.getObject("user_id", UUID.class)).thenReturn(user);
            when(rs.getObject("org_id", UUID.class)).thenReturn(org);
            when(rs.getString("token_hash")).thenReturn(hash);
            when(rs.getString("status")).thenReturn("ACTIVE");
            when(rs.getTimestamp("issued_at")).thenReturn(Timestamp.from(now));
            when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(now.plusSeconds(10)));
            when(rs.getTimestamp("used_at")).thenReturn(used == null ? null : Timestamp.from(used));
            when(jdbc.query(eq("select * from refresh_token where token_hash = ?"), any(RowMapper.class), eq(hash)))
                    .thenAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0)));
            assertThat(store.findToken(hash)).contains(token);
        }
    }

    @Test void mapsUsersRolesAndMissingRecords() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(user);
        when(rs.getString("password_hash")).thenReturn("synthetic-hash");
        when(rs.getString("role")).thenReturn("STAFF");
        when(jdbc.query(eq("select id, password_hash from staff_user where username = ?"), any(RowMapper.class), eq("synthetic")))
                .thenAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0)));
        assertThat(store.findUser("synthetic")).contains(new io.github.seah1006.careconnect.identity.application.AuthStore.User(user, "synthetic-hash"));
        when(jdbc.query(eq("select role from membership where user_id = ? and org_id = ?"), any(RowMapper.class), eq(user), eq(org)))
                .thenAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0)));
        assertThat(store.membership(user, org)).contains(StaffRole.STAFF);
        assertThat(store.findToken("missing")).isEmpty();
        assertThat(store.findUser("missing")).isEmpty();
    }
}
