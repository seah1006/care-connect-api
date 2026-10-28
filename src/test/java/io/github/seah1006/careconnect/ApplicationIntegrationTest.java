package io.github.seah1006.careconnect;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration")
class ApplicationIntegrationTest extends PostgresSupport {

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
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9000");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '1' and success",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version in ('2', '9000') and success",
                Integer.class)).isEqualTo(2);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(response.body()).path("status").asString()).isEqualTo("UP");
        }
    }
}
