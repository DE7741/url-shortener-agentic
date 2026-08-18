package com.assessment.shortener.web;

import com.assessment.shortener.service.UrlShortenerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UrlShortenerService.ValidationException.class)
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(UrlShortenerService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        return error(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(UrlShortenerService.ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return error(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(UrlShortenerService.ExpiredException.class)
    public ResponseEntity<Map<String, String>> gone(RuntimeException e) {
        return error(HttpStatus.GONE, e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> illegalArgument(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, e);
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, Exception e) {
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage() == null ? "error" : e.getMessage()));
    }
}
