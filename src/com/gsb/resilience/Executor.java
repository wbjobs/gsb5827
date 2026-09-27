package com.gsb.resilience;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Named-channel executor combining a retry policy (with a retry budget) and a
 * circuit breaker. All timing goes through the injected {@link Clock}.
 *
 * Failure semantics:
 * - only exceptions accepted by the policy's retryable predicate are retried;
 *   anything else is rethrown immediately and consumes no retry budget;
 * - when attempts are exhausted the original failure is rethrown as-is,
 *   never wrapped;
 * - retries are only performed while the channel's retry budget
 *   (retries / totalCalls) allows it.
 */
public final class Executor {

    private final Clock clock;
    private final Map<String, Channel> channels = new ConcurrentHashMap<String, Channel>();

    public Executor() {
        this(new SystemClock());
    }

    public Executor(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    public void configure(String name, RetryPolicy policy, CircuitBreakerConfig breaker) {
        if (name == null || policy == null || breaker == null) {
            throw new IllegalArgumentException("name, policy and breaker must not be null");
        }
        channels.put(name, new Channel(name, policy, breaker, clock));
    }

    public State state(String name) {
        return channel(name).breaker.currentState();
    }

    public <T> T execute(String name, Supplier<T> action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        Channel channel = channel(name);
        channel.breaker.beforeCall();
        channel.budget.recordCall();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                T result = action.get();
                channel.breaker.onSuccess();
                return result;
            } catch (Throwable failure) {
                RetryPolicy policy = channel.policy;
                boolean canRetry = policy.isRetryable(failure)
                        && attempt < policy.getMaxAttempts()
                        && channel.budget.tryAcquireRetry();
                if (!canRetry) {
                    channel.breaker.onFailure();
                    Executor.<RuntimeException>throwAsIs(failure);
                }
                long delayMillis = policy.getBackoff().delayMillis(attempt);
                clock.sleep(delayMillis);
            }
        }
    }

    private Channel channel(String name) {
        Channel channel = channels.get(name);
        if (channel == null) {
            throw new IllegalArgumentException("No configuration registered under name '" + name + "'");
        }
        return channel;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwAsIs(Throwable failure) throws E {
        throw (E) failure;
    }

    private static final class Channel {
        final RetryPolicy policy;
        final CircuitBreaker breaker;
        final RetryBudget budget;

        Channel(String name, RetryPolicy policy, CircuitBreakerConfig config, Clock clock) {
            this.policy = policy;
            this.breaker = new CircuitBreaker(name, config, clock);
            this.budget = new RetryBudget(policy.getRetryBudgetRatio());
        }
    }
}
