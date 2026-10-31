package io.github.seah1006.careconnect.common.authz;

import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CurrentActor {
    public Actor require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
        return new Actor(UUID.fromString(jwt.getToken().getSubject()), TenantContext.require());
    }
}
