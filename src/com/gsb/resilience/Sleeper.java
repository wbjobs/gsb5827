package com.gsb.resilience;

public interface Sleeper {
    void sleep(long millis) throws InterruptedException;
}
