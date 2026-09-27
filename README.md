# gsb-resilience

带重试预算和熔断器的执行器，JDK 8，仅使用标准库，无第三方依赖。

## 结构

- `src/com/gsb/resilience/Executor.java` — 对外入口：`configure` / `execute` / `state`
- `src/com/gsb/resilience/RetryPolicy.java` — 最大尝试次数、退避策略、重试预算比例、可重试判定
- `src/com/gsb/resilience/BackoffStrategy.java` — 固定 / 指数退避
- `src/com/gsb/resilience/CircuitBreakerConfig.java` — 滑动窗口、失败率阈值、冷却时长、半开探针数
- `src/com/gsb/resilience/Clock.java` / `Sleeper.java` — 可注入的时间与睡眠抽象（实现中无 `Thread.sleep`）
- `test/com/gsb/resilience/ExecutorTest.java` — 虚拟时间测试套件

## 行为约定

- 只有 `retryOn` 判定为可重试的异常才会重试；不可重试异常立刻原样抛出且不消耗预算。
- 全部尝试失败后抛出根因异常本身，不做包装。
- 重试预算 = 重试次数 / 总调用次数，超过比例后新的失败调用不再重试。
- 熔断器在滑动窗口失败率超阈值时进入 OPEN，期间快速失败（`CircuitBreakerOpenException`）不触达下游；
  冷却后进入 HALF_OPEN，只放行有限个探针，探针成功回 CLOSED，失败立刻回 OPEN。

## 运行测试

```bash
./run-tests.sh
```
