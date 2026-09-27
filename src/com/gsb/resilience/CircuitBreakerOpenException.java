package com.gsb.resilience;

/**
 * Thrown when a call is rejected by an OPEN (or saturated HALF_OPEN) circuit
 * breaker. The downstream action is never invoked in this case.
 */
public class CircuitBreakerOpenException extends RuntimeException {

    public CircuitBreakerOpenException(String name) {
        super("Circuit breaker '" + name + "' is OPEN; call rejected without touching downstream");
    }
}
