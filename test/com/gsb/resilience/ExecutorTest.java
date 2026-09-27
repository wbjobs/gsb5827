package com.gsb.resilience;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class ExecutorTest {
    private static int passed;
    private static int failed;
    private static final List<String> failureMessages = new ArrayList<String>();

    public static void main(String[] args) {
        run("nonRetryableFailsImmediatelyAndKeepsBudget", new TestCase() {
            @Override public void run() { testNonRetryableFailsImmediatelyAndKeepsBudget(); }
        });
        run("fixedBackoffSequence", new TestCase() {
            @Override public void run() { testFixedBackoffSequence(); }
        });
        run("exponentialBackoffSequence", new TestCase() {
            @Override public void run() { testExponentialBackoffSequence(); }
        });
        run("retryBudgetExceededStopsRetries", new TestCase() {
            @Override public void run() { testRetryBudgetExceededStopsRetries(); }
        });
        run("rootCausePropagatedAfterExhaustion", new TestCase() {
            @Override public void run() { testRootCausePropagatedAfterExhaustion(); }
        });
        run("circuitOpensAndFastFailsWithoutTouchingDownstream", new TestCase() {
            @Override public void run() { testCircuitOpensAndFastFails(); }
        });
        run("halfOpenLimitsProbesAndClosesOnSuccess", new TestCase() {
            @Override public void run() { testHalfOpenLimitsProbesAndClosesOnSuccess(); }
        });
        run("halfOpenProbeFailureReopens", new TestCase() {
            @Override public void run() { testHalfOpenProbeFailureReopens(); }
        });
        run("successfulCallReturnsValueAndStaysClosed", new TestCase() {
            @Override public void run() { testSuccessfulCallReturnsValueAndStaysClosed(); }
        });

        System.out.println("----------------------------------------");
        System.out.println("passed: " + passed + ", failed: " + failed);
        for (String message : failureMessages) {
            System.out.println("FAILED: " + message);
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    private interface TestCase {
        void run();
    }

    private static void run(String name, TestCase testCase) {
        try {
            testCase.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable t) {
            failed++;
            failureMessages.add(name + " -> " + t);
            System.out.println("FAIL " + name + " -> " + t);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertSame(Object expected, Object actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected same instance)");
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static final class VirtualClock implements Clock {
        private long now;

        @Override
        public long currentTimeMillis() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    private static final class RecordingSleeper implements Sleeper {
        private final VirtualClock clock;
        private final List<Long> delays = new ArrayList<Long>();

        RecordingSleeper(VirtualClock clock) {
            this.clock = clock;
        }

        @Override
        public void sleep(long millis) {
            delays.add(millis);
            clock.advance(millis);
        }
    }

    private static final class Downstream {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile RuntimeException failure;

        String call() {
            calls.incrementAndGet();
            RuntimeException f = failure;
            if (f != null) {
                throw f;
            }
            return "ok";
        }
    }

    private static RetryPolicy retryPolicy(BackoffStrategy backoff, double budgetRatio) {
        return RetryPolicy.builder()
                .maxAttempts(10)
                .backoff(backoff)
                .retryBudgetRatio(budgetRatio)
                .build();
    }

    private static CircuitBreakerConfig breakerConfig() {
        return CircuitBreakerConfig.builder()
                .slidingWindowSize(4)
                .failureRateThresholdPercent(50.0)
                .openStateDurationMillis(1000)
                .halfOpenPermittedCalls(1)
                .build();
    }

    private static void testNonRetryableFailsImmediatelyAndKeepsBudget() {
        VirtualClock clock = new VirtualClock();
        Executor executor = new Executor(clock, new RecordingSleeper(clock));
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5)
                .backoff(BackoffStrategy.fixed(10))
                .retryBudgetRatio(0.5)
                .retryOn(new java.util.function.Predicate<Throwable>() {
                    @Override
                    public boolean test(Throwable t) {
                        return t instanceof IllegalArgumentException;
                    }
                })
                .build();
        executor.configure("svc", policy, breakerConfig());

        final Downstream downstream = new Downstream();
        final IllegalStateException nonRetryable = new IllegalStateException("fatal");
        downstream.failure = nonRetryable;

        RuntimeException thrown = expectThrows(executor, "svc", new Supplier<String>() {
            @Override public String get() { return downstream.call(); }
        });
        assertSame(nonRetryable, thrown, "non-retryable exception must propagate as-is");
        assertEquals(1, downstream.calls.get(), "non-retryable exception must be attempted exactly once");

        // Budget must be untouched: ratio 0.5, totalCalls=1, retries=0.
        // A retryable failure now may perform exactly 1 retry (1 <= 0.5 * 2).
        final IllegalArgumentException retryable = new IllegalArgumentException("transient");
        downstream.failure = retryable;
        RuntimeException second = expectThrows(executor, "svc", new Supplier<String>() {
            @Override public String get() { return downstream.call(); }
        });
        assertSame(retryable, second, "retryable root cause must propagate");
        assertEquals(3, downstream.calls.get(),
                "budget must not have been consumed by the non-retryable failure (1 + 2 attempts expected)");
    }

    private static void testFixedBackoffSequence() {
        VirtualClock clock = new VirtualClock();
        RecordingSleeper sleeper = new RecordingSleeper(clock);
        Executor executor = new Executor(clock, sleeper);
        executor.configure("svc", RetryPolicy.builder()
                        .maxAttempts(4)
                        .backoff(BackoffStrategy.fixed(100))
                        .retryBudgetRatio(100.0)
                        .build(),
                breakerConfig());

        final Downstream downstream = new Downstream();
        downstream.failure = new RuntimeException("boom");
        expectThrows(executor, "svc", new Supplier<String>() {
            @Override public String get() { return downstream.call(); }
        });

        assertEquals(4, downstream.calls.get(), "maxAttempts=4 means 4 downstream calls");
        assertEquals(Arrays.asList(100L, 100L, 100L), sleeper.delays, "fixed backoff delay sequence");
    }

    private static void testExponentialBackoffSequence() {
        VirtualClock clock = new VirtualClock();
        RecordingSleeper sleeper = new RecordingSleeper(clock);
        Executor executor = new Executor(clock, sleeper);
        executor.configure("svc", RetryPolicy.builder()
                        .maxAttempts(5)
                        .backoff(BackoffStrategy.exponential(100, 2.0, 250))
                        .retryBudgetRatio(100.0)
                        .build(),
                breakerConfig());

        final Downstream downstream = new Downstream();
        downstream.failure = new RuntimeException("boom");
        expectThrows(executor, "svc", new Supplier<String>() {
            @Override public String get() { return downstream.call(); }
        });

        assertEquals(5, downstream.calls.get(), "maxAttempts=5 means 5 downstream calls");
        assertEquals(Arrays.asList(100L, 200L, 250L, 250L), sleeper.delays,
                "exponential backoff must double and cap at max");
    }

    private static void testRetryBudgetExceededStopsRetries() {
        VirtualClock clock = new VirtualClock();
        Executor executor = new Executor(clock, new RecordingSleeper(clock));
        executor.configure("svc", retryPolicy(BackoffStrategy.fixed(1), 0.5), breakerConfig());

        final Downstream downstream = new Downstream();
        downstream.failure = new RuntimeException("boom");
        Supplier<String> action = new Supplier<String>() {
            @Override public String get() { return downstream.call(); }
        };

        // Call 1: totalCalls=1, budget=0.5 -> first retry (1 > 0.5) rejected. 1 attempt.
        expectThrows(executor, "svc", action);
        assertEquals(1, downstream.calls.get(), "first call exceeds budget immediately, no retry");

        // Call 2: totalCalls=2, budget=1.0 -> one retry allowed (1 <= 1), second rejected. 2 attempts.
        expectThrows(executor, "svc", action);
        assertEquals(3, downstream.calls.get(), "second call gets exactly one retry");

        // Call 3: totalCalls=3, retries=1, budget=1.5 -> next retry (2 > 1.5) rejected. 1 attempt.
        expectThrows(executor, "svc", action);
        assertEquals(4, downstream.calls.get(), "over budget: new failing call must not retry");
    }

    private static void testRootCausePropagatedAfterExhaustion() {
        VirtualClock clock = new VirtualClock();
        Executor executor = new Executor(clock, new RecordingSleeper(clock));
        executor.configure("svc", RetryPolicy.builder()
                        .maxAttempts(3)
                        .backoff(BackoffStrategy.fixed(1))
                        .retryBudgetRatio(100.0)
                        .build(),
                breakerConfig());

        final Downstream downstream = new Downstream();
        final RuntimeException rootCause = new RuntimeException("root-cause");
        downstream.failure = rootCause;

        RuntimeException thrown = expectThrows(executor, "svc", new Supplier<String>() {
            @Override public String get() { return downstream.call(); }
        });
        assertSame(rootCause, thrown, "after all attempts fail the root cause must be thrown, not a wrapper");
        assertEquals(3, downstream.calls.get(), "all 3 attempts must have reached downstream");
    }

    private static void testCircuitOpensAndFastFails() {
        VirtualClock clock = new VirtualClock();
        Executor executor = new Executor(clock, new RecordingSleeper(clock));
        executor.configure("svc", retryPolicy(BackoffStrategy.fixed(1), 0.0), breakerConfig());

        final Downstream downstream = new Downstream();
        downstream.failure = new RuntimeException("boom");
        Supplier<String> action = new Supplier<String>() {
            @Override public String get() { return downstream.call(); }
        };

        // Sliding window = 4, threshold = 50%: 4 failures -> 100% -> OPEN.
        for (int i = 0; i < 4; i++) {
            expectThrows(executor, "svc", action);
        }
        assertEquals(4, downstream.calls.get(), "window of 4 failures reached downstream");
        assertEquals(State.OPEN, executor.state("svc"), "breaker must be OPEN after threshold exceeded");

        int before = downstream.calls.get();
        for (int i = 0; i < 3; i++) {
            RuntimeException thrown = expectThrows(executor, "svc", action);
            assertTrue(thrown instanceof CircuitBreakerOpenException,
                    "OPEN breaker must fast-fail with CircuitBreakerOpenException, got: " + thrown);
        }
        assertEquals(before, downstream.calls.get(), "OPEN breaker must not touch downstream");

        clock.advance(999);
        assertEquals(State.OPEN, executor.state("svc"), "breaker stays OPEN before cooldown elapses");
        expectThrows(executor, "svc", action);
        assertEquals(before, downstream.calls.get(), "still no downstream call before cooldown");
    }

    private static void testHalfOpenLimitsProbesAndClosesOnSuccess() {
        VirtualClock clock = new VirtualClock();
        Executor executor = new Executor(clock, new RecordingSleeper(clock));
        executor.configure("svc", retryPolicy(BackoffStrategy.fixed(1), 0.0), breakerConfig());

        final Downstream downstream = new Downstream();
        downstream.failure = new RuntimeException("boom");
        Supplier<String> action = new Supplier<String>() {
            @Override public String get() { return downstream.call(); }
        };
        for (int i = 0; i < 4; i++) {
            expectThrows(executor, "svc", action);
        }
        assertEquals(State.OPEN, executor.state("svc"), "breaker OPEN after 4 failures");

        clock.advance(1000);
        assertEquals(State.HALF_OPEN, executor.state("svc"), "breaker must be HALF_OPEN after cooldown");

        downstream.failure = null;
        final AtomicInteger nestedCalls = new AtomicInteger();
        String result = executor.execute("svc", new Supplier<String>() {
            @Override
            public String get() {
                // While the single permitted probe is in flight, a concurrent call must fast-fail.
                RuntimeException nested = expectThrows(executor, "svc", new Supplier<String>() {
                    @Override public String get() {
                        nestedCalls.incrementAndGet();
                        return "nested";
                    }
                });
                assertTrue(nested instanceof CircuitBreakerOpenException,
                        "excess probe in HALF_OPEN must fast-fail, got: " + nested);
                return downstream.call();
            }
        });
        assertEquals("ok", result, "probe must return downstream value");
        assertEquals(0, nestedCalls.get(), "rejected probe must not reach downstream");
        assertEquals(State.CLOSED, executor.state("svc"), "successful probe must close the breaker");

        // Breaker is closed again: normal traffic flows.
        assertEquals("ok", executor.execute("svc", action), "closed breaker serves traffic");
    }

    private static void testHalfOpenProbeFailureReopens() {
        VirtualClock clock = new VirtualClock();
        Executor executor = new Executor(clock, new RecordingSleeper(clock));
        executor.configure("svc", retryPolicy(BackoffStrategy.fixed(1), 0.0), breakerConfig());

        final Downstream downstream = new Downstream();
        downstream.failure = new RuntimeException("boom");
        Supplier<String> action = new Supplier<String>() {
            @Override public String get() { return downstream.call(); }
        };
        for (int i = 0; i < 4; i++) {
            expectThrows(executor, "svc", action);
        }
        clock.advance(1000);
        assertEquals(State.HALF_OPEN, executor.state("svc"), "HALF_OPEN after cooldown");

        int before = downstream.calls.get();
        expectThrows(executor, "svc", action);
        assertEquals(before + 1, downstream.calls.get(), "probe must reach downstream");
        assertEquals(State.OPEN, executor.state("svc"), "failed probe must reopen the breaker immediately");

        RuntimeException thrown = expectThrows(executor, "svc", action);
        assertTrue(thrown instanceof CircuitBreakerOpenException, "reopened breaker must fast-fail");
        assertEquals(before + 1, downstream.calls.get(), "reopened breaker must not touch downstream");
    }

    private static void testSuccessfulCallReturnsValueAndStaysClosed() {
        VirtualClock clock = new VirtualClock();
        Executor executor = new Executor(clock, new RecordingSleeper(clock));
        executor.configure("svc", retryPolicy(BackoffStrategy.fixed(1), 0.5), breakerConfig());

        final Downstream downstream = new Downstream();
        String value = executor.execute("svc", new Supplier<String>() {
            @Override public String get() { return downstream.call(); }
        });
        assertEquals("ok", value, "successful call returns downstream value");
        assertEquals(1, downstream.calls.get(), "successful call hits downstream once");
        assertEquals(State.CLOSED, executor.state("svc"), "breaker stays CLOSED on success");
    }

    private static RuntimeException expectThrows(Executor executor, String name, Supplier<String> action) {
        try {
            executor.execute(name, action);
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("expected RuntimeException from execute(" + name + ")");
    }
}
