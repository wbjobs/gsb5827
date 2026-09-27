package com.gsb.resilience;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

public class Executor {
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Entry> entries = new ConcurrentHashMap<String, Entry>();

    public Executor() {
        this(new SystemClock(), new ParkSleeper());
    }

    public Executor(Clock clock, Sleeper sleeper) {
        if (clock == null || sleeper == null) {
            throw new IllegalArgumentException("clock and sleeper must not be null");
        }
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public void configure(String name, RetryPolicy policy, CircuitBreakerConfig breaker) {
        if (name == null || policy == null || breaker == null) {
            throw new IllegalArgumentException("name, policy and breaker must not be null");
        }
        entries.put(name, new Entry(policy, breaker));
    }

    public State state(String name) {
        Entry entry = requireEntry(name);
        return entry.breaker.state(clock.currentTimeMillis());
    }

    public <T> T execute(String name, Supplier<T> action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        Entry entry = requireEntry(name);
        RetryPolicy policy = entry.policy;

        long now = clock.currentTimeMillis();
        if (!entry.breaker.tryAcquire(now)) {
            throw new CircuitBreakerOpenException("circuit breaker is " + entry.breaker.state(now) + " for: " + name);
        }
        entry.registerCall();

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                T result = action.get();
                entry.breaker.onSuccess();
                return result;
            } catch (RuntimeException failure) {
                if (!policy.getRetryable().test(failure)
                        || attempt >= policy.getMaxAttempts()
                        || !entry.tryConsumeBudget()) {
                    entry.breaker.onFailure(clock.currentTimeMillis());
                    throw failure;
                }
                sleepQuietly(policy.getBackoff().delayMillis(attempt), failure);
            } catch (Error failure) {
                entry.breaker.onFailure(clock.currentTimeMillis());
                throw failure;
            }
        }
    }

    private void sleepQuietly(long delayMillis, RuntimeException rootCause) {
        if (delayMillis <= 0) {
            return;
        }
        try {
            sleeper.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw rootCause;
        }
    }

    private Entry requireEntry(String name) {
        Entry entry = entries.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("no configuration registered for: " + name);
        }
        return entry;
    }

    private static final class Entry {
        private final RetryPolicy policy;
        private final CircuitBreaker breaker;
        private long totalCalls;
        private long totalRetries;

        Entry(RetryPolicy policy, CircuitBreakerConfig config) {
            this.policy = policy;
            this.breaker = new CircuitBreaker(config);
        }

        synchronized void registerCall() {
            totalCalls++;
        }

        synchronized boolean tryConsumeBudget() {
            double budget = policy.getRetryBudgetRatio() * totalCalls;
            if (totalRetries + 1 > budget) {
                return false;
            }
            totalRetries++;
            return true;
        }
    }

    private static final class CircuitBreaker {
        private final CircuitBreakerConfig config;
        private final boolean[] failures;
        private State state = State.CLOSED;
        private int windowIndex;
        private int windowCount;
        private int failureCount;
        private long openedAt;
        private int halfOpenInFlight;

        CircuitBreaker(CircuitBreakerConfig config) {
            this.config = config;
            this.failures = new boolean[config.getSlidingWindowSize()];
        }

        synchronized State state(long now) {
            if (state == State.OPEN && now - openedAt >= config.getOpenStateDurationMillis()) {
                state = State.HALF_OPEN;
                halfOpenInFlight = 0;
            }
            return state;
        }

        synchronized boolean tryAcquire(long now) {
            State current = state(now);
            if (current == State.OPEN) {
                return false;
            }
            if (current == State.HALF_OPEN) {
                if (halfOpenInFlight >= config.getHalfOpenPermittedCalls()) {
                    return false;
                }
                halfOpenInFlight++;
            }
            return true;
        }

        synchronized void onSuccess() {
            if (state == State.HALF_OPEN) {
                halfOpenInFlight--;
                transitionToClosed();
                return;
            }
            record(false);
        }

        synchronized void onFailure(long now) {
            if (state == State.HALF_OPEN) {
                halfOpenInFlight--;
                transitionToOpen(now);
                return;
            }
            record(true);
            if (windowCount >= config.getSlidingWindowSize()) {
                double rate = failureCount * 100.0 / windowCount;
                if (rate >= config.getFailureRateThresholdPercent()) {
                    transitionToOpen(now);
                }
            }
        }

        private void record(boolean failure) {
            if (windowCount == failures.length) {
                if (failures[windowIndex]) {
                    failureCount--;
                }
            } else {
                windowCount++;
            }
            failures[windowIndex] = failure;
            if (failure) {
                failureCount++;
            }
            windowIndex = (windowIndex + 1) % failures.length;
        }

        private void transitionToOpen(long now) {
            state = State.OPEN;
            openedAt = now;
        }

        private void transitionToClosed() {
            state = State.CLOSED;
            windowIndex = 0;
            windowCount = 0;
            failureCount = 0;
            Arrays.fill(failures, false);
        }
    }

    private static final class SystemClock implements Clock {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }

    private static final class ParkSleeper implements Sleeper {
        @Override
        public void sleep(long millis) throws InterruptedException {
            if (millis <= 0) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }
}
