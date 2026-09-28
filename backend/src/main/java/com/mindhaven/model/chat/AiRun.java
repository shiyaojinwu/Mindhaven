package com.mindhaven.model.chat;

public record AiRun(String id, String sessionId, String requestId, String message, Status status,
                    boolean cancelRequested, String createdAt, String updatedAt, String error) {
    public enum Status {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED;

        public boolean terminal() {
            return this != QUEUED && this != RUNNING;
        }
    }

    public record Event(long seq, String name, String payload) {
    }

    public record Created(AiRun run, boolean fresh) {
    }
}
