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

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> business(ApiException error, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).body(new ApiError(error.code(), error.getMessage(), requestId(request)));
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> invalid(Exception error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("INVALID_REQUEST", "Invalid request parameters", requestId(request)));
    }
    public static String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-Id");
        return value != null && value.matches("[A-Za-z0-9._-]{1,64}") ? value : UUID.randomUUID().toString();
    }
}
