package com.mindhaven.common;

import java.time.Instant;

public final class Times {
    private Times() {
    }

    public static String now() {
        return Instant.now().toString();
    }
}
