package com.gsb.resilience;

import java.util.Objects;

final class Check {

    interface Body {
        void run() throws Exception;
    }

    private Check() {
    }

    static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void equals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " | expected=" + expected + " actual=" + actual);
        }
    }

    static void same(Object expected, Object actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " | expected the same instance, got " + actual);
        }
    }

    static <T extends Throwable> T expectThrows(Class<T> expectedType, Body body, String message) {
        try {
            body.run();
        } catch (Throwable actual) {
            if (expectedType.isInstance(actual)) {
                return expectedType.cast(actual);
            }
            AssertionError error = new AssertionError(message + " | expected " + expectedType.getName()
                    + " but got " + actual.getClass().getName());
            error.initCause(actual);
            throw error;
        }
        throw new AssertionError(message + " | expected " + expectedType.getName() + " but nothing was thrown");
    }
}
