package com.gsb.resilience;

import java.util.concurrent.locks.LockSupport;

/**
 * Wall-clock implementation. Parking is used for waiting so the production
 * code base never blocks a thread with an uninterruptible sleep loop.
 */
public final class SystemClock implements Clock {

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        long deadline = System.nanoTime() + millis * 1000000L;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0L) {
            LockSupport.parkNanos(remaining);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }
}
