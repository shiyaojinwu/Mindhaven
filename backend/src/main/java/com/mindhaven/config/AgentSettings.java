package com.mindhaven.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AgentSettings(boolean enabled, int maxSteps, int maxToolCalls, int maxSearches, int toolResultBudget) {
    public AgentSettings(@Value("${mindhaven.agent.enabled:true}") boolean enabled,
                         @Value("${mindhaven.agent.max-steps:64}") int maxSteps,
                         @Value("${mindhaven.agent.max-tool-calls:0}") int maxToolCalls,
                         @Value("${mindhaven.agent.max-searches:0}") int maxSearches,
                         @Value("${mindhaven.agent.tool-result-budget:2400}") int toolResultBudget) {
        if (maxSteps < 2 || maxSteps > 256 || maxToolCalls < 0 || maxToolCalls > 1000 || maxSearches < 0 || (maxToolCalls > 0 && maxSearches > maxToolCalls)
                || toolResultBudget < 512 || toolResultBudget > 8000) throw new IllegalArgumentException("Invalid agent execution limits");
        this.enabled = enabled;
        this.maxSteps = maxSteps;
        this.maxToolCalls = maxToolCalls;
        this.maxSearches = maxSearches;
        this.toolResultBudget = toolResultBudget;
    }
}
