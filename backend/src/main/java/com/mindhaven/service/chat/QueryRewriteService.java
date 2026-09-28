package com.mindhaven.service.chat;

import com.mindhaven.config.Settings;
import com.mindhaven.integration.ai.PromptRepository;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.chat.Summary;
import com.mindhaven.service.ai.AiOperations;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QueryRewriteService {
    private final Settings settings;
    private final PromptRepository prompts;
    private final AiOperations ai;

    public QueryRewriteService(Settings settings, PromptRepository prompts, AiOperations ai) {
        this.settings = settings;
        this.prompts = prompts;
        this.ai = ai;
    }

    public String rewrite(String input, List<ChatMessage> history, Summary summary) {
        if (history.isEmpty() && summary == null) return input;
        if (settings.aiMode().equals("demo")) {
            if (input.matches(".*(它|这个|那个|怎么办|那我|为什么|这样|这种).*"))
                return history.stream().filter(m -> m.role().equals("user")).reduce((a, b) -> b).map(m -> m.content() + "；" + input).orElse(input);
            return input;
        }
        StringBuilder context = new StringBuilder();
        if (summary != null) context.append(summary.content());
        for (var m : history.subList(Math.max(0, history.size() - 4), history.size()))
            context.append("\n").append(m.role()).append(":").append(m.content());
        var result = ai.complete("rewrite", List.of(new SystemMessage(prompts.get("rewrite").text()), new UserMessage("历史：" + ContextPlanner.clip(context.toString(), 2400) + "\n当前问题：" + input)), 200);
        return result.text().isBlank() ? input : ContextPlanner.clip(result.text(), 1200);
    }

}
