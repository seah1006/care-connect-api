package io.github.seah1006.careconnect.common.security;

import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;

@Tag("INV-TEN-003")
class TenantRequestFilterTest {
    @Test void bindsOrgForWholeChainAndUnwindsOnEveryExit() throws Exception {
        var security = new SecurityFixture();
        UUID org = UUID.randomUUID();
        var jwt = security.decoder.decode(security.access.issue(UUID.randomUUID(), org, StaffRole.STAFF));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
        try {
            var filter = new TenantRequestFilter();
            boolean[] called = {false};
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
                called[0] = true;
                assertThat(TenantContext.require()).isEqualTo(org);
            });
            assertThat(called[0]).isTrue();
            for (Exception failure : new Exception[]{new IOException("synthetic"), new ServletException("synthetic"),
                    new IllegalArgumentException("synthetic")}) {
                assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
                    assertThat(TenantContext.require()).isEqualTo(org);
                    if (failure instanceof IOException io) throw io;
                    if (failure instanceof ServletException servlet) throw servlet;
                    throw (RuntimeException) failure;
                })).isSameAs(failure);
                assertThat(TenantContext.current()).isEmpty();
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test void anonymousRequestDoesNotBindTenant() throws Exception {
        SecurityContextHolder.clearContext();
        boolean[] called = {false};
        new TenantRequestFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
            called[0] = true;
            assertThat(TenantContext.current()).isEmpty();
        });
        assertThat(called[0]).isTrue();
    }
}
