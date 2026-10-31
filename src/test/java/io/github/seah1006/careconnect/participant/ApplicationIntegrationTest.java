package io.github.seah1006.careconnect.participant;

import io.github.seah1006.careconnect.CareConnectApplication;
import io.github.seah1006.careconnect.PostgresSupport;
import io.github.seah1006.careconnect.common.authz.AccessPolicy;
import io.github.seah1006.careconnect.common.authz.AccessPolicy.Facts;
import io.github.seah1006.careconnect.common.security.AccessTokens;
import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.*;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Tag("docker")
class ApplicationIntegrationTest {
    static class Database extends PostgresSupport {
        final ConfigurableApplicationContext context;
        final JdbcTemplate jdbc, owner;
        final TransactionTemplate tx, jpaTx;
        final EntityManagerFactory emf;
        final AccessTokens tokens;
        final String base;
        final HttpClient client = HttpClient.newHttpClient();
        final JsonMapper mapper = JsonMapper.builder().build();

        Database() {
            Map<String, Object> settings = new HashMap<>();
            properties(settings::put);
            settings.put("server.port", 0);
            settings.put("spring.flyway.locations", "classpath:db/migration,classpath:db/testmigration");
            context = new SpringApplicationBuilder(CareConnectApplication.class)
                    .run(settings.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
            jdbc = context.getBean(JdbcTemplate.class);
            owner = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
            tx = new TransactionTemplate(context.getBean("identityTransactionManager", PlatformTransactionManager.class));
            jpaTx = new TransactionTemplate(context.getBean("transactionManager", PlatformTransactionManager.class));
            emf = context.getBean(EntityManagerFactory.class);
            tokens = context.getBean(AccessTokens.class);
            base = "http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort() + "/api/v1/participants";
            Runtime.getRuntime().addShutdownHook(new Thread(context::close));
        }

        UUID org(boolean all) {
            UUID id = UUID.randomUUID();
            jdbc.update("insert into organization(id, name, staff_view_all) values (?, 'synthetic-org', ?)", id, all);
            return id;
        }
        UUID user(UUID org, StaffRole role) {
            UUID id = UUID.randomUUID();
            jdbc.update("insert into staff_user values (?, ?, 'synthetic-unused-hash', now())", id, "synthetic-" + id);
            scoped(org, () -> jdbc.update("insert into membership values (?, ?, ?, ?)", UUID.randomUUID(), org, id, role.name()));
            return id;
        }
        UUID group(UUID org) {
            UUID id = UUID.randomUUID();
            scoped(org, () -> jdbc.update("insert into care_group values (?, ?, ?)", id, org, "synthetic-group-" + id));
            return id;
        }
        UUID participant(UUID org, UUID group) {
            UUID id = UUID.randomUUID();
            scoped(org, () -> jdbc.update("insert into participant values (?, ?, ?, ?)", id, org, group, "synthetic-participant-" + id));
            return id;
        }
        int assign(UUID org, UUID user, UUID group) {
            return scoped(org, () -> jdbc.update("insert into staff_group_assignment values (?, ?, ?, ?)",
                    UUID.randomUUID(), org, user, group));
        }
        <T> T scoped(UUID org, Supplier<T> operation) {
            return TenantContext.callAs(org, () -> tx.execute(status -> operation.get()));
        }
        HttpResponse<byte[]> get(String token, String suffix, int expected) throws Exception {
            var jwt = context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class).decode(token);
            UUID org = UUID.fromString(jwt.getClaimAsString("org"));
            UUID user = UUID.fromString(jwt.getSubject());
            Set<UUID> before = new HashSet<>(scoped(org, () -> jdbc.queryForList(
                    "select id from access_log where actor_user_id=?", UUID.class, user)));
            var response = client.send(HttpRequest.newBuilder(URI.create(base + suffix))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).as(suffix).isEqualTo(expected);
            if (expected != 200) assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("application/problem+json");
            var logs = scoped(org, () -> jdbc.queryForList("select * from access_log where actor_user_id=?", user))
                    .stream().filter(row -> !before.contains(row.get("id"))).toList();
            if (expected == 500) {
                assertThat(logs).isEmpty();
                assertThat(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8))
                        .doesNotContain("synthetic-participant", "items", "access_log", "permission denied");
                assertThat(mapper.readTree(response.body()).path("status").asInt()).isEqualTo(500);
            } else {
                boolean single = suffix.startsWith("/");
                String outcome = expected == 200 ? "ALLOWED" : expected == 403 ? "DENIED" : "NOT_FOUND";
                List<UUID> resources = new ArrayList<>();
                if (single) resources.add(UUID.fromString(suffix.substring(1)));
                else if (expected == 200) {
                    for (var item : mapper.readTree(response.body()).path("items")) resources.add(UUID.fromString(item.path("id").asString()));
                }
                if (resources.isEmpty()) resources.add(null);
                assertThat(logs).hasSize(resources.size());
                assertThat(logs.stream().map(row -> (UUID) row.get("resource_id")).toList())
                        .containsExactlyInAnyOrderElementsOf(resources);
                assertThat(logs.stream().map(row -> row.get("request_id")).distinct().count()).isEqualTo(1);
                for (var row : logs) {
                    assertThat(row).containsEntry("org_id", org).containsEntry("actor_user_id", user)
                            .containsEntry("action", single ? "READ" : "LIST").containsEntry("outcome", outcome)
                            .containsEntry("resource_type", "participant");
                    assertThat(row.get("occurred_at")).isNotNull();
                    assertThat(row.get("client_ip")).isIn("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
                }
            }
            return response;
        }
        Set<UUID> list(String token, int size) throws Exception {
            Set<UUID> ids = new LinkedHashSet<>();
            String cursor = "";
            String previous = null;
            do {
                JsonNode page = mapper.readTree(get(token, "?size=" + size + "&cursor=" + cursor, 200).body());
                for (var row : page.path("items")) {
                    String id = row.path("id").asString();
                    if (previous != null) assertThat(id).isGreaterThan(previous);
                    previous = id;
                    assertThat(ids.add(UUID.fromString(id))).isTrue();
                }
                assertThat(page.path("items").size()).isLessThanOrEqualTo(size);
                cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
                assertThat(ids.size()).isLessThan(1000);
            } while (cursor != null);
            return ids;
        }
    }
    static class Holder { static final Database DB = new Database(); }
    static Database db() { return Holder.DB; }

