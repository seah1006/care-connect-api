package io.github.seah1006.careconnect;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @LocalServerPort
    int port;

    @Test
    void contextStartsWithPostgresBaselineMigrationAndHealthyHttpEndpoint() throws Exception {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '1' and success",
                Integer.class)).isEqualTo(1);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(response.body()).path("status").asString()).isEqualTo("UP");
        }
    }
}
