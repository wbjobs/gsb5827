package com.gsb.resilience;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class ResilienceTestRunner {

    static final class RetryableException extends RuntimeException {
        RetryableException(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        Map<String, Check.Body> tests = new LinkedHashMap<String, Check.Body>();
        tests.put("nonRetryableExceptionCallsActionOnlyOnce", ResilienceTestRunner::nonRetryableExceptionCallsActionOnlyOnce);
        tests.put("fixedBackoffDelaySequence", ResilienceTestRunner::fixedBackoffDelaySequence);
        tests.put("exponentialBackoffDelaySequence", ResilienceTestRunner::exponentialBackoffDelaySequence);
        tests.put("rootCauseExceptionPropagatesUnwrapped", ResilienceTestRunner::rootCauseExceptionPropagatesUnwrapped);
        tests.put("retryBudgetExceededStopsRetries", ResilienceTestRunner::retryBudgetExceededStopsRetries);
        tests.put("openCircuitFailsFastWithoutTouchingDownstream", ResilienceTestRunner::openCircuitFailsFastWithoutTouchingDownstream);
        tests.put("halfOpenAllowsLimitedProbesAndClosesOnSuccess", ResilienceTestRunner::halfOpenAllowsLimitedProbesAndClosesOnSuccess);
        tests.put("halfOpenProbeFailureReopensCircuit", ResilienceTestRunner::halfOpenProbeFailureReopensCircuit);
        tests.put("halfOpenPermitsOnlyConfiguredNumberOfProbes", ResilienceTestRunner::halfOpenPermitsOnlyConfiguredNumberOfProbes);

        int passed = 0;
        List<String> failed = new ArrayList<String>();
        for (Map.Entry<String, Check.Body> entry : tests.entrySet()) {
            try {
                entry.getValue().run();
                passed++;
                System.out.println("PASS " + entry.getKey());
            } catch (Throwable failure) {
                failed.add(entry.getKey());
                System.out.println("FAIL " + entry.getKey() + " -> " + failure);
                failure.printStackTrace(System.out);
            }
        }
        System.out.println();
        System.out.println(passed + "/" + tests.size() + " tests passed");
        if (!failed.isEmpty()) {
            System.out.println("Failed tests: " + failed);
            System.exit(1);
        }
        System.out.println("ALL GREEN");
    }

    private static RetryPolicy.Builder retryPolicy() {
        return RetryPolicy.builder()
                .withMaxAttempts(3)
                .withBackoff(BackoffStrategy.fixed(0L))
                .withRetryBudgetRatio(1.0d)
                .withRetryable(failure -> true);
    }

    private static CircuitBreakerConfig.Builder relaxedBreaker() {
        return CircuitBreakerConfig.builder()
                .withSlidingWindowSize(1000)
                .withFailureRateThresholdPercent(100.0d)
                .withWaitDurationInOpenStateMillis(60000L)
                .withPermittedCallsInHalfOpenState(1);
    }

    private static void nonRetryableExceptionCallsActionOnlyOnce() {
        FakeClock clock = new FakeClock();
        Executor executor = new Executor(clock);
        executor.configure("svc",
                retryPolicy()
                        .withMaxAttempts(5)
                        .withBackoff(BackoffStrategy.fixed(10L))
                        .withRetryable(failure -> failure instanceof RetryableException)
                        .build(),
                relaxedBreaker().build());

        final int[] calls = {0};
        final IllegalStateException boom = new IllegalStateException("non-retryable");
        IllegalStateException thrown = Check.expectThrows(IllegalStateException.class,
                () -> executor.execute("svc", () -> {
                    calls[0]++;
                    throw boom;
                }),
                "non-retryable exception must propagate");
        Check.same(boom, thrown, "the original exception instance must be rethrown");
        Check.equals(1, calls[0], "non-retryable exception must not be retried");
        Check.isTrue(clock.recordedSleeps().isEmpty(), "no backoff sleep expected for non-retryable failure");
    }

    private static void fixedBackoffDelaySequence() {
        FakeClock clock = new FakeClock();
        Executor executor = new Executor(clock);
        executor.configure("fixed",
                retryPolicy().withMaxAttempts(4).withBackoff(BackoffStrategy.fixed(100L)).build(),
                relaxedBreaker().build());

        for (int i = 0; i < 5; i++) {
            executor.execute("fixed", () -> "warm-up");
        }
        final int[] calls = {0};
        Check.expectThrows(RetryableException.class,
                () -> executor.execute("fixed", () -> {
                    calls[0]++;
                    throw new RetryableException("always fails");
                }),
                "retryable failure must propagate after max attempts");
        Check.equals(4, calls[0], "maxAttempts=4 means 4 downstream calls");
        Check.equals(Arrays.asList(100L, 100L, 100L), clock.recordedSleeps(),
                "fixed backoff must repeat the same delay");
    }

    private static void exponentialBackoffDelaySequence() {
        FakeClock clock = new FakeClock();
        Executor executor = new Executor(clock);
        executor.configure("exponential",
                retryPolicy().withMaxAttempts(5)
                        .withBackoff(BackoffStrategy.exponential(100L, 2.0d, 250L))
                        .build(),
                relaxedBreaker().build());

        for (int i = 0; i < 5; i++) {
            executor.execute("exponential", () -> "warm-up");
        }
        final int[] calls = {0};
        Check.expectThrows(RetryableException.class,
                () -> executor.execute("exponential", () -> {
                    calls[0]++;
                    throw new RetryableException("always fails");
                }),
                "retryable failure must propagate after max attempts");
        Check.equals(5, calls[0], "maxAttempts=5 means 5 downstream calls");
        Check.equals(Arrays.asList(100L, 200L, 250L, 250L), clock.recordedSleeps(),
                "exponential backoff must double and be capped at max");
    }

    private static void rootCauseExceptionPropagatesUnwrapped() {
        FakeClock clock = new FakeClock();
        Executor executor = new Executor(clock);
        executor.configure("root-cause",
                retryPolicy().withMaxAttempts(3).build(),
                relaxedBreaker().build());

        final RetryableException rootCause = new RetryableException("root cause");
        RetryableException thrown = Check.expectThrows(RetryableException.class,
                () -> executor.execute("root-cause", () -> {
                    throw rootCause;
                }),
                "failure must propagate after all attempts");
        Check.same(rootCause, thrown, "the root cause exception must be rethrown unwrapped");
    }

    private static void retryBudgetExceededStopsRetries() {
        FakeClock clock = new FakeClock();
        Executor executor = new Executor(clock);
        executor.configure("budget",
                retryPolicy().withMaxAttempts(10).withRetryBudgetRatio(0.5d).build(),
                relaxedBreaker().build());

        Supplier<String> ok = () -> "ok";
        final int[] calls = {0};
        Supplier<String> fail = () -> {
            calls[0]++;
            throw new RetryableException("flaky downstream");
        };

        executor.execute("budget", ok);
        executor.execute("budget", ok);

        calls[0] = 0;
        Check.expectThrows(RetryableException.class, () -> executor.execute("budget", fail),
                "call A must fail");
        Check.equals(2, calls[0], "call A: budget 0.5 * 3 calls allows exactly 1 retry");

        calls[0] = 0;
        Check.expectThrows(RetryableException.class, () -> executor.execute("budget", fail),
                "call B must fail");
        Check.equals(2, calls[0], "call B: budget 0.5 * 4 calls allows exactly 1 more retry");

        calls[0] = 0;
        Check.expectThrows(RetryableException.class, () -> executor.execute("budget", fail),
                "call C must fail");
        Check.equals(1, calls[0], "call C: budget exhausted, no retry at all");

        calls[0] = 0;
        Check.expectThrows(RetryableException.class, () -> executor.execute("budget", fail),
                "call D must fail");
        Check.equals(2, calls[0], "call D: completed calls refill the budget, 1 retry allowed again");
    }

    private static void openCircuitFailsFastWithoutTouchingDownstream() {
        FakeClock clock = new FakeClock();
        Executor executor = new Executor(clock);
        executor.configure("svc-open",
                retryPolicy().withMaxAttempts(1).build(),
                CircuitBreakerConfig.builder()
                        .withSlidingWindowSize(4)
                        .withFailureRateThresholdPercent(50.0d)
                        .withWaitDurationInOpenStateMillis(1000L)
                        .withPermittedCallsInHalfOpenState(2)
                        .build());

        final int[] downstreamCalls = {0};
        Supplier<String> ok = () -> {
            downstreamCalls[0]++;
            return "ok";
        };
        Supplier<String> fail = () -> {
            downstreamCalls[0]++;
            throw new RetryableException("downstream down");
        };

        executor.execute("svc-open", ok);
        for (int i = 0; i < 3; i++) {
            Check.expectThrows(RetryableException.class, () -> executor.execute("svc-open", fail),
                    "failure must propagate");
        }
        Check.equals(State.OPEN, executor.state("svc-open"),
                "75% failure rate over the window must open the breaker");

        int downstreamBefore = downstreamCalls[0];
        for (int i = 0; i < 3; i++) {
            Check.expectThrows(CircuitBreakerOpenException.class, () -> executor.execute("svc-open", ok),
                    "OPEN breaker must fail fast");
        }
        Check.equals(downstreamBefore, downstreamCalls[0],
                "while OPEN, downstream call count must stay unchanged (zero downstream calls)");
    }

    private static void halfOpenAllowsLimitedProbesAndClosesOnSuccess() {
        FakeClock clock = new FakeClock();
        Executor executor = new Executor(clock);
        executor.configure("svc-half",
                retryPolicy().withMaxAttempts(1).build(),
                CircuitBreakerConfig.builder()
                        .withSlidingWindowSize(2)
                        .withFailureRateThresholdPercent(50.0d)
                        .withWaitDurationInOpenStateMillis(1000L)
                        .withPermittedCallsInHalfOpenState(2)
                        .build());

        Supplier<String> ok = () -> "ok";
        Supplier<String> fail = () -> {
            throw new RetryableException("downstream down");
        };

        Check.expectThrows(RetryableException.class, () -> executor.execute("svc-half", fail), "f1");
        Check.expectThrows(RetryableException.class, () -> executor.execute("svc-half", fail), "f2");
        Check.equals(State.OPEN, executor.state("svc-half"), "breaker must be OPEN");

        clock.advance(999L);
        Check.equals(State.OPEN, executor.state("svc-half"), "cooldown not elapsed yet");
        Check.expectThrows(CircuitBreakerOpenException.class, () -> executor.execute("svc-half", ok),
                "still OPEN before cooldown elapses");

        clock.advance(1L);
        Check.equals(State.HALF_OPEN, executor.state("svc-half"), "cooldown elapsed -> HALF_OPEN");

        executor.execute("svc-half", ok);
        Check.equals(State.HALF_OPEN, executor.state("svc-half"),
                "one successful probe out of two permitted keeps HALF_OPEN");
        executor.execute("svc-half", ok);
        Check.equals(State.CLOSED, executor.state("svc-half"),
                "all permitted probes succeeded -> CLOSED");
    }

    private static void halfOpenProbeFailureReopensCircuit() {
        FakeClock clock = new FakeClock();
        Executor executor = new Executor(clock);
        executor.configure("svc-flap",
                retryPolicy().withMaxAttempts(1).build(),
                CircuitBreakerConfig.builder()
                        .withSlidingWindowSize(2)
                        .withFailureRateThresholdPercent(50.0d)
                        .withWaitDurationInOpenStateMillis(1000L)
                        .withPermittedCallsInHalfOpenState(1)
                        .build());

        Supplier<String> ok = () -> "ok";
        Supplier<String> fail = () -> {
            throw new RetryableException("downstream down");
        };

        Check.expectThrows(RetryableException.class, () -> executor.execute("svc-flap", fail), "f1");
        Check.expectThrows(RetryableException.class, () -> executor.execute("svc-flap", fail), "f2");
        Check.equals(State.OPEN, executor.state("svc-flap"), "breaker must be OPEN");

        clock.advance(1000L);
        Check.equals(State.HALF_OPEN, executor.state("svc-flap"), "cooldown elapsed -> HALF_OPEN");

        Check.expectThrows(RetryableException.class, () -> executor.execute("svc-flap", fail),
                "probe failure must propagate");
        Check.equals(State.OPEN, executor.state("svc-flap"), "failed probe -> back to OPEN");

        final int[] downstreamCalls = {0};
        Check.expectThrows(CircuitBreakerOpenException.class,
                () -> executor.execute("svc-flap", () -> {
                    downstreamCalls[0]++;
                    return "ok";
                }),
                "re-opened breaker must fail fast");
        Check.equals(0, downstreamCalls[0], "no downstream call after probe failure re-opened the breaker");

        clock.advance(1000L);
        Check.equals(State.HALF_OPEN, executor.state("svc-flap"), "second cooldown -> HALF_OPEN again");
        executor.execute("svc-flap", ok);
        Check.equals(State.CLOSED, executor.state("svc-flap"), "successful probe -> CLOSED");
    }

    private static void halfOpenPermitsOnlyConfiguredNumberOfProbes() {
        FakeClock clock = new FakeClock();
        Executor executor = new Executor(clock);
        executor.configure("svc-probe",
                retryPolicy().withMaxAttempts(1).build(),
                CircuitBreakerConfig.builder()
                        .withSlidingWindowSize(2)
                        .withFailureRateThresholdPercent(50.0d)
                        .withWaitDurationInOpenStateMillis(1000L)
                        .withPermittedCallsInHalfOpenState(1)
                        .build());

        Supplier<String> fail = () -> {
            throw new RetryableException("downstream down");
        };
        Check.expectThrows(RetryableException.class, () -> executor.execute("svc-probe", fail), "f1");
        Check.expectThrows(RetryableException.class, () -> executor.execute("svc-probe", fail), "f2");
        Check.equals(State.OPEN, executor.state("svc-probe"), "breaker must be OPEN");

        clock.advance(1000L);
        Check.equals(State.HALF_OPEN, executor.state("svc-probe"), "cooldown elapsed -> HALF_OPEN");

        final boolean[] innerCalled = {false};
        String result = executor.execute("svc-probe", () -> {
            Check.expectThrows(CircuitBreakerOpenException.class,
                    () -> executor.execute("svc-probe", () -> {
                        innerCalled[0] = true;
                        return "inner";
                    }),
                    "only 1 probe permitted: the nested call must fail fast");
            return "outer-probe";
        });
        Check.equals("outer-probe", result, "the single permitted probe goes through");
        Check.isTrue(!innerCalled[0], "rejected probe must not touch downstream");
        Check.equals(State.CLOSED, executor.state("svc-probe"), "successful probe -> CLOSED");
    }
}
