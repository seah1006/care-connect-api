package io.github.seah1006.careconnect.participant;

import io.github.seah1006.careconnect.common.authz.*;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import io.github.seah1006.careconnect.participant.application.ParticipantView;
import io.github.seah1006.careconnect.participant.repository.ParticipantRepository;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("INV-AUTHZ-003") @Tag("INV-AUTHZ-005")
class ParticipantRepositoryTest {
    final UUID org = UUID.randomUUID(), user = UUID.randomUUID(), group = UUID.randomUUID(), id = UUID.randomUUID();
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ParticipantRepository repository = new ParticipantRepository(jdbc);
    String sql;
    final List<Object> bindings = new ArrayList<>();
    boolean member = true, viewAll = false, found = true;
    UUID targetGroup = group;

    @BeforeEach @SuppressWarnings({"rawtypes", "unchecked"})
    void databaseRows() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> { sql = call.getArgument(0); bindings.clear(); return statement; });
        doAnswer(call -> { bindings.add(call.getArgument(1)); return null; }).when(statement).setObject(anyInt(), any());
        doAnswer(call -> { bindings.add(call.getArgument(1)); return null; }).when(statement).setInt(anyInt(), anyInt());
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class))).thenAnswer(call -> {
            ((PreparedStatementCreator) call.getArgument(0)).createPreparedStatement(connection);
            var rs = mock(ResultSet.class);
            if (sql.startsWith("select role")) {
                if (!member) return List.of();
                when(rs.getString("role")).thenReturn("STAFF");
            } else if (sql.startsWith("select group_id")) {
                when(rs.getObject("group_id", UUID.class)).thenReturn(group);
            } else if (sql.startsWith("select staff_view_all")) {
                var metadata = mock(ResultSetMetaData.class);
                when(metadata.getColumnCount()).thenReturn(1);
                when(rs.getMetaData()).thenReturn(metadata);
                when(rs.getBoolean(1)).thenReturn(viewAll);
                when(rs.getObject(1, Boolean.class)).thenReturn(viewAll);
            } else {
                if (!found) return List.of();
                when(rs.getObject("id", UUID.class)).thenReturn(id);
                when(rs.getObject("group_id", UUID.class)).thenReturn(targetGroup);
                when(rs.getString("display_name")).thenReturn("synthetic-participant");
            }
            return List.of(((RowMapper) call.getArgument(1)).mapRow(rs, 0));
        });
    }

    @Test void factsReloadMembershipAssignmentsAndSettings() {
        var actor = new Actor(user, org);
        assertThat(repository.facts(actor)).isEqualTo(new AccessPolicy.Facts(StaffRole.STAFF, Set.of(group), false));
        member = false; viewAll = true;
        assertThat(repository.facts(actor)).isEqualTo(new AccessPolicy.Facts(null, Set.of(group), true));
        assertThat(bindings).contains(org);
    }

    @Test void singleLookupBindsBothTenantAndIdAndPreservesNullableGroup() {
        assertThat(repository.find(org, id)).contains(new ParticipantView(id, "synthetic-participant", group));
        assertThat(sql).contains("org_id=? and id=?");
        assertThat(bindings).containsExactly(org, id);
        targetGroup = null;
        assertThat(repository.find(org, id)).contains(new ParticipantView(id, "synthetic-participant", null));
        found = false;
        assertThat(repository.find(org, id)).isEmpty();
    }

    @Test void paginationAlwaysScopesOrdersAndLimitsAndOnlyExpandsNonemptyGroups() {
        assertThat(repository.list(org, true, Set.of(), null, 11))
                .containsExactly(new ParticipantView(id, "synthetic-participant", group));
        assertThat(sql).contains("org_id=? and (true)", "order by id limit ?").doesNotContain("id>?");
        assertThat(bindings).containsExactly(org, 11);
        repository.list(org, false, Set.of(), null, 2);
        assertThat(sql).contains("(false)");
        repository.list(org, false, Set.of(group), id, 2);
        assertThat(sql).contains("(group_id in (?))", "and id>?", "order by id limit ?");
        assertThat(bindings).containsExactly(org, group, id, 2);
    }
}
