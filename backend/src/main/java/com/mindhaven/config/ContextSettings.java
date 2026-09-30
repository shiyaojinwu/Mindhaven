package com.mindhaven.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("mindhaven.context")
public record ContextSettings(@DefaultValue("true") boolean compressionEnabled,
                              @DefaultValue("0.8") double compressionThreshold, @DefaultValue("3") int keepRecentTurns,
                              @DefaultValue("4") int maxCompressionPasses,
                              @DefaultValue("1500") int maxInputCharacters,
                              @DefaultValue("0.05") double summaryRatio,
                              @DefaultValue("2000") int summaryMaxTokens) {
    public ContextSettings(boolean enabled, double threshold, int recent, int passes, int input) {
        this(enabled, threshold, recent, passes, input, 0.05, 2000);
    }

    public int summaryBudget(int window) {
        return Math.max(1, Math.min(summaryMaxTokens, (int) (window * summaryRatio)));
    }

    @ConstructorBinding
    public ContextSettings {
        if (!(summaryRatio > 0 && summaryRatio < compressionThreshold) || summaryMaxTokens < 64 || summaryMaxTokens > 16000 || !(compressionThreshold > 0 && compressionThreshold < 1) || keepRecentTurns < 1 || maxCompressionPasses < 1 || maxInputCharacters < 1 || maxInputCharacters > 1500) {
            throw new IllegalArgumentException("Invalid context configuration");
        }
    }
}
