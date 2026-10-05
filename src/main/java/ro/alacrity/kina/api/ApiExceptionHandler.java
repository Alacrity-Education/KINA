package ro.alacrity.kina.api;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.domain.UnknownDistributorException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 9457 {@code application/problem+json} errors for the REST API only (scoped to this package so the OAuth and
 * web error handling stay untouched). Problem types are {@code urn:kina:problem:<name>}; responses never contain stack
 * traces or exception internals.
 */
@RestControllerAdvice(basePackages = "ro.alacrity.kina.api")
@Slf4j
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final URI VALIDATION = URI.create("urn:kina:problem:validation");
    static final URI UNKNOWN_DISTRIBUTOR = URI.create("urn:kina:problem:unknown-distributor");
    static final URI NOT_FOUND = URI.create("urn:kina:problem:not-found");
    static final URI DISTRIBUTOR_ERROR = URI.create("urn:kina:problem:distributor-error");
    static final URI INTERNAL = URI.create("urn:kina:problem:internal");

    @ExceptionHandler(UnknownDistributorException.class)
    ResponseEntity<Object> unknownDistributor(UnknownDistributorException e, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, UNKNOWN_DISTRIBUTOR, "Unknown distributor",
                e.getMessage());
        return handleExceptionInternal(e, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(PartNotFoundException.class)
    ResponseEntity<Object> notFound(PartNotFoundException e, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, NOT_FOUND, "Part not found", e.getMessage());
        problem.setProperty("distributor", e.distributor());
        problem.setProperty("part_number", e.partNumber());
        problem.setProperty("reason", e.reason());
        if (e.identity() != null) {
            problem.setProperty("identity", e.identity());
        }
        return handleExceptionInternal(e, problem, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraintViolation(ConstraintViolationException e, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION, "Invalid request", e.getMessage());
        return handleExceptionInternal(e, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> illegalArgument(IllegalArgumentException e, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION, "Invalid request", e.getMessage());
        return handleExceptionInternal(e, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    /** Lookup failures: not configured / unavailable 503, rate limited 429, timeout 504, bad response 502. */
    @ExceptionHandler(DistributorException.class)
    ResponseEntity<Object> distributor(DistributorException e, WebRequest request) {
        HttpStatus status = switch (e.kind()) {
            case NOT_CONFIGURED, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case BAD_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
        ProblemDetail problem = problem(status, DISTRIBUTOR_ERROR, "Distributor error",
                e.distributor() + " lookup failed: " + e.errorCode());
        problem.setProperty("distributor", e.distributor());
        problem.setProperty("error", e.errorCode());
        return handleExceptionInternal(e, problem, new HttpHeaders(), status, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
        log.error("Unexpected error in {}", request.getDescription(false), e);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL, "Internal server error",
                "An unexpected error occurred.");
        return handleExceptionInternal(e, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("field", fieldError.getField());
            error.put("message", fieldError.getDefaultMessage());
            errors.add(error);
        }
        e.getBindingResult().getGlobalErrors().forEach(g -> errors.add(Map.of("message",
                String.valueOf(g.getDefaultMessage()))));
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION, "Invalid request",
                "Request body validation failed.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(e, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<Map<String, Object>> errors = new ArrayList<>();
        e.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            var requestParam = result.getMethodParameter()
                    .getParameterAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
            if (requestParam != null && !requestParam.name().isEmpty()) {
                name = requestParam.name();
            } else if (requestParam != null && !requestParam.value().isEmpty()) {
                name = requestParam.value();
            }
            for (var error : result.getResolvableErrors()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("field", name);
                entry.put("message", error.getDefaultMessage());
                errors.add(entry);
            }
        });
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION, "Invalid request",
                "Request parameter validation failed.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(e, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        UnknownDistributorException unknown = findCause(e, UnknownDistributorException.class);
        ProblemDetail problem = unknown != null
                ? problem(HttpStatus.BAD_REQUEST, UNKNOWN_DISTRIBUTOR, "Unknown distributor", unknown.getMessage())
                : problem(HttpStatus.BAD_REQUEST, VALIDATION, "Invalid request",
                "Request body is missing or is not valid JSON for this endpoint.");
        return handleExceptionInternal(e, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }

    private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }
}
