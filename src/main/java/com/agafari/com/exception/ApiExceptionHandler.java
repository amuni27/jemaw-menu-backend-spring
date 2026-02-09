package com.agafari.com.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<?> bad(BadRequestException e){ return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<?> forb(ForbiddenException e){ return ResponseEntity.status(403).body(Map.of("message", e.getMessage())); }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> nf(NotFoundException e){ return ResponseEntity.status(404).body(Map.of("message", e.getMessage())); }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<?> conf(ConflictException e){ return ResponseEntity.status(409).body(Map.of("message", e.getMessage())); }
}

