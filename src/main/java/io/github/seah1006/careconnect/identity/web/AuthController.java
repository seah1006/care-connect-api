package io.github.seah1006.careconnect.identity.web;

import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import io.github.seah1006.careconnect.identity.application.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    public record Login(@NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String organizationId,
            @NotBlank @Size(max = 255) String username,
            @NotBlank @Size(max = 72) String password) {
        @AssertTrue
        public boolean isPasswordLengthValid() {
            return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
        }
    }
    public record Refresh(@NotBlank @Size(max = 512) String refreshToken) {}
    public record Me(UUID userId, UUID organizationId, String role) {}

    private final AuthService service;

    public AuthController(AuthService service) { this.service = service; }

    @PostMapping("/auth/login")
    public AuthService.Tokens login(@Valid @RequestBody Login body) {
        return service.login(UUID.fromString(body.organizationId()), body.username(), body.password());
    }

    @PostMapping("/auth/refresh")
    public AuthService.Tokens refresh(@Valid @RequestBody Refresh body) {
        return service.refresh(body.refreshToken());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody Refresh body) { service.logout(body.refreshToken()); }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal Jwt jwt) {
        return new Me(UUID.fromString(jwt.getSubject()), TenantContext.require(), jwt.getClaimAsString("role"));
    }
}
