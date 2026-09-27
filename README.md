# gsb-resilience

带重试预算和熔断器的命名通道执行器。JDK 8，仅标准库，无构建工具、无第三方依赖。

## 使用

```java
Clock clock = new SystemClock();          // 测试时注入自己的虚拟 Clock
Executor executor = new Executor(clock);

executor.configure("order-service",
        RetryPolicy.builder()
                .withMaxAttempts(4)                                   // 含首次调用
                .withBackoff(BackoffStrategy.exponential(100, 2.0, 1000))
                .withRetryBudgetRatio(0.2)                            // retries / totalCalls <= 20%
                .withRetryable(t -> t instanceof TransientException)  // 只有可重试异常才重试
                .build(),
        CircuitBreakerConfig.builder()
                .withSlidingWindowSize(20)
                .withFailureRateThresholdPercent(50)
                .withWaitDurationInOpenStateMillis(30_000)
                .withPermittedCallsInHalfOpenState(2)
                .build());

String result = executor.execute("order-service", () -> downstream.call());
State state = executor.state("order-service");
```

## 语义

- **重试**：只有 `retryable` 谓词接受的异常才重试；不可重试异常立即原样抛出，不消耗预算。
  全部尝试失败后抛出根因异常本身（sneaky throw，不包装）。
- **重试预算**：按通道累计统计 `retries / totalCalls`，超过 `retryBudgetRatio` 后新的失败调用直接失败。
  被熔断器快速拒绝的调用不计入 totalCalls。
- **熔断器**：计数滑动窗口内失败率 >= 阈值时进入 OPEN；OPEN 期间调用抛出
  `CircuitBreakerOpenException`，不触达下游；冷却时间（由注入的 `Clock` 读取）过后进入
  HALF_OPEN，只放行 `permittedCallsInHalfOpenState` 个探针；探针全部成功回 CLOSED，
  任一失败立刻回 OPEN 并重新计时。
- **时间**：实现内没有 `Thread.sleep`，退避通过 `Clock.sleep` 完成（`SystemClock` 用
  `LockSupport.parkNanos`），测试用虚拟时钟推进时间。

## 构建与测试

```bash
./run-tests.sh
```

脚本会检查 `src/` 中无 `Thread.sleep`，用 `javac -source 8 -target 8` 编译
`src/` 与 `test/`，然后运行 `com.gsb.resilience.ResilienceTestRunner`。
