package io.github.yibenwulong.seatflow.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> business(ApiException error, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).body(new ApiError(error.code(), error.getMessage(), requestId(request)));
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, ConstraintViolationException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> invalid(Exception error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("INVALID_REQUEST", "Invalid request parameters", requestId(request)));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(Exception error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", "Resource not found", requestId(request)));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(Exception error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new ApiError("METHOD_NOT_ALLOWED", "HTTP method not allowed", requestId(request)));
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(Exception error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new ApiError("UNSUPPORTED_MEDIA_TYPE", "Unsupported media type", requestId(request)));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("INTERNAL_ERROR", "An unexpected error occurred", requestId(request)));
    }
    @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class})
    ResponseEntity<ApiError> unavailable(Exception error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError("DEPENDENCY_UNAVAILABLE", "A required dependency is unavailable", requestId(request)));
    }
    public static String requestId(HttpServletRequest request) {
        Object existing = request.getAttribute(ApiExceptionHandler.class.getName() + ".requestId");
        if (existing instanceof String value) return value;
        String value = request.getHeader("X-Request-Id");
        String id = value != null && value.matches("[A-Za-z0-9._-]{1,64}") ? value : UUID.randomUUID().toString();
        request.setAttribute(ApiExceptionHandler.class.getName() + ".requestId", id);
        return id;
    }
}
