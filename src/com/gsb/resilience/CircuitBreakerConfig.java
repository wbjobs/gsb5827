package com.gsb.resilience;

public class CircuitBreakerConfig {
    private final int slidingWindowSize;
    private final double failureRateThresholdPercent;
    private final long openStateDurationMillis;
    private final int halfOpenPermittedCalls;

    private CircuitBreakerConfig(Builder builder) {
        this.slidingWindowSize = builder.slidingWindowSize;
        this.failureRateThresholdPercent = builder.failureRateThresholdPercent;
        this.openStateDurationMillis = builder.openStateDurationMillis;
        this.halfOpenPermittedCalls = builder.halfOpenPermittedCalls;
    }

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public double getFailureRateThresholdPercent() {
        return failureRateThresholdPercent;
    }

    public long getOpenStateDurationMillis() {
        return openStateDurationMillis;
    }

    public int getHalfOpenPermittedCalls() {
        return halfOpenPermittedCalls;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int slidingWindowSize = 10;
        private double failureRateThresholdPercent = 50.0;
        private long openStateDurationMillis = 1000;
        private int halfOpenPermittedCalls = 1;

        private Builder() {
        }

        public Builder slidingWindowSize(int slidingWindowSize) {
            if (slidingWindowSize < 1) {
                throw new IllegalArgumentException("slidingWindowSize must be >= 1");
            }
            this.slidingWindowSize = slidingWindowSize;
            return this;
        }

        public Builder failureRateThresholdPercent(double failureRateThresholdPercent) {
            if (failureRateThresholdPercent <= 0.0 || failureRateThresholdPercent > 100.0) {
                throw new IllegalArgumentException("failureRateThresholdPercent must be in (0, 100]");
            }
            this.failureRateThresholdPercent = failureRateThresholdPercent;
            return this;
        }

        public Builder openStateDurationMillis(long openStateDurationMillis) {
            if (openStateDurationMillis < 0) {
                throw new IllegalArgumentException("openStateDurationMillis must be >= 0");
            }
            this.openStateDurationMillis = openStateDurationMillis;
            return this;
        }

        public Builder halfOpenPermittedCalls(int halfOpenPermittedCalls) {
            if (halfOpenPermittedCalls < 1) {
                throw new IllegalArgumentException("halfOpenPermittedCalls must be >= 1");
            }
            this.halfOpenPermittedCalls = halfOpenPermittedCalls;
            return this;
        }

        public CircuitBreakerConfig build() {
            return new CircuitBreakerConfig(this);
        }
    }
}
