package io.github.seah1006.careconnect.common.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Tag("INV-AUDIT-003")
class ApiExceptionHandlerTest {
    @Test void storageFailuresLogOnlyExceptionTypeAndRequestIdentity() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var handler = new ApiExceptionHandler(JsonMapper.builder().build());
            var request = new MockHttpServletRequest();
            UUID resourceId = UUID.randomUUID(), requestId = UUID.randomUUID();
            request.setRequestURI("/api/v1/participants/" + resourceId);
            var failure = new DataAccessResourceFailureException("synthetic-wrapper-secret",
                    new IllegalStateException("synthetic-private-name SQL parameters: " + resourceId));
            for (boolean existing : new boolean[] {true, false}) {
                if (existing) request.setAttribute("requestId", requestId);
                else request.removeAttribute("requestId");
                var response = new MockHttpServletResponse();
                handler.storageFailure(failure, request, response);
                assertThat(response.getStatus()).isEqualTo(500);
                assertThat(response.getContentType()).startsWith("application/problem+json");
                assertThat(response.getContentAsString()).contains("Request failed")
                        .doesNotContain("synthetic-", "SQL", resourceId.toString(), "IllegalStateException");
                assertThat(request.getAttribute("requestId")).isInstanceOf(UUID.class);
                if (existing) assertThat(request.getAttribute("requestId")).isEqualTo(requestId);
                var logged = appender.list.getLast();
                assertThat(logged.getLevel()).isEqualTo(Level.ERROR);
                assertThat(logged.getFormattedMessage()).isEqualTo(
                        "Storage failure: exception=java.lang.IllegalStateException requestId=" + request.getAttribute("requestId"));
                assertThat(logged.getThrowableProxy()).isNull();
            }
            assertThat(appender.list).hasSize(2);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
