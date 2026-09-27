package com.gsb.resilience;

/**
 * Computes the delay before a given retry. {@code retryAttempt} is 1-based:
 * 1 is the delay before the first retry.
 */
public interface BackoffStrategy {

    long delayMillis(int retryAttempt);

    static BackoffStrategy fixed(final long delayMillis) {
        if (delayMillis < 0L) {
            throw new IllegalArgumentException("delayMillis must be >= 0");
        }
        return new BackoffStrategy() {
            @Override
            public long delayMillis(int retryAttempt) {
                return delayMillis;
            }
        };
    }

    static BackoffStrategy exponential(final long initialDelayMillis,
                                       final double multiplier,
                                       final long maxDelayMillis) {
        if (initialDelayMillis < 0L) {
            throw new IllegalArgumentException("initialDelayMillis must be >= 0");
        }
        if (multiplier < 1.0d) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
        if (maxDelayMillis < 0L) {
            throw new IllegalArgumentException("maxDelayMillis must be >= 0");
        }
        return new BackoffStrategy() {
            @Override
            public long delayMillis(int retryAttempt) {
                double delay = initialDelayMillis * Math.pow(multiplier, retryAttempt - 1);
                if (delay >= maxDelayMillis) {
                    return maxDelayMillis;
                }
                return (long) delay;
            }
        };
    }
}
