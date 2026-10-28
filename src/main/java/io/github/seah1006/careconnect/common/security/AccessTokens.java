package io.github.seah1006.careconnect.common.security;

import io.github.seah1006.careconnect.identity.domain.StaffRole;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

public final class AccessTokens {
    private final JwtEncoder encoder;
    private final Clock clock;

    public AccessTokens(JwtEncoder encoder, Clock clock) {
        this.encoder = encoder;
        this.clock = clock;
    }

    public String issue(UUID user, UUID org, StaffRole role) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().subject(user.toString()).claim("org", org.toString())
                .claim("role", role.name()).id(UUID.randomUUID().toString()).issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15))).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
