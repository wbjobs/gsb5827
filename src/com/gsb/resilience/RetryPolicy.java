package com.gsb.resilience;

import java.util.function.Predicate;

public class RetryPolicy {
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

    public Predicate<Throwable> getRetryable() {
        return retryable;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxAttempts = 3;
        private BackoffStrategy backoff = BackoffStrategy.fixed(100);
        private double retryBudgetRatio = 0.2;
        private Predicate<Throwable> retryable = new Predicate<Throwable>() {
            @Override
            public boolean test(Throwable t) {
                return true;
            }
        };

        private Builder() {
        }

        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder backoff(BackoffStrategy backoff) {
            if (backoff == null) {
                throw new IllegalArgumentException("backoff must not be null");
            }
            this.backoff = backoff;
            return this;
        }

        public Builder retryBudgetRatio(double retryBudgetRatio) {
            if (retryBudgetRatio < 0.0) {
                throw new IllegalArgumentException("retryBudgetRatio must be >= 0");
            }
            this.retryBudgetRatio = retryBudgetRatio;
            return this;
        }

        public Builder retryOn(Predicate<Throwable> retryable) {
            if (retryable == null) {
                throw new IllegalArgumentException("retryable predicate must not be null");
            }
            this.retryable = retryable;
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}
