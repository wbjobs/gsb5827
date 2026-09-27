package com.gsb.resilience;

import java.util.function.Predicate;

/**
 * Retry configuration for a named channel.
 *
 * maxAttempts counts the initial call, so maxAttempts=3 means 1 initial call
 * plus up to 2 retries. retryBudgetRatio caps retries as
 * retries / totalCalls across the whole channel lifetime.
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final BackoffStrategy backoff;
    private final double retryBudgetRatio;
    private final Predicate<Throwable> retryable;

    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.backoff = builder.backoff;
        this.retryBudgetRatio = builder.retryBudgetRatio;
        this.retryable = builder.retryable;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public BackoffStrategy getBackoff() {
        return backoff;
    }

    public double getRetryBudgetRatio() {
        return retryBudgetRatio;
    }

    public boolean isRetryable(Throwable failure) {
        return retryable.test(failure);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxAttempts = 3;
        private BackoffStrategy backoff = BackoffStrategy.fixed(0L);
        private double retryBudgetRatio = 1.0d;
        private Predicate<Throwable> retryable = new Predicate<Throwable>() {
            @Override
            public boolean test(Throwable failure) {
                return true;
            }
        };

        private Builder() {
        }

        public Builder withMaxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder withBackoff(BackoffStrategy backoff) {
            if (backoff == null) {
                throw new IllegalArgumentException("backoff must not be null");
            }
            this.backoff = backoff;
            return this;
        }

        public Builder withRetryBudgetRatio(double retryBudgetRatio) {
            if (retryBudgetRatio < 0.0d) {
                throw new IllegalArgumentException("retryBudgetRatio must be >= 0");
            }
            this.retryBudgetRatio = retryBudgetRatio;
            return this;
        }

        public Builder withRetryable(Predicate<Throwable> retryable) {
            if (retryable == null) {
                throw new IllegalArgumentException("retryable must not be null");
            }
            this.retryable = retryable;
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}
