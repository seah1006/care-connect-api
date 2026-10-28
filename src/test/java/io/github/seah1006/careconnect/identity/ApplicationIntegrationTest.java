package io.github.seah1006.careconnect.identity;

import io.github.seah1006.careconnect.CareConnectApplication;
import io.github.seah1006.careconnect.PostgresSupport;
import io.github.seah1006.careconnect.common.security.RefreshSecrets;
import io.github.seah1006.careconnect.common.tenancy.*;
import io.github.seah1006.careconnect.identity.application.AuthService;
import io.github.seah1006.careconnect.identity.application.AuthenticationFailed;
import io.github.seah1006.careconnect.identity.domain.RefreshToken;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

// Named ApplicationIntegrationTest so the existing PIT integration-test exclusion also applies here.
@org.junit.jupiter.api.Tag("docker") @net.jqwik.api.Tag("docker")
class ApplicationIntegrationTest {
    @TestConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackageClasses = CareConnectApplication.class, excludeFilters = {
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = CareConnectApplication.class),
            @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = TestConfiguration.class)})
    @EntityScan(basePackageClasses = {CareConnectApplication.class, TenantProbe.class})
    static class ProbeConfiguration {}

    static class Database extends PostgresSupport {
        final ConfigurableApplicationContext context;
        final JdbcTemplate jdbc;
        final JdbcTemplate owner;
        final AuthService auth;
        final TransactionTemplate tx;
        final TransactionTemplate jpaTx;
        final EntityManagerFactory emf;
        final PasswordEncoder passwords;

        Database() {
            Map<String, Object> settings = new HashMap<>();
            properties(settings::put);
            settings.put("spring.flyway.locations", "classpath:db/migration,classpath:db/testmigration");
            settings.put("server.port", 0);
            // jqwik starts this context directly, so explicitly exclude unrelated test configurations.
            context = new SpringApplicationBuilder(ProbeConfiguration.class)
                    .run(settings.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
            jdbc = context.getBean(JdbcTemplate.class);
            owner = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
            auth = context.getBean(AuthService.class);
            passwords = context.getBean(PasswordEncoder.class);
            tx = new TransactionTemplate(context.getBean("identityTransactionManager", PlatformTransactionManager.class));
            jpaTx = new TransactionTemplate(context.getBean("transactionManager", PlatformTransactionManager.class));
            emf = context.getBean(EntityManagerFactory.class);
            Runtime.getRuntime().addShutdownHook(new Thread(context::close));
        }

        UUID organization() {
            UUID org = UUID.randomUUID();
            jdbc.update("insert into organization(id, name) values (?, 'synthetic-organization')", org);
            return org;
        }

        LoginFixture staff() {
            UUID org = organization(), user = UUID.randomUUID();
            String name = "synthetic-" + user;
            jdbc.update("insert into staff_user values (?, ?, ?, ?)", user, name,
                    passwords.encode("synthetic-password"), Timestamp.from(Instant.now()));
            scoped(org, () -> jdbc.update("insert into membership values (?, ?, ?, 'STAFF')", UUID.randomUUID(), org, user));
            return new LoginFixture(user, org, name);
        }

        <T> T scoped(UUID org, java.util.function.Supplier<T> operation) {
            return TenantContext.callAs(org, () -> tx.execute(status -> operation.get()));
        }
    }
    record LoginFixture(UUID user, UUID org, String username) {}
    static class Holder { static final Database DB = new Database(); }
    static Database db() { return Holder.DB; }

    @Test @org.junit.jupiter.api.Tag("INV-AUTH-001") @org.junit.jupiter.api.Tag("INV-AUTH-002")
    void sequentialReuseCommitsRevocationBeforeReturningFailure() {
        Database d = db(); var user = d.staff();
        var t1 = d.auth.login(user.org(), user.username(), "synthetic-password");
        var t2 = d.auth.refresh(t1.refreshToken());
        assertThatThrownBy(() -> d.auth.refresh(t1.refreshToken())).isInstanceOf(AuthenticationFailed.class);
        assertThatThrownBy(() -> d.auth.refresh(t2.refreshToken())).isInstanceOf(AuthenticationFailed.class);
        assertThat(d.jdbc.queryForObject("select count(*) from refresh_token where user_id=? and status='ACTIVE'",
                Integer.class, user.user())).isZero();
    }

    @Test @org.junit.jupiter.api.Tag("INV-AUTH-001") @org.junit.jupiter.api.Tag("INV-AUTH-002")
    void eightConcurrentRefreshesFollowUseCasTrueForTwentyFamilies() throws Exception {
        Database d = db(); var user = d.staff();
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int run = 0; run < 20; run++) {
                var pair = d.auth.login(user.org(), user.username(), "synthetic-password");
                UUID parent = d.jdbc.queryForObject("select id from refresh_token where token_hash=?", UUID.class,
                        RefreshSecrets.hash(pair.refreshToken()));
                CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 8; i++) results.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(20, TimeUnit.SECONDS)) throw new AssertionError("Start barrier timed out");
                    try { d.auth.refresh(pair.refreshToken()); return true; }
                    catch (AuthenticationFailed expected) { return false; }
                }));
                assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                int successes = 0;
                for (var result : results) if (result.get(30, TimeUnit.SECONDS)) successes++;
                // RefreshTokenRotation.md: Begin may read ACTIVE twice, Finish uses current state (UseCAS=TRUE).
                // INV_AUTH_001: at most one Exchange and one child. Losing Finish invokes RevokeFamily.
                assertThat(successes).isEqualTo(1);
                assertThat(d.jdbc.queryForObject("select count(*) from refresh_token where parent_id=?", Integer.class, parent))
                        .isEqualTo(1);
                // INV_AUTH_001_SingleActive and reuse => INV_AUTH_002, including the committed successor.
                assertThat(d.jdbc.queryForObject("""
                        select count(*) from refresh_token where family_id=(select family_id from refresh_token where id=?)
                        and status='ACTIVE'
                        """, Integer.class, parent)).isZero();
            }
        }
    }

    @Test @org.junit.jupiter.api.Tag("INV-TEN-004")
    void loginCannotMintTokenForNonMembershipAndDatabaseAccountCannotBypassRls() {
        Database d = db(); var user = d.staff();
        assertThatThrownBy(() -> d.auth.login(d.organization(), user.username(), "synthetic-password"))
                .isInstanceOf(AuthenticationFailed.class);
        var pair = d.auth.login(user.org(), user.username(), "synthetic-password");
        var jwt = d.context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class).decode(pair.accessToken());
        assertThat(jwt.getClaimAsString("org")).isEqualTo(user.org().toString());
        assertThat(d.jdbc.queryForObject("select rolsuper or rolbypassrls from pg_roles where rolname=current_user", Boolean.class))
                .isFalse();
        assertThat(d.jdbc.queryForObject("select current_user", String.class)).isEqualTo("care_test_app");
        assertThat(d.jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where relname='membership'",
                Boolean.class)).isTrue();
    }

    @Test @org.junit.jupiter.api.Tag("INV-TEN-001")
    void realMembershipTableRejectsCrossTenantWritesAndHidesRowsWithoutContext() {
        Database d = db(); var a = d.staff(); var b = d.staff();
        var users = d.scoped(a.org(), () -> d.jdbc.queryForList("select user_id from membership", UUID.class));
        assertThat(users).containsExactly(a.user());
        assertThat(d.jdbc.queryForList("select * from membership")).isEmpty();
        rejects("42501", () -> d.scoped(a.org(), () -> d.jdbc.update(
                "insert into membership values (?, ?, ?, 'STAFF')", UUID.randomUUID(), b.org(), a.user())));
        rejects("42501", () -> d.scoped(a.org(), () -> d.jdbc.update("update membership set org_id=?", b.org())));
        assertThat(d.scoped(a.org(), () -> d.jdbc.update("delete from membership where user_id=?", b.user()))).isZero();
    }

    @Property(tries = 100) @net.jqwik.api.Tag("INV-TEN-001")
    void randomLayoutsAreIsolatedByJpaAndUnqualifiedSql(@ForAll("layouts") List<Integer> layout) {
        Database d = db(); List<UUID> orgs = new ArrayList<>();
        for (int rows : layout) {
            UUID org = d.organization(); orgs.add(org);
            d.scoped(org, () -> {
                for (int i = 0; i < rows; i++) d.jdbc.update("insert into tenant_probe values (?, ?, 'synthetic')", UUID.randomUUID(), org);
                return null;
            });
        }
        for (int i = 0; i < orgs.size(); i++) {
            UUID org = orgs.get(i); int expected = layout.get(i);
            d.scoped(org, () -> {
                assertThat(d.jdbc.queryForList("select org_id from tenant_probe", UUID.class)).hasSize(expected).containsOnly(org);
                return null;
            });
            TenantContext.callAs(org, () -> d.jpaTx.execute(status -> {
                var em = EntityManagerFactoryUtils.getTransactionalEntityManager(d.emf);
                assertThat(em.createQuery("from TenantProbe", TenantProbe.class).getResultList())
                        .hasSize(expected).allMatch(row -> row.organizationId().equals(org));
                return null;
            }));
        }
        var unscoped = d.tx.execute(status -> d.jdbc.queryForList("select * from tenant_probe"));
        assertThat(unscoped).isEmpty();
        assertThatThrownBy(() -> d.jpaTx.execute(status -> null)).isInstanceOfAny(IllegalStateException.class,
                org.springframework.transaction.CannotCreateTransactionException.class);
        UUID a = orgs.getFirst(), b = orgs.getLast();
        assertThatThrownBy(() -> d.scoped(a, () -> d.jdbc.update("insert into tenant_probe values (?, ?, 'synthetic')", UUID.randomUUID(), b)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> d.scoped(a, () -> d.jdbc.update("update tenant_probe set org_id=?", b)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(d.scoped(a, () -> d.jdbc.update("update tenant_probe set label='changed' where org_id=?", b))).isZero();
        // Reuse pooled connections after both successful and rejected transactions: no tenant survives.
        assertThat(d.jdbc.queryForList("select * from tenant_probe")).isEmpty();
    }

    @Provide Arbitrary<List<Integer>> layouts() {
        return Arbitraries.integers().between(1, 5).list().ofMinSize(2).ofMaxSize(5);
    }

    @Test @org.junit.jupiter.api.Tag("INV-TEN-002") @org.junit.jupiter.api.Tag("INV-AUTH-001")
    void databaseRejectsInvalidOwnershipRolesAndTokenBranches() {
        Database d = db(); var user = d.staff();
        // The owner connection tests constraints independently of RLS; isolation tests always use the app login.
        rejects("23502", () -> d.owner.update("insert into membership values (?, null, ?, 'STAFF')", UUID.randomUUID(), user.user()));
        rejects("23505", () -> d.owner.update("insert into membership values (?, ?, ?, 'STAFF')",
                UUID.randomUUID(), user.org(), user.user()));
        rejects("23514", () -> d.owner.update("update membership set role='INVALID' where user_id=?", user.user()));
        rejects("23503", () -> d.owner.update("insert into membership values (?, ?, ?, 'STAFF')",
                UUID.randomUUID(), UUID.randomUUID(), user.user()));
        rejects("23503", () -> d.owner.update("insert into membership values (?, ?, ?, 'STAFF')",
                UUID.randomUUID(), user.org(), UUID.randomUUID()));
        var pair = d.auth.login(user.org(), user.username(), "synthetic-password");
        UUID parent = d.jdbc.queryForObject("select id from refresh_token where token_hash=?", UUID.class, RefreshSecrets.hash(pair.refreshToken()));
        rejects("23505", () -> duplicateToken(d, parent, null, "ACTIVE"));
        d.auth.refresh(pair.refreshToken());
        rejects("23505", () -> duplicateToken(d, parent, parent, "USED"));
        rejects("23503", () -> duplicateToken(d, parent, UUID.randomUUID(), "USED"));
        rejects("23514", () -> d.jdbc.update("update refresh_token set status='INVALID' where id=?", parent));
        rejects("23514", () -> d.jdbc.update("update refresh_token set token_hash='plaintext' where id=?", parent));
        rejects("23502", () -> d.jdbc.update("update refresh_token set org_id=null where id=?", parent));
        rejects("23503", () -> d.jdbc.update("update refresh_token set org_id=? where id=?", UUID.randomUUID(), parent));
    }

    private static void rejects(String sqlState, org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).satisfies(failure -> {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getSQLState()).isEqualTo(sqlState);
        });
    }

    private void duplicateToken(Database d, UUID source, UUID parent, String status) {
        d.jdbc.update("""
                insert into refresh_token(id, family_id, user_id, org_id, token_hash, parent_id, status, issued_at, expires_at)
                select ?, family_id, user_id, org_id, ?, ?, ?, issued_at, expires_at from refresh_token where id=?
                """, UUID.randomUUID(), RefreshSecrets.hash(RefreshSecrets.generate()), parent, status, source);
    }
}
