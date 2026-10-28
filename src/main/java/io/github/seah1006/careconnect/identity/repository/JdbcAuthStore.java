package io.github.seah1006.careconnect.identity.repository;

import io.github.seah1006.careconnect.identity.application.AuthStore;
import io.github.seah1006.careconnect.identity.domain.RefreshToken;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcAuthStore implements AuthStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcAuthStore(JdbcTemplate jdbc,
            @Qualifier("identityTransactionManager") PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public <T> T transaction(Supplier<T> operation) {
        return transactions.execute(status -> operation.get());
    }

    @Override
    public Optional<User> findUser(String username) {
        return jdbc.query("select id, password_hash from staff_user where username = ?",
                (rs, row) -> new User(rs.getObject("id", UUID.class), rs.getString("password_hash")), username)
                .stream().findFirst();
    }

    @Override
    public Optional<StaffRole> membership(UUID userId, UUID orgId) {
        return jdbc.query("select role from membership where user_id = ? and org_id = ?",
                (rs, row) -> StaffRole.valueOf(rs.getString("role")), userId, orgId).stream().findFirst();
    }

    @Override
    public Optional<RefreshToken> findToken(String hash) {
        return jdbc.query("select * from refresh_token where token_hash = ?", (rs, row) -> {
            Timestamp used = rs.getTimestamp("used_at");
            return new RefreshToken(rs.getObject("id", UUID.class), rs.getObject("family_id", UUID.class),
                    rs.getObject("user_id", UUID.class), rs.getObject("org_id", UUID.class),
                    rs.getString("token_hash"), rs.getObject("parent_id", UUID.class),
                    RefreshToken.Status.valueOf(rs.getString("status")), rs.getTimestamp("issued_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(), used == null ? null : used.toInstant());
        }, hash).stream().findFirst();
    }

    @Override
    public void lockFamily(UUID familyId) {
        // Serialize ancestor reuse, descendant rotation and logout, including their commits.
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> {},
                familyId.getMostSignificantBits() ^ familyId.getLeastSignificantBits());
    }

    @Override
    public int markUsed(UUID id, Instant now) {
        return jdbc.update("UPDATE refresh_token SET status='USED', used_at=? WHERE id=? AND status='ACTIVE'",
                Timestamp.from(now), id);
    }

    @Override
    public RefreshToken.Status status(UUID id) {
        return RefreshToken.Status.valueOf(jdbc.queryForObject(
                "select status from refresh_token where id = ?", String.class, id));
    }

    @Override
    public void insert(RefreshToken token) {
        jdbc.update("""
                insert into refresh_token(id, family_id, user_id, org_id, token_hash, parent_id,
                    status, issued_at, expires_at, used_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, token.id(), token.familyId(), token.userId(), token.orgId(), token.tokenHash(),
                token.parentId(), token.status().name(), Timestamp.from(token.issuedAt()),
                Timestamp.from(token.expiresAt()), token.usedAt() == null ? null : Timestamp.from(token.usedAt()));
    }

    @Override
    public void revokeFamily(UUID familyId) {
        jdbc.update("update refresh_token set status='REVOKED' where family_id=? and status='ACTIVE'", familyId);
    }
}