    @Test @Tag("INV-AUTHZ-003") @Tag("INV-AUTHZ-002") @Tag("INV-API-001") @Tag("INV-AUDIT-001")
    void twentyRandomLayoutsMatchPolicyForEveryEmployeeAndHideForeignParticipants() throws Exception {
        Database d = db(); Random random = new Random(3003);
        for (int run = 0; run < 20; run++) {
            UUID a = d.org(random.nextBoolean()), b = d.org(random.nextBoolean());
            Map<UUID, Map<UUID, UUID>> layouts = new HashMap<>();
            Map<UUID, List<UUID>> groups = new HashMap<>();
            for (UUID org : List.of(a, b)) {
                List<UUID> gs = new ArrayList<>();
                for (int i = 0, n = 1 + random.nextInt(4); i < n; i++) gs.add(d.group(org));
                groups.put(org, gs);
                Map<UUID, UUID> participants = new HashMap<>();
                participants.put(d.participant(org, null), null);
                for (int i = 0, n = 3 + random.nextInt(8); i < n; i++) {
                    UUID group = gs.get(random.nextInt(gs.size()));
                    participants.put(d.participant(org, group), group);
                }
                layouts.put(org, participants);
            }
            for (UUID org : List.of(a, b)) {
                boolean all = d.jdbc.queryForObject("select staff_view_all from organization where id=?", Boolean.class, org);
                for (int employee = 0, n = 2 + random.nextInt(3); employee < n; employee++) {
                    StaffRole role = employee == 0 ? StaffRole.ORG_ADMIN : StaffRole.STAFF;
                    UUID user = d.user(org, role);
                    Set<UUID> assigned = new HashSet<>();
                    for (UUID group : groups.get(org)) if (random.nextBoolean()) { d.assign(org, user, group); assigned.add(group); }
                    // Deliberately stale token role: all authorization facts must come from the DB.
                    String token = d.tokens.issue(user, org, role == StaffRole.STAFF ? StaffRole.ORG_ADMIN : StaffRole.STAFF);
                    var facts = new Facts(role, assigned, all);
                    Set<UUID> expected = new HashSet<>();
                    layouts.get(org).forEach((id, group) -> { if (AccessPolicy.permits(facts, group)) expected.add(id); });
                    assertThat(d.list(token, 1 + random.nextInt(4))).isEqualTo(expected);
                    for (var target : layouts.get(org).entrySet()) {
                        d.get(token, "/" + target.getKey(), expected.contains(target.getKey()) ? 200 : 403);
                    }
                    UUID foreign = layouts.get(org.equals(a) ? b : a).keySet().iterator().next();
                    var missing = d.get(token, "/" + UUID.randomUUID(), 404);
                    var other = d.get(token, "/" + foreign, 404);
                    assertThat(other.headers().firstValue("Content-Type")).isEqualTo(missing.headers().firstValue("Content-Type"));
                    assertThat(other.body()).isEqualTo(missing.body());
                    assertThat(new String(other.body(), java.nio.charset.StandardCharsets.UTF_8))
                            .doesNotContain("synthetic-participant", "synthetic-group", foreign.toString());
                }
            }
        }
    }

