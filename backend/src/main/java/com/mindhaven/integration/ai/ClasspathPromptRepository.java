package com.mindhaven.integration.ai;

import com.mindhaven.common.Hashes;
import com.mindhaven.integration.ai.PromptRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ClasspathPromptRepository implements PromptRepository {
    private final Map<String, Template> templates = new ConcurrentHashMap<>();

    public Template get(String name) {
        if (!name.matches("[a-z-]+")) throw new IllegalArgumentException("Invalid prompt name");
        return templates.computeIfAbsent(name, key -> {
            try {
                String text = new ClassPathResource("prompts/" + key + ".txt").getContentAsString(StandardCharsets.UTF_8).strip();
                return new Template(key, text, Hashes.sha256(text));
            } catch (IOException e) {
                throw new IllegalStateException("Missing prompt: " + key, e);
            }
        });
    }
}
