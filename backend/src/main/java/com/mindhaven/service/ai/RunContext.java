package com.mindhaven.service.ai;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Per-execution control; model output never supplies identity, permissions or limits.
 */
public final class RunContext {
    private static final class Execution {
        final String id;
        final BooleanSupplier cancelled;
        int remaining;

        Execution(String id, BooleanSupplier cancelled, int budget) {
            this.id = id;
            this.cancelled = cancelled;
            this.remaining = budget;
        }
    }

    private static final ThreadLocal<Execution> CURRENT = new ThreadLocal<>();

    private RunContext() {
    }

    public static String id() {
        return CURRENT.get() == null ? null : CURRENT.get().id;
    }

    public static void check() {
        if (Thread.currentThread().isInterrupted() || (CURRENT.get() != null && CURRENT.get().cancelled.getAsBoolean()))
            throw new CancellationException("任务已停止");
    }

    /**
     * Conservative input estimate plus output ceiling; not a provider billing counter.
     */
    public static void reserve(int tokens) {
        check();
        var execution = CURRENT.get();
        if (execution == null) return;
        if (tokens > execution.remaining)
            throw new IllegalArgumentException("本次任务的模型预算已用尽，请缩短消息或开始新的对话");
        execution.remaining -= tokens;
    }

    public static AutoCloseable open(String id, BooleanSupplier cancelled) {
        return open(id, cancelled, 24000);
    }

    public static AutoCloseable open(String id, BooleanSupplier cancelled, int budget) {
        Execution previous = CURRENT.get();
        CURRENT.set(new Execution(id, cancelled, budget));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }
}
