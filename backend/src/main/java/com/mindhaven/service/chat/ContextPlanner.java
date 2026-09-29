package com.mindhaven.service.chat;

import com.mindhaven.config.Settings;
import com.mindhaven.integration.ai.PromptRepository;
import com.mindhaven.model.chat.ContextPlan;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.chat.Summary;
import com.mindhaven.model.knowledge.Citation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class ContextPlanner {


    private final PromptRepository prompts;

    private final ContextRenderer renderer;

    public ContextPlanner(PromptRepository prompts, ContextRenderer renderer) {
        this.prompts = prompts;
        this.renderer = renderer;
    }

    // UTF-8 bytes + per-message margin: conservative estimate, NOT provider-reported token usage.
    public static int estimate(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length + 12;
    }

    public static String clip(String s, int budget) {
        StringBuilder out = new StringBuilder();
        int used = 12;
        for (int cp : s.codePoints().toArray()) {
            String c = new String(Character.toChars(cp));
            int n = c.getBytes(StandardCharsets.UTF_8).length;
            if (used + n > budget) break;
            out.append(c);
            used += n;
        }
        return out.toString();
    }

    /**
     * Builds an untrimmed history candidate; only retrieval documents have a separate cap.
     */
    public ContextPlan candidate(List<ChatMessage> history, Summary summary, String input, List<Citation> docs, Settings s) {
        List<Citation> included = new ArrayList<>();
        String currentTurn = renderer.currentTurn(input, included);
        int docBudget = s.knowledgeBudget();
        for (Citation document : docs) {
            var candidate = new ArrayList<>(included);
            candidate.add(document);
            String rendered = renderer.currentTurn(input, candidate);
            int cost = estimate(rendered) - estimate(currentTurn);
            if (cost <= docBudget) {
                included.add(document);
                currentTurn = rendered;
                docBudget -= cost;
            }
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(prompts.get("answer").text()));
        if (summary != null) messages.add(new UserMessage(renderer.summary(summary.content())));
        for (var message : uncovered(history, summary)) {
            messages.add(message.role().equals("user") ? new UserMessage(message.content()) : new AssistantMessage(message.content() + ("partial".equals(message.status())
                    ? "\n[执行状态：这条回答仅部分完成。用户要求继续时，从已有正文末尾衔接，不重复已完成部分；用户要求重写或修改时遵循新要求。]" : "")));
        }
        messages.add(new UserMessage(currentTurn));
        int total = messages.stream().mapToInt(m -> estimate(m.getText())).sum();
        return new ContextPlan(List.copyOf(messages), List.copyOf(included), total);
    }

    /**
     * Keeps whole successful turns, never a failed message or half of a turn.
     */
    public List<ChatMessage> uncovered(List<ChatMessage> history, Summary summary) {
        var eligible = history.stream().filter(m -> (m.status().equals("complete") || m.status().equals("partial")) && (summary == null || m.seq() > summary.coveredThroughSeq())).toList();
        List<ChatMessage> turns = new ArrayList<>();
        for (int i = 0; i + 1 < eligible.size(); i++) {
            var user = eligible.get(i);
            var assistant = eligible.get(i + 1);
            if (user.role().equals("user") && assistant.role().equals("assistant") && assistant.seq() == user.seq() + 1) {
                turns.add(user);
                turns.add(assistant);
                i++;
            }
        }
        return turns;
    }

    public ContextPlan plan(List<ChatMessage> history, Summary summary, String input, List<Citation> docs, Settings s) {
        ContextPlan plan = candidate(history, summary, input, docs, s);
        List<Citation> included = new ArrayList<>(plan.citations());
        while (plan.estimate() > inputLimit(s) && !included.isEmpty()) {
            included.removeLast();
            plan = candidate(history, summary, input, included, s);
        }
        if (plan.estimate() > inputLimit(s)) {
            throw new IllegalArgumentException("保留的近期对话和当前输入超过模型上下文预算，请缩短输入或新建对话；原始记录仍保留");
        }
        return plan;
    }

    public static int inputLimit(Settings settings) {
        return settings.contextBudget() - settings.outputBudget() - 200;
    }
}
