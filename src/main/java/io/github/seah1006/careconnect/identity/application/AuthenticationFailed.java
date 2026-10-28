package io.github.seah1006.careconnect.identity.application;

import org.springframework.security.core.AuthenticationException;

public final class AuthenticationFailed extends AuthenticationException {
    public AuthenticationFailed() {
        super("Authentication failed");
    }
}
