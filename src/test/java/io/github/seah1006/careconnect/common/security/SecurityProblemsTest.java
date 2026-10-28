package io.github.seah1006.careconnect.common.security;

import io.github.seah1006.careconnect.common.web.SecurityProblems;
import io.github.seah1006.careconnect.identity.application.AuthenticationFailed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Tag("INV-API-001")
class SecurityProblemsTest {
    @Test void securityHandlersWriteOnlyGenericProblemDetails() throws Exception {
        var mapper = JsonMapper.builder().build();
        var denied = new MockHttpServletResponse();
        SecurityProblems.deniedHandler(mapper).handle(new MockHttpServletRequest(), denied,
                new AccessDeniedException("synthetic-secret-input"));
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(denied.getContentType()).isEqualTo("application/problem+json");
        assertThat(mapper.readTree(denied.getContentAsString()).path("detail").asString()).isEqualTo("Access denied");
        assertThat(denied.getContentAsString()).doesNotContain("synthetic-secret-input");
        var unauthorized = new MockHttpServletResponse();
        SecurityProblems.entryPoint(mapper).commence(new MockHttpServletRequest(), unauthorized, new AuthenticationFailed());
        assertThat(unauthorized.getStatus()).isEqualTo(401);
        assertThat(unauthorized.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(unauthorized.getContentType()).isEqualTo("application/problem+json");
        assertThat(mapper.readTree(unauthorized.getContentAsString()).path("detail").asString()).isEqualTo("Authentication failed");
    }
}
