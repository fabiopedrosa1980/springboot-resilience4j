package br.com.pedrosa.exception;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.TimeoutException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // Rate Limiter -> 429
    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<Void> handleRateLimit(RequestNotPermitted ex) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "60")
                .build();
    }

    // Circuit Breaker aberto -> 503
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ApiError> handleCircuitOpen(CallNotPermittedException ex) {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(new ApiError("SERVICE_UNAVAILABLE",
                        "O serviço está temporariamente indisponível. Tente novamente em instantes."));
    }

    // Time Limiter estourou -> 504
    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ApiError> handleTimeout(TimeoutException ex) {
        return ResponseEntity
                .status(HttpStatus.GATEWAY_TIMEOUT)
                .body(new ApiError("GATEWAY_TIMEOUT",
                        "O serviço demorou demais para responder."));
    }

    // Falha de conexão/timeout do RestClient -> 503
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiError> handleResourceAccess(ResourceAccessException ex) {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("SERVICE_UNAVAILABLE",
                        "Não foi possível se comunicar com o serviço de pedidos."));
    }

    public record ApiError(String code, String message) {}
}