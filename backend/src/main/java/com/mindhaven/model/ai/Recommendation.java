package com.mindhaven.model.ai;

/** Only the server creates navigation targets; model output cannot supply URLs. */
public record Recommendation(String kind, String id, String title, String description) { }
