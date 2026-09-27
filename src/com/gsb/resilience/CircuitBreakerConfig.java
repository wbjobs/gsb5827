package com.gsb.resilience;

public final class CircuitBreakerConfig {

    private final int slidingWindowSize;
    private final double failureRateThresholdPercent;
    private final long waitDurationInOpenStateMillis;
    private final int permittedCallsInHalfOpenState;

    private CircuitBreakerConfig(Builder builder) {
        this.slidingWindowSize = builder.slidingWindowSize;
        this.failureRateThresholdPercent = builder.failureRateThresholdPercent;
        this.waitDurationInOpenStateMillis = builder.waitDurationInOpenStateMillis;
        this.permittedCallsInHalfOpenState = builder.permittedCallsInHalfOpenState;
    }

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public double getFailureRateThresholdPercent() {
        return failureRateThresholdPercent;
    }

    public long getWaitDurationInOpenStateMillis() {
        return waitDurationInOpenStateMillis;
    }

    public int getPermittedCallsInHalfOpenState() {
        return permittedCallsInHalfOpenState;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int slidingWindowSize = 10;
        private double failureRateThresholdPercent = 50.0d;
        private long waitDurationInOpenStateMillis = 60000L;
        private int permittedCallsInHalfOpenState = 1;

        private Builder() {
        }

        public Builder withSlidingWindowSize(int slidingWindowSize) {
            if (slidingWindowSize < 1) {
                throw new IllegalArgumentException("slidingWindowSize must be >= 1");
            }
            this.slidingWindowSize = slidingWindowSize;
            return this;
        }

        public Builder withFailureRateThresholdPercent(double failureRateThresholdPercent) {
            if (failureRateThresholdPercent <= 0.0d || failureRateThresholdPercent > 100.0d) {
                throw new IllegalArgumentException("failureRateThresholdPercent must be in (0, 100]");
            }
            this.failureRateThresholdPercent = failureRateThresholdPercent;
            return this;
        }

        public Builder withWaitDurationInOpenStateMillis(long waitDurationInOpenStateMillis) {
            if (waitDurationInOpenStateMillis < 0L) {
                throw new IllegalArgumentException("waitDurationInOpenStateMillis must be >= 0");
            }
            this.waitDurationInOpenStateMillis = waitDurationInOpenStateMillis;
            return this;
        }

        public Builder withPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
            if (permittedCallsInHalfOpenState < 1) {
                throw new IllegalArgumentException("permittedCallsInHalfOpenState must be >= 1");
            }
            this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
            return this;
        }

        public CircuitBreakerConfig build() {
            return new CircuitBreakerConfig(this);
        }
    }
}
