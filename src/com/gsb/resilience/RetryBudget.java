package com.gsb.resilience;

/**
 * Tracks the retry budget of one channel: a retry is only allowed while
 * retries / totalCalls stays within the configured ratio. Only calls that
 * actually reached the downstream action are counted; calls rejected by an
 * open circuit breaker are not.
 */
final class RetryBudget {

    private final double ratio;
    private long totalCalls;
    private long retries;

    RetryBudget(double ratio) {
        this.ratio = ratio;
    }

    synchronized void recordCall() {
        totalCalls++;
    }

    synchronized boolean tryAcquireRetry() {
        if (totalCalls > 0L && (retries + 1L) <= ratio * totalCalls) {
            retries++;
            return true;
        }
        return false;
    }
}
