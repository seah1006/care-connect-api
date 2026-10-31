package io.github.seah1006.careconnect.participant.repository;

import io.github.seah1006.careconnect.common.authz.AccessPolicy.Facts;
import io.github.seah1006.careconnect.common.authz.Actor;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import io.github.seah1006.careconnect.participant.application.ParticipantView;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ParticipantRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final RowMapper<ParticipantView> VIEW = (rs, row) -> new ParticipantView(
            rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getObject("group_id", UUID.class));

    public ParticipantRepository(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    public Facts facts(Actor actor) {
        var params = Map.of("org", actor.orgId(), "user", actor.userId());
        var roles = jdbc.query("select role from membership where org_id=:org and user_id=:user",
                params, (rs, row) -> StaffRole.valueOf(rs.getString("role")));
        var groups = jdbc.query("select group_id from staff_group_assignment where org_id=:org and user_id=:user",
                params, (rs, row) -> rs.getObject("group_id", UUID.class));
        boolean viewAll = Boolean.TRUE.equals(jdbc.queryForObject(
                "select staff_view_all from organization where id=:org", params, Boolean.class));
        return new Facts(roles.isEmpty() ? null : roles.getFirst(), Set.copyOf(groups), viewAll);
    }

    public Optional<ParticipantView> find(UUID org, UUID id) {
        return jdbc.query("select id, display_name, group_id from participant where org_id=:org and id=:id",
                Map.of("org", org, "id", id), VIEW).stream().findFirst();
    }

    public List<ParticipantView> list(UUID org, boolean all, Set<UUID> groups, UUID after, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("org", org);
        params.put("groups", groups);
        params.put("after", after);
        params.put("limit", limit);
        String scope = all ? "true" : groups.isEmpty() ? "false" : "group_id in (:groups)";
        return jdbc.query("select id, display_name, group_id from participant where org_id=:org and (" + scope
                + ")" + (after == null ? "" : " and id>:after") + " order by id limit :limit", params, VIEW);
    }
}
