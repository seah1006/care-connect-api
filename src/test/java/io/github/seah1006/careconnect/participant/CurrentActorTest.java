package io.github.seah1006.careconnect.participant;

import io.github.seah1006.careconnect.common.authz.*;
import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;

@Tag("INV-AUTHZ-005")
class CurrentActorTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void requiresAuthenticatedJwtAndTenantAndReadsOnlySubject() {
        var current = new CurrentActor();
        assertThatThrownBy(current::require).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("synthetic", "synthetic"));
        assertThatThrownBy(current::require).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        UUID user = UUID.randomUUID(), org = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("synthetic").header("alg", "HS256").subject(user.toString()).claim("role", "IGNORED").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        assertThatThrownBy(current::require).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
        assertThatThrownBy(current::require).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.callAs(org, current::require)).isEqualTo(new Actor(user, org));
    }
}
