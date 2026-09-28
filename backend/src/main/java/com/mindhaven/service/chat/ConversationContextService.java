package com.mindhaven.service.chat;

import com.mindhaven.config.ContextSettings;
import com.mindhaven.config.Settings;
import com.mindhaven.integration.ai.PromptRepository;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.chat.PreparedContext;
import com.mindhaven.model.chat.Summary;
import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.service.ai.AiOperations;
import com.mindhaven.service.ai.RunContext;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ConversationContextService {
    private final ContextSettings policy;
    private final Settings settings;
    private final ContextPlanner planner;
    private final PromptRepository prompts;
    private final AiOperations ai;
    private final RecordManager records;

    public ConversationContextService(ContextSettings policy, Settings settings, ContextPlanner planner, PromptRepository prompts, AiOperations ai, RecordManager records) {
        this.policy = policy;
        this.settings = settings;
        this.planner = planner;
        this.prompts = prompts;
        this.ai = ai;
        this.records = records;
    }

    public void validateInput(String input) {
        if (input == null || input.isBlank() || input.length() > policy.maxInputCharacters()) {
            throw new IllegalArgumentException("消息不能为空且不能超过 " + policy.maxInputCharacters() + " 个字符");
        }
    }

    public Summary summary(String session) {
        return policy.compressionEnabled() ? records.get("summaries", session, Summary.class).orElse(null) : null;
    }

    public PreparedContext prepare(String session, List<ChatMessage> history, Summary previous, String input, List<Citation> documents) {
        Summary summary = policy.compressionEnabled() ? previous : null;
        var remaining = planner.uncovered(history, summary);
        int trigger = Math.min((int) (settings.contextBudget() * policy.compressionThreshold()), ContextPlanner.inputLimit(settings));
        var candidate = planner.candidate(remaining, summary, input, documents, settings);
        if (policy.compressionEnabled() && candidate.estimate() >= trigger && remaining.size() > policy.keepRecentTurns() * 2) {
            // Once triggered, compact all older turns; keep the newest configured turns verbatim.
            int end = remaining.size() - policy.keepRecentTurns() * 2;
            List<ChatMessage> older = new ArrayList<>(remaining.subList(0, end));
            int passes = 0;
            while (!older.isEmpty()) {
                if (++passes > policy.maxCompressionPasses()) {
                    throw new IllegalArgumentException("历史较长，超过单次压缩预算；请新建对话或调整后端配置，原始记录仍保留");
                }
                summary = compress(session, older, summary);
                long covered = summary.coveredThroughSeq();
                older.removeIf(message -> message.seq() <= covered);
            }
            remaining = planner.uncovered(remaining, summary);
        }
        // Drop low-priority documents if necessary, never silently discard recent conversation turns.
        var plan = planner.plan(remaining, summary, input, documents, settings);
        if (summary != null && summary != previous) {
            RunContext.check();
            // Content, version and covered sequence are stored together only after every batch and final plan succeed.
            records.put("summaries", session, summary);
        }
        return new PreparedContext(plan, summary);
    }

    private Summary compress(String session, List<ChatMessage> older, Summary previous) {
        String system = prompts.get("summary").text();
        StringBuilder input = new StringBuilder("已有摘要：").append(previous == null ? "无" : previous.content()).append("\n新增完整轮次：");
        int limit = settings.contextBudget() - 500 - 200;
        long covered = -1;
        for (int i = 0; i + 1 < older.size(); i += 2) {
            var user = older.get(i);
            var assistant = older.get(i + 1);
            String pair = "\nuser:" + user.content() + "\nassistant:" + assistant.content();
            if (ContextPlanner.estimate(system) + ContextPlanner.estimate(input + pair) > limit) break;
            input.append(pair);
            covered = assistant.seq();
        }
        if (covered < 0) throw new IllegalArgumentException("单轮历史超过摘要输入预算，原始记录仍保留，请新建对话");
        RunContext.check();
        String content = ai.complete("summary", List.of(new SystemMessage(system), new UserMessage(input.toString())), 500).text();
        if (content == null || content.isBlank() || ContextPlanner.estimate(content) > 1400) {
            throw new IllegalStateException("摘要为空或超出预算，原摘要及覆盖范围保持不变");
        }
        RunContext.check();
        return new Summary(session, previous == null ? 1 : previous.version() + 1, covered, content, Instant.now().toString());
    }
}
