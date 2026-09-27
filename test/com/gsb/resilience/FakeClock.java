package com.gsb.resilience;

import java.util.ArrayList;
import java.util.List;

/**
 * Virtual clock for tests: sleeping only advances virtual time and records
 * the requested backoff delays so tests can assert the exact sequence.
 */
final class FakeClock implements Clock {

    private long nowMillis;
    private final List<Long> sleeps = new ArrayList<Long>();

    @Override
    public synchronized long currentTimeMillis() {
        return nowMillis;
    }

    @Override
    public synchronized void sleep(long millis) {
        sleeps.add(millis);
        nowMillis += millis;
    }

    synchronized void advance(long millis) {
        nowMillis += millis;
    }

    synchronized List<Long> recordedSleeps() {
        return new ArrayList<Long>(sleeps);
    }
}
