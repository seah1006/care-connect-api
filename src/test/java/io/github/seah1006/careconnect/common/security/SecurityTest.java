package io.github.seah1006.careconnect.common.security;

import io.github.seah1006.careconnect.identity.domain.StaffRole;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import static org.assertj.core.api.Assertions.*;

class SecurityTest {
    private final SecurityFixture fixture = new SecurityFixture();

    @Test @Tag("INV-TEN-004")
    void accessClaimsAndLifetimeAreBoundToMembership() {
        UUID user = UUID.randomUUID(), org = UUID.randomUUID();
        Jwt jwt = fixture.decoder.decode(fixture.access.issue(user, org, StaffRole.STAFF));
        assertThat(jwt.getSubject()).isEqualTo(user.toString());
        assertThat(jwt.getClaimAsString("org")).isEqualTo(org.toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("STAFF");
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(fixture.decoder.decode(fixture.access.issue(user, org, StaffRole.STAFF)).getId()).isNotEqualTo(jwt.getId());
    }

    @Test @Tag("INV-TEN-004")
    void rejectsBadKeysSignaturesExpiredAndIncompleteClaims() {
        assertThatThrownBy(() -> fixture.config.jwtKey("invalid-base64!" )).isInstanceOf(IllegalArgumentException.class);
        for (int length : new int[]{0, 31}) {
            assertThatThrownBy(() -> fixture.config.jwtKey(Base64.getEncoder().encodeToString(new byte[length])))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var now = fixture.clock.instant();
        for (String org : new String[]{"bad-uuid", UUID.randomUUID().toString()}) {
            var claims = JwtClaimsSet.builder().subject(UUID.randomUUID().toString()).claim("org", org)
                    .claim("role", "STAFF").issuedAt(now).expiresAt(now.plusSeconds(900)).build();
            assertThatThrownBy(() -> fixture.decoder.decode(sign(claims))).isInstanceOf(JwtException.class);
        }
        var expired = JwtClaimsSet.builder().subject(UUID.randomUUID().toString()).claim("org", UUID.randomUUID().toString())
                .claim("role", "STAFF").id("synthetic").issuedAt(now.minusSeconds(2000)).expiresAt(now.minusSeconds(1000)).build();
        assertThatThrownBy(() -> fixture.decoder.decode(sign(expired))).isInstanceOf(JwtException.class);
        String valid = fixture.access.issue(UUID.randomUUID(), UUID.randomUUID(), StaffRole.STAFF);
        assertThatThrownBy(() -> fixture.decoder.decode(valid.substring(0, valid.length() - 8) + "AAAAAAAA"))
                .isInstanceOf(JwtException.class);
    }

    private String sign(JwtClaimsSet claims) {
        return fixture.encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @net.jqwik.api.Tag("INV-AUTH-003")
    @Property(tries = 100)
    void passwordsAndRefreshSecretsAreOneWay(@ForAll("rawSecrets") String raw) {
        String encoded = fixture.passwords.encode(raw);
        assertThat(encoded).isNotEqualTo(raw).startsWith("{bcrypt}");
        assertThat(fixture.passwords.matches(raw, encoded)).isTrue();
        assertThat(fixture.passwords.matches(raw + "!", encoded)).isFalse();
        assertThat(RefreshSecrets.hash(raw)).matches("[0-9a-f]{64}").isNotEqualTo(raw);
        String generated = RefreshSecrets.generate();
        assertThat(generated).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(generated)).hasSize(32);
        assertThat(RefreshSecrets.generate()).isNotEqualTo(generated);
        assertThat(RefreshSecrets.hash(generated)).matches("[0-9a-f]{64}").isNotEqualTo(generated);
    }

    @Provide Arbitrary<String> rawSecrets() {
        return Arbitraries.strings().ascii().ofMinLength(1).ofMaxLength(50);
    }

    @Test @Tag("INV-AUTH-003")
    void hashesUseStandardSha256() {
        assertThat(RefreshSecrets.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
