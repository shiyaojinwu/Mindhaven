package com.mindhaven.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mindhaven.context")
public record ContextSettings(@DefaultValue("true") boolean compressionEnabled,
                              @DefaultValue("0.8") double compressionThreshold, @DefaultValue("3") int keepRecentTurns,
                              @DefaultValue("4") int maxCompressionPasses,
                              @DefaultValue("1500") int maxInputCharacters) {
    public ContextSettings {
        if (!(compressionThreshold > 0 && compressionThreshold < 1) || keepRecentTurns < 1 || maxCompressionPasses < 1 || maxInputCharacters < 1 || maxInputCharacters > 1500) {
            throw new IllegalArgumentException("Invalid context configuration");
        }
    }
}
