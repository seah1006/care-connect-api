package io.github.seah1006.careconnect.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class JwtConfiguration {
    @Bean
    SecretKey jwtKey(@Value("${security.jwt-secret}") String encoded) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("JWT_SECRET must be Base64 encoding at least 32 bytes");
        }
        if (key.length < 32) throw new IllegalArgumentException("JWT_SECRET must encode at least 32 bytes");
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), jwt -> {
                    try {
                        UUID.fromString(jwt.getSubject());
                        UUID.fromString(jwt.getClaimAsString("org"));
                        StaffRole.valueOf(jwt.getClaimAsString("role"));
                        if (jwt.getId() == null || jwt.getIssuedAt() == null || jwt.getExpiresAt() == null) {
                            throw new IllegalArgumentException();
                        }
                        return OAuth2TokenValidatorResult.success();
                    } catch (RuntimeException invalid) {
                        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                    }
                }));
        return decoder;
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    @Bean
    AccessTokens accessTokens(JwtEncoder encoder, Clock clock) { return new AccessTokens(encoder, clock); }
}
