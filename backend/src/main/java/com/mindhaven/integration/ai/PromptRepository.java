package com.mindhaven.integration.ai;

public interface PromptRepository {
    record Template(String name, String text, String hash) {
    }

    Template get(String name);
}
