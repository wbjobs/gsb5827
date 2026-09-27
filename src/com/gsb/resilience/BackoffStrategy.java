package com.gsb.resilience;

public abstract class BackoffStrategy {

    public abstract long delayMillis(int retryNumber);

    public static BackoffStrategy fixed(final long intervalMillis) {
        if (intervalMillis < 0) {
            throw new IllegalArgumentException("intervalMillis must be >= 0");
        }
        return new BackoffStrategy() {
            @Override
            public long delayMillis(int retryNumber) {
                return intervalMillis;
            }

            @Override
            public String toString() {
                return "FixedBackoff(" + intervalMillis + "ms)";
            }
        };
    }

    public static BackoffStrategy exponential(final long initialMillis, final double multiplier, final long maxMillis) {
        if (initialMillis < 0 || maxMillis < 0) {
            throw new IllegalArgumentException("backoff durations must be >= 0");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
        return new BackoffStrategy() {
            @Override
            public long delayMillis(int retryNumber) {
                double delay = initialMillis * Math.pow(multiplier, retryNumber - 1.0);
                if (delay >= maxMillis) {
                    return maxMillis;
                }
                return (long) delay;
            }

            @Override
            public String toString() {
                return "ExponentialBackoff(initial=" + initialMillis + "ms, multiplier=" + multiplier + ", max=" + maxMillis + "ms)";
            }
        };
    }
}
