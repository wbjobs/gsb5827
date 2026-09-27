package com.gsb.resilience;

/**
 * Time source used by the executor for backoff sleeping and circuit breaker
 * timing. Tests inject a virtual implementation so no real waiting happens.
 */
public interface Clock {

    long currentTimeMillis();

    void sleep(long millis);
}
