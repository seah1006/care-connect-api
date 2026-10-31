package io.github.seah1006.careconnect.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import io.github.seah1006.careconnect.common.authz.AccessDenied;
import io.github.seah1006.careconnect.common.authz.ResourceNotFound;
import io.github.seah1006.careconnect.common.pagination.InvalidCursorException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.ObjectMapper;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ObjectMapper mapper;

    public ApiExceptionHandler(ObjectMapper mapper) { this.mapper = mapper; }

    @ExceptionHandler(DataAccessException.class)
    public void storageFailure(DataAccessException failure, HttpServletRequest request, HttpServletResponse response) throws IOException {
        UUID requestId = (UUID) request.getAttribute("requestId");
        if (requestId == null) {
            requestId = UUID.randomUUID();
            request.setAttribute("requestId", requestId);
        }
        log.error("Storage failure: exception={} requestId={}", failure.getMostSpecificCause().getClass().getName(), requestId);
        SecurityProblems.write(mapper, response, HttpStatus.INTERNAL_SERVER_ERROR, "Request failed");
    }

    @ExceptionHandler(AccessDenied.class)
    public void forbidden(HttpServletResponse response) throws IOException {
        SecurityProblems.write(mapper, response, HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(ResourceNotFound.class)
    public void notFound(HttpServletResponse response) throws IOException {
        // Write directly so Spring cannot insert the requested resource URI as instance.
        SecurityProblems.write(mapper, response, HttpStatus.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler({InvalidCursorException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public void invalidPagination(HttpServletResponse response) throws IOException {
        SecurityProblems.write(mapper, response, HttpStatus.UNPROCESSABLE_ENTITY, "Invalid request");
    }

    @ExceptionHandler(AuthenticationException.class)
    public void unauthorized(AuthenticationException failure, HttpServletRequest request, HttpServletResponse response)
            throws IOException, jakarta.servlet.ServletException {
        SecurityProblems.entryPoint(mapper).commence(request, response, failure);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class})
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ProblemDetail invalidRequest(Exception failure) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid request");
        Map<String, List<String>> errors = new LinkedHashMap<>();
        if (failure instanceof MethodArgumentNotValidException validation) {
            validation.getBindingResult().getFieldErrors().forEach(error ->
                    errors.put(error.getField(), List.of("Invalid format or missing required field")));
        } else {
            errors.put("request", List.of("Invalid format or missing required fields"));
        }
        problem.setProperty("errors", errors);
        return problem;
    }
}
