package io.github.seah1006.careconnect.common.security;

import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public final class TenantRequestFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            // The decoder validates claim types and values before this filter runs.
            try {
                TenantContext.<Void, Exception>callAs(UUID.fromString(jwt.getToken().getClaimAsString("org")), () -> {
                    chain.doFilter(request, response);
                    return null;
                });
            } catch (IOException | ServletException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new ServletException("Request processing failed", failure);
            }
        } else {
            chain.doFilter(request, response);
        }
    }
}
