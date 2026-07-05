package dev.anmol.webhooks.controller;

import dev.anmol.webhooks.exception.InvalidSignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 401 for bad signatures. Delibrately generic message in the response -
     * detailed reasons go to logs only, so attackers probing the endpoint
     * learn nothing about which check falied.
     */
    @ExceptionHandler(InvalidSignatureException.class)
    public ResponseEntity<Map<String, String>> invalidSignature(InvalidSignatureException e) {
        log.warn("Rejected webhook: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "signature verification failed"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
