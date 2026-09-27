package com.gsb.resilience;

public class CircuitBreakerOpenException extends RuntimeException {
    public CircuitBreakerOpenException(String message) {
        super(message);
    }
}
