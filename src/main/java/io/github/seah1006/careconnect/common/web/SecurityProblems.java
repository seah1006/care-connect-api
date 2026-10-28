package io.github.seah1006.careconnect.common.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

public final class SecurityProblems {
    private SecurityProblems() {}

    public static AuthenticationEntryPoint entryPoint(ObjectMapper mapper) {
        return (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(mapper, response, HttpStatus.UNAUTHORIZED, "Authentication failed");
        };
    }

    public static AccessDeniedHandler deniedHandler(ObjectMapper mapper) {
        return (request, response, exception) -> write(mapper, response, HttpStatus.FORBIDDEN, "Access denied");
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}
