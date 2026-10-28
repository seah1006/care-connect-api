package io.github.seah1006.careconnect;

import io.github.seah1006.careconnect.common.security.SecurityFixture;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
public abstract class PostgresSupport {
    @Container
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withUsername("care_test_owner").withPassword("synthetic-owner-password").withInitScript("db/init.sql");

    public static void properties(java.util.function.BiConsumer<String, Object> set) {
        set.accept("spring.datasource.url", POSTGRES.getJdbcUrl());
        set.accept("spring.datasource.username", "care_test_app");
        set.accept("spring.datasource.password", "synthetic-app-password");
        set.accept("spring.flyway.url", POSTGRES.getJdbcUrl());
        set.accept("spring.flyway.user", POSTGRES.getUsername());
        set.accept("spring.flyway.password", POSTGRES.getPassword());
        set.accept("spring.flyway.placeholders.applicationUser", "care_test_app");
        set.accept("security.jwt-secret", SecurityFixture.SECRET);
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        properties((name, value) -> registry.add(name, () -> value));
    }
}
