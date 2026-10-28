package io.github.seah1006.careconnect.common.security;

import java.time.Clock;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

public final class SecurityFixture {
    // Synthetic public test material, used only by tests.
    public static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    public final JwtConfiguration config = new JwtConfiguration();
    public final SecretKey key = config.jwtKey(SECRET);
    public final JwtEncoder encoder = config.jwtEncoder(key);
    public final JwtDecoder decoder = config.jwtDecoder(key);
    public final PasswordEncoder passwords = config.passwordEncoder();
    public final Clock clock = Clock.systemUTC();
    public final AccessTokens access = new AccessTokens(encoder, clock);
}
