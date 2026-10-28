package io.github.seah1006.careconnect.identity;

import io.github.seah1006.careconnect.common.security.*;
import io.github.seah1006.careconnect.common.tenancy.TenantContext;
import io.github.seah1006.careconnect.common.web.ApiExceptionHandler;
import io.github.seah1006.careconnect.identity.application.*;
import io.github.seah1006.careconnect.identity.domain.StaffRole;
import io.github.seah1006.careconnect.identity.web.AuthController;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@net.jqwik.api.Tag("INV-API-001")
@org.junit.jupiter.api.Tag("INV-API-001")
class AuthApiTest {
    static final SecurityFixture SECURITY = new SecurityFixture();
    static final MemoryAuthStore STORE = new MemoryAuthStore();
    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final MockMvc MVC;
    static {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebConfig.class);
        context.refresh();
        MVC = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @org.springframework.boot.test.context.TestConfiguration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class, AuthController.class, ApiExceptionHandler.class})
    static class WebConfig {
        @Bean ObjectMapper objectMapper() { return MAPPER; }
        @Bean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() { return SECURITY.decoder; }
        @Bean AuthService authService() { return new AuthService(STORE, SECURITY.passwords, SECURITY.access, SECURITY.clock); }
    }

    @Provide Arbitrary<String> inputSecrets() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(12).ofMaxLength(40)
                .map(s -> "synthetic-" + s);
    }

    @Property(tries = 100)
    void failedRequestsNeverEchoCredentials(@ForAll("inputSecrets") String username,
            @ForAll("inputSecrets") String password, @ForAll("inputSecrets") String token) throws Exception {
        assertPrivate(MVC.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(MAPPER.writeValueAsString(Map.of("organizationId", UUID.randomUUID().toString(),
                        "username", username, "password", password)))), 401, username, password, token);
        assertPrivate(MVC.perform(post("/api/v1/auth/refresh").contentType("application/json")
                .content(MAPPER.writeValueAsString(Map.of("refreshToken", token)))), 401, username, password, token);
        assertPrivate(MVC.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)),
                401, username, password, token);
        assertPrivate(MVC.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(MAPPER.writeValueAsString(Map.of("organizationId", token, "username", username, "password", password)))),
                422, username, password, token);
        assertThat(TenantContext.current()).isEmpty();
    }

    static void assertPrivate(ResultActions response, int status, String... secrets) throws Exception {
        var result = response.andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn();
        for (String secret : secrets) assertThat(result.getResponse().getContentAsString()).doesNotContain(secret);
    }

    @Test @org.junit.jupiter.api.Tag("INV-TEN-003") @org.junit.jupiter.api.Tag("INV-TEN-004")
    void loginMeRefreshLogoutAndValidationUseRealSecurityChain() throws Exception {
        UUID user = UUID.randomUUID(), org = UUID.randomUUID();
        STORE.users.put("synthetic-api-user", new AuthStore.User(user, SECURITY.passwords.encode("synthetic-password")));
        STORE.memberships.put(user, Map.of(org, StaffRole.STAFF));
        var login = MVC.perform(post("/api/v1/auth/login").contentType("application/json").content(MAPPER.writeValueAsString(
                Map.of("organizationId", org, "username", "synthetic-api-user", "password", "synthetic-password"))))
                .andExpect(status().isOk()).andReturn();
        var pair = MAPPER.readTree(login.getResponse().getContentAsString());
        MVC.perform(get("/api/v1/me").header("Authorization", "Bearer " + pair.path("accessToken").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.organizationId").value(org.toString()))
                .andExpect(jsonPath("$.userId").value(user.toString())).andExpect(jsonPath("$.role").value("STAFF"));
        assertThat(TenantContext.current()).isEmpty();
        String refreshBody = MAPPER.writeValueAsString(Map.of("refreshToken", pair.path("refreshToken").asString()));
        MVC.perform(post("/api/v1/auth/refresh").contentType("application/json").content(refreshBody)).andExpect(status().isOk());
        MVC.perform(post("/api/v1/auth/logout").contentType("application/json").content(refreshBody)).andExpect(status().isNoContent());
        MVC.perform(post("/api/v1/auth/logout").contentType("application/json").content("{\"refreshToken\":\"synthetic-unknown\"}"))
                .andExpect(status().isNoContent());
        for (String path : new String[]{"login", "refresh", "logout"}) {
            assertPrivate(MVC.perform(post("/api/v1/auth/" + path).contentType("application/json").content("{}")), 422);
        }
        assertPrivate(MVC.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(MAPPER.writeValueAsString(Map.of("organizationId", org, "username", "synthetic-api-user",
                        "password", "가".repeat(25))))), 422, "가".repeat(25));
        assertPrivate(MVC.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(MAPPER.writeValueAsString(Map.of("organizationId", org, "username", "synthetic-api-user",
                        "password", "가".repeat(24))))), 401, "가".repeat(24));
        MVC.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.errors.organizationId").isArray())
                .andExpect(jsonPath("$.errors.username").isArray()).andExpect(jsonPath("$.errors.password").isArray());
        assertPrivate(MVC.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"organizationId\":\"synthetic-bad-json")), 422, "synthetic-bad-json");
        String failureBody = null;
        for (Map<String, Object> input : java.util.List.of(
                Map.<String, Object>of("organizationId", org, "username", "synthetic-missing-user", "password", "synthetic-password"),
                Map.<String, Object>of("organizationId", org, "username", "synthetic-api-user", "password", "synthetic-wrong-password"),
                Map.<String, Object>of("organizationId", UUID.randomUUID(), "username", "synthetic-api-user", "password", "synthetic-password"))) {
            var response = MVC.perform(post("/api/v1/auth/login").contentType("application/json")
                    .content(MAPPER.writeValueAsString(input))).andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json")).andReturn().getResponse();
            if (failureBody == null) failureBody = response.getContentAsString();
            assertThat(response.getContentAsString()).isEqualTo(failureBody);
        }
        assertPrivate(MVC.perform(get("/api/v1/me")), 401);
        assertThat(login.getResponse().getCookie("JSESSIONID")).isNull();
    }
}