    @Test @Tag("INV-AUTHZ-004") @Tag("INV-TEN-002")
    void compositeForeignKeysAndUniquenessRejectInvalidOwnershipUsingApplicationAccount() {
        Database d = db(); UUID a = d.org(false), b = d.org(false);
        UUID userA = d.user(a, StaffRole.STAFF), userB = d.user(b, StaffRole.STAFF);
        UUID groupA = d.group(a), groupB = d.group(b);
        rejects("23503", () -> d.assign(a, userA, groupB));
        rejects("23503", () -> d.assign(a, userB, groupA));
        rejects("23503", () -> d.participant(a, groupB));
        UUID participant = d.participant(a, groupA);
        rejects("23503", () -> d.scoped(a, () -> d.jdbc.update("update participant set group_id=? where id=?", groupB, participant)));
        d.assign(a, userA, groupA);
        rejects("23505", () -> d.assign(a, userA, groupA));
        for (String table : List.of("care_group", "participant", "staff_group_assignment")) {
            // Isolate NOT NULL from RLS; ownership/FK rejection above uses the application account.
            rejects("23502", () -> d.owner.update("update " + table + " set org_id=null where org_id=?", a));
        }
    }

    @Test @Tag("INV-TEN-001") @Tag("INV-TEN-002")
    void allThreeTablesForceRlsForReadsWritesAndJpaQueries() {
        Database d = db(); UUID a = d.org(false), b = d.org(false);
        UUID ua = d.user(a, StaffRole.STAFF), ub = d.user(b, StaffRole.STAFF);
        UUID ga = d.group(a), gb = d.group(b);
        d.participant(a, ga); d.participant(b, gb); d.assign(a, ua, ga); d.assign(b, ub, gb);
        for (String table : List.of("care_group", "participant", "staff_group_assignment")) {
            assertThat(d.jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where relname=?",
                    Boolean.class, table)).isTrue();
            assertThat(d.jdbc.queryForList("select * from " + table)).isEmpty();
            assertThat(d.scoped(a, () -> d.jdbc.queryForList("select org_id from " + table, UUID.class))).containsOnly(a);
            rejects("42501", () -> d.scoped(a, () -> d.jdbc.update("update " + table + " set org_id=?", b)));
            assertThat(d.scoped(a, () -> d.jdbc.update("delete from " + table + " where org_id=?", b))).isZero();
            rejects("42501", () -> d.scoped(a, () -> d.jdbc.update("insert into " + table
                    + " select ?::uuid, ?::uuid, " + (table.equals("care_group") ? "name" : table.equals("participant")
                    ? "group_id, display_name" : "user_id, group_id") + " from " + table + " limit 1", UUID.randomUUID(), b)));
        }
        for (String entity : List.of("CareGroup", "Participant", "StaffGroupAssignment")) {
            TenantContext.callAs(a, () -> d.jpaTx.execute(status -> {
                var em = EntityManagerFactoryUtils.getTransactionalEntityManager(d.emf);
                assertThat(em.createQuery("select e.orgId from " + entity + " e", UUID.class).getResultList()).containsOnly(a);
                return null;
            }));
        }
    }

    @Test @Tag("INV-AUTHZ-005") @Tag("INV-AUTHZ-003")
    void sameTokenTracksRevocationPromotionAndRandomFactChanges() throws Exception {
        Database d = db(); UUID org = d.org(false), user = d.user(org, StaffRole.STAFF);
        UUID group = d.group(org), target = d.participant(org, group), ungrouped = d.participant(org, null);
        d.assign(org, user, group);
        String token = d.tokens.issue(user, org, StaffRole.STAFF);
        d.get(token, "/" + target, 200);
        d.scoped(org, () -> d.jdbc.update("delete from staff_group_assignment where user_id=?", user));
        d.get(token, "/" + target, 403); assertThat(d.list(token, 1)).isEmpty();
        d.scoped(org, () -> d.jdbc.update("update membership set role='ORG_ADMIN' where user_id=?", user));
        assertThat(d.list(token, 1)).containsExactlyInAnyOrder(target, ungrouped);
        d.scoped(org, () -> d.jdbc.update("delete from membership where user_id=?", user));
        d.get(token, "/" + target, 403); d.get(token, "", 403);

        StaffRole role = null; boolean all = false, assigned = false;
        Random random = new Random(5005);
        for (int step = 0; step < 50; step++) {
            switch (random.nextInt(5)) {
                case 0, 1 -> {
                    role = random.nextBoolean() ? StaffRole.STAFF : StaffRole.ORG_ADMIN;
                    StaffRole next = role;
                    d.scoped(org, () -> d.jdbc.update("insert into membership values (?, ?, ?, ?) "
                            + "on conflict (org_id,user_id) do update set role=excluded.role", UUID.randomUUID(), org, user, next.name()));
                }
                case 2 -> {
                    all = !all;
                    d.jdbc.update("update organization set staff_view_all=? where id=?", all, org);
                }
                case 3 -> {
                    if (assigned) d.scoped(org, () -> d.jdbc.update("delete from staff_group_assignment where user_id=?", user));
                    else if (role != null) d.assign(org, user, group);
                    assigned = role != null && !assigned;
                }
                case 4 -> {
                    d.scoped(org, () -> d.jdbc.update("delete from staff_group_assignment where user_id=?", user));
                    d.scoped(org, () -> d.jdbc.update("delete from membership where user_id=?", user));
                    role = null; assigned = false;
                }
            }
            Facts facts = new Facts(role, assigned ? Set.of(group) : Set.of(), all);
            for (UUID id : List.of(target, ungrouped)) d.get(token, "/" + id,
                    AccessPolicy.permits(facts, id.equals(target) ? group : null) ? 200 : 403);
            if (role == null) d.get(token, "", 403);
            else {
                Set<UUID> expected = new HashSet<>();
                if (AccessPolicy.permits(facts, group)) expected.add(target);
                if (AccessPolicy.permits(facts, null)) expected.add(ungrouped);
                assertThat(d.list(token, 1)).isEqualTo(expected);
            }
        }
    }

    @Test @Tag("INV-AUDIT-002") @Tag("INV-TEN-001") @Tag("INV-TEN-002")
    void accessLogPrivilegesRetentionConstraintsAndRls() throws Exception {
        Database d = db(); UUID a = d.org(false), b = d.org(false), user = d.user(a, StaffRole.ORG_ADMIN);
        UUID target = d.participant(a, null);
        d.get(d.tokens.issue(user, a, StaffRole.ORG_ADMIN), "/" + target, 200);
        UUID id = d.scoped(a, () -> d.jdbc.queryForObject("select id from access_log where org_id=?", UUID.class, a));
        assertThat(d.jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where relname='access_log'", Boolean.class)).isTrue();
        assertThat(d.jdbc.queryForList("select * from access_log")).isEmpty();
        assertThat(d.scoped(b, () -> d.jdbc.queryForList("select * from access_log where id=?", id))).isEmpty();
        assertThat(d.scoped(a, () -> d.jdbc.queryForList("select org_id from access_log", UUID.class))).containsOnly(a);
        rejects("42501", () -> d.scoped(a, () -> d.jdbc.update("update access_log set outcome='DENIED' where id=?", id)));
        rejects("42501", () -> d.scoped(a, () -> d.jdbc.update("delete from access_log where id=?", id)));
        rejects("42501", () -> d.owner.update("update access_log set outcome='DENIED' where id=?", id));
        rejects("42501", () -> d.owner.update("delete from access_log where id=?", id));
        rejects("42501", () -> d.owner.execute("truncate access_log"));
        String insert = "insert into access_log(id,org_id,actor_user_id,action,resource_type,outcome,occurred_at,request_id) "
                + "values (?,?,?,'READ','participant','ALLOWED',now(),?)";
        rejects("42501", () -> d.scoped(a, () -> d.jdbc.update(insert, UUID.randomUUID(), b, user, UUID.randomUUID())));
        rejects("23502", () -> d.owner.update(insert, UUID.randomUUID(), null, user, UUID.randomUUID()));
        rejects("23503", () -> d.owner.update(insert, UUID.randomUUID(), UUID.randomUUID(), user, UUID.randomUUID()));
        rejects("23514", () -> d.owner.update(insert.replace("'READ'", "'INVALID'"), UUID.randomUUID(), a, user, UUID.randomUUID()));
        rejects("23514", () -> d.owner.update(insert.replace("'ALLOWED'", "'INVALID'"), UUID.randomUUID(), a, user, UUID.randomUUID()));
        UUID old = UUID.randomUUID();
        d.owner.update(insert.replace("now()", "now()-interval '2 years 1 day'"), old, a, user, UUID.randomUUID());
        // Application account cannot delete even records past the retention period.
        rejects("42501", () -> d.scoped(a, () -> d.jdbc.update("delete from access_log where id=?", old)));
        assertThat(d.owner.update("delete from access_log where id=?", old)).isEqualTo(1);
    }

    @Test @Tag("INV-AUDIT-001") @Tag("INV-AUDIT-003")
    void recordsCommitOutsideCallerRollbackAndInsertFailureBlocksResponses() throws Exception {
        Database d = db(); UUID org = d.org(false), user = d.user(org, StaffRole.STAFF);
        UUID target = d.participant(org, null);
        String token = d.tokens.issue(user, org, StaffRole.STAFF);
        d.get(token, "/" + target, 403);
        d.get(token, "/" + UUID.randomUUID(), 404);
        assertThat(d.scoped(org, () -> d.jdbc.queryForList("select outcome from access_log where actor_user_id=?", String.class, user)))
                .containsExactlyInAnyOrder("DENIED", "NOT_FOUND");
        d.scoped(org, () -> d.jdbc.update("delete from membership where user_id=?", user));
        d.get(token, "", 403);
        d.scoped(org, () -> d.jdbc.update("insert into membership values (?,?,?,'ORG_ADMIN')", UUID.randomUUID(), org, user));
        var audit = d.context.getBean(io.github.seah1006.careconnect.common.audit.AccessLog.class);
        UUID resource = UUID.randomUUID();
        d.scoped(org, () -> {
            audit.record(new io.github.seah1006.careconnect.common.audit.AccessEvent(org, user,
                    io.github.seah1006.careconnect.common.audit.AccessEvent.Action.READ, "participant", resource,
                    io.github.seah1006.careconnect.common.audit.AccessEvent.Outcome.NOT_FOUND));
            return null;
        });
        // Explicit rollback of the surrounding transaction must leave the independent insert committed.
        io.github.seah1006.careconnect.common.tenancy.TenantContext.callAs(org, () -> d.tx.execute(status -> {
            audit.record(new io.github.seah1006.careconnect.common.audit.AccessEvent(org, user,
                    io.github.seah1006.careconnect.common.audit.AccessEvent.Action.READ, "participant", resource,
                    io.github.seah1006.careconnect.common.audit.AccessEvent.Outcome.DENIED));
            status.setRollbackOnly(); return null;
        }));
        assertThat(d.scoped(org, () -> d.jdbc.queryForObject("select count(*) from access_log where resource_id=?", Integer.class, resource))).isEqualTo(2);
        d.owner.execute("revoke insert on access_log from care_test_app");
        try {
            d.get(token, "/" + target, 500);
            d.get(token, "", 500);
        } finally { d.owner.execute("grant insert on access_log to care_test_app"); }
        d.get(token, "/" + target, 200);
        d.get(token, "", 200);
    }

    @Test @Tag("INV-SENS-003")
    void rawDatabaseCiphertextHidesPlaintextAndCannotBeCopiedToAnotherRow() {
        Database d = db(); UUID org = d.org(false), first = UUID.randomUUID(), second = UUID.randomUUID();
        var cipher = d.context.getBean(io.github.seah1006.careconnect.common.crypto.FieldCipher.class);
        var slot = new io.github.seah1006.careconnect.common.crypto.FieldSlot(org, "sensitive_probe", "secret", first);
        String plaintext = "synthetic-sensitive-value-\ud83d\ude00";
        String sealed = cipher.seal(plaintext, slot);
        d.scoped(org, () -> d.jdbc.update("insert into sensitive_probe values (?,?,?)", first, org, sealed));
        String raw = d.scoped(org, () -> d.jdbc.queryForObject("select secret from sensitive_probe where id=?", String.class, first));
        assertThat(raw).isEqualTo(sealed).doesNotContain(plaintext);
        assertThat(cipher.open(raw, slot)).isEqualTo(plaintext);
        d.scoped(org, () -> d.jdbc.update("insert into sensitive_probe select ?,org_id,secret from sensitive_probe where id=?", second, first));
        String copied = d.scoped(org, () -> d.jdbc.queryForObject("select secret from sensitive_probe where id=?", String.class, second));
        assertThatThrownBy(() -> cipher.open(copied,
                new io.github.seah1006.careconnect.common.crypto.FieldSlot(org, "sensitive_probe", "secret", second)))
                .isExactlyInstanceOf(io.github.seah1006.careconnect.common.crypto.FieldDecryptionFailed.class)
                .hasMessage("Field decryption failed");
    }

    private static void rejects(String state, org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).satisfies(failure -> {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getSQLState()).isEqualTo(state);
        });
    }
}
