package com.gsb.resilience;

/**
 * Count-based sliding window circuit breaker. All time reads go through the
 * injected Clock. Transitions from OPEN to HALF_OPEN happen lazily when the
 * wait duration has elapsed.
 */
final class CircuitBreaker {

    private final String name;
    private final CircuitBreakerConfig config;
    private final Clock clock;

    private State state = State.CLOSED;
    private final boolean[] window; // true = success, false = failure
    private int windowIndex;
    private int windowCount;
    private int failureCount;
    private long openedAtMillis;
    private int halfOpenInFlight;
    private int halfOpenSuccesses;

    CircuitBreaker(String name, CircuitBreakerConfig config, Clock clock) {
        this.name = name;
        this.config = config;
        this.clock = clock;
        this.window = new boolean[config.getSlidingWindowSize()];
    }

    synchronized State currentState() {
        if (state == State.OPEN
                && clock.currentTimeMillis() - openedAtMillis >= config.getWaitDurationInOpenStateMillis()) {
            transitionTo(State.HALF_OPEN);
        }
        return state;
    }

    synchronized void beforeCall() {
        State current = currentState();
        if (current == State.OPEN) {
            throw new CircuitBreakerOpenException(name);
        }
        if (current == State.HALF_OPEN) {
            if (halfOpenInFlight >= config.getPermittedCallsInHalfOpenState()) {
                throw new CircuitBreakerOpenException(name);
            }
            halfOpenInFlight++;
        }
    }

    synchronized void onSuccess() {
        if (state == State.HALF_OPEN) {
            halfOpenInFlight--;
            halfOpenSuccesses++;
            if (halfOpenSuccesses >= config.getPermittedCallsInHalfOpenState()) {
                transitionTo(State.CLOSED);
            }
        } else if (state == State.CLOSED) {
            recordOutcome(true);
        }
    }

    synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            transitionTo(State.OPEN);
        } else if (state == State.CLOSED) {
            recordOutcome(false);
        }
    }

    private void recordOutcome(boolean success) {
        if (windowCount == window.length) {
            if (!window[windowIndex]) {
                failureCount--;
            }
        } else {
            windowCount++;
        }
        window[windowIndex] = success;
        if (!success) {
            failureCount++;
        }
        windowIndex = (windowIndex + 1) % window.length;
        if (windowCount == window.length
                && failureCount * 100.0d / windowCount >= config.getFailureRateThresholdPercent()) {
            transitionTo(State.OPEN);
        }
    }

    private void transitionTo(State next) {
        state = next;
        halfOpenInFlight = 0;
        halfOpenSuccesses = 0;
        if (next == State.OPEN) {
            openedAtMillis = clock.currentTimeMillis();
        }
        if (next == State.CLOSED) {
            windowIndex = 0;
            windowCount = 0;
            failureCount = 0;
        }
    }
}
