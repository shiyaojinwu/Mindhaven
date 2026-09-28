package com.mindhaven.model.vo;

/** Counts reflect the current corpus and configured embedding/index target. */
public record KnowledgeIndexStatus(boolean enabled, boolean running, int total, int indexed, int pending, int failed) {
}
