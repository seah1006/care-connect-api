package io.github.seah1006.careconnect.participant;

import io.github.seah1006.careconnect.common.authz.*;
import io.github.seah1006.careconnect.common.pagination.*;
import io.github.seah1006.careconnect.common.security.*;
import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import io.github.seah1006.careconnect.common.web.ApiExceptionHandler;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import io.github.seah1006.careconnect.participant.application.*;
import io.github.seah1006.careconnect.participant.repository.ParticipantRepository;
import io.github.seah1006.careconnect.participant.web.ParticipantController;
import java.util.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@net.jqwik.api.Tag("INV-API-001")
@org.junit.jupiter.api.Tag("INV-API-001")
class ParticipantApiTest {
    static final SecurityFixture SECURITY = new SecurityFixture();
    static final UUID USER = UUID.randomUUID(), ORG = UUID.randomUUID();
    static final Actor ACTOR = new Actor(USER, ORG);
    static final String TOKEN = SECURITY.access.issue(USER, ORG, StaffRole.ORG_ADMIN);
    static final ParticipantRepository REPOSITORY = mock(ParticipantRepository.class);
    static final CursorCodec CURSORS = new CursorCodec(new byte[32]);
    static final MockMvc MVC;
    static {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebConfig.class);
        context.refresh();
        MVC = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @org.springframework.boot.test.context.TestConfiguration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class, ParticipantController.class, ApiExceptionHandler.class})
    static class WebConfig {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() { return SECURITY.decoder; }
        @Bean ParticipantService service() { return new ParticipantService(new CurrentActor(), REPOSITORY, CURSORS); }
    }

    @Provide Arbitrary<String> names() {
        return Arbitraries.strings().alpha().ofMinLength(12).ofMaxLength(40).map(s -> "synthetic-" + s);
    }

    @Property(tries = 100) @net.jqwik.api.Tag("INV-AUTHZ-002")
    void privateErrorsAndForeignAndMissingAreByteIdentical(@ForAll("names") String displayName,
            @ForAll("names") String groupName) throws Exception {
        reset(REPOSITORY);
        UUID own = UUID.randomUUID(), foreign = UUID.randomUUID(), missing = UUID.randomUUID();
        when(REPOSITORY.find(ORG, own)).thenReturn(Optional.of(new ParticipantView(own, displayName, UUID.randomUUID())));
        when(REPOSITORY.facts(ACTOR)).thenReturn(new AccessPolicy.Facts(StaffRole.STAFF, Set.of(), false));
        var forbidden = request("/" + own, 403);
        assertThat(forbidden.getContentAsString()).doesNotContain(displayName, groupName);
        var absent = request("/" + missing, 404);
        var other = request("/" + foreign, 404);
        assertThat(other.getContentAsByteArray()).isEqualTo(absent.getContentAsByteArray());
        assertThat(other.getContentType()).isEqualTo(absent.getContentType());
        assertThat(other.getContentAsString()).doesNotContain(displayName, groupName, foreign.toString(), missing.toString());
        assertThat(JsonMapper.builder().build().readTree(other.getContentAsString()).path("instance").isNull()).isTrue();
        verify(REPOSITORY, times(1)).facts(ACTOR);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test @org.junit.jupiter.api.Tag("INV-AUTHZ-003") @org.junit.jupiter.api.Tag("INV-AUTHZ-005")
    void paginationValidationAuthorizationAndSuccess() throws Exception {
        reset(REPOSITORY);
        request("", 401, false);
        request("/" + UUID.randomUUID(), 401, false);
        for (String query : List.of("?size=0", "?size=101", "?size=invalid", "?cursor=invalid")) request(query, 422);
        request("?cursor=" + CURSORS.encode(new Cursor("another-resource", UUID.randomUUID())), 422);
        when(REPOSITORY.facts(ACTOR)).thenReturn(new AccessPolicy.Facts(null, Set.of(), true));
        request("", 403);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var a = new ParticipantView(first, "synthetic-a", null);
        var b = new ParticipantView(second, "synthetic-b", null);
        when(REPOSITORY.facts(ACTOR)).thenReturn(new AccessPolicy.Facts(StaffRole.ORG_ADMIN, Set.of(), false));
        when(REPOSITORY.list(ORG, true, Set.of(), null, 2)).thenReturn(List.of(a, b));
        var page = request("?size=1", 200);
        var json = JsonMapper.builder().build().readTree(page.getContentAsString());
        assertThat(json.path("items").size()).isEqualTo(1);
        assertThat(json.path("items").get(0).path("id").asString()).isEqualTo(first.toString());
        String cursor = json.path("nextCursor").asString();
        assertThat(CURSORS.decode(cursor)).isEqualTo(new Cursor("participants:id", first));
        when(REPOSITORY.list(ORG, true, Set.of(), first, 2)).thenReturn(List.of(b));
        var last = JsonMapper.builder().build().readTree(request("?size=1&cursor=" + cursor, 200).getContentAsString());
        assertThat(last.path("items").get(0).path("id").asString()).isEqualTo(second.toString());
        assertThat(last.path("nextCursor").isNull()).isTrue();
        when(REPOSITORY.list(ORG, true, Set.of(), null, 101)).thenReturn(List.of());
        request("?size=100&cursor=", 200);
        when(REPOSITORY.find(ORG, first)).thenReturn(Optional.of(a));
        assertThat(request("/" + first, 200).getContentAsString()).contains("synthetic-a");
        when(REPOSITORY.facts(ACTOR)).thenReturn(new AccessPolicy.Facts(StaffRole.STAFF, Set.of(), false));
        when(REPOSITORY.list(ORG, false, Set.of(), null, 21)).thenReturn(List.of());
        request("", 200);
        when(REPOSITORY.facts(ACTOR)).thenReturn(new AccessPolicy.Facts(null, Set.of(), false));
        request("/" + first, 403);
    }

    private static org.springframework.mock.web.MockHttpServletResponse request(String suffix, int expected) throws Exception {
        return request(suffix, expected, true);
    }
    private static org.springframework.mock.web.MockHttpServletResponse request(String suffix, int expected, boolean auth) throws Exception {
        var request = get("/api/v1/participants" + suffix);
        if (auth) request.header("Authorization", "Bearer " + TOKEN);
        var result = MVC.perform(request).andExpect(status().is(expected));
        if (expected != 200) result.andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        return result.andReturn().getResponse();
    }
}
