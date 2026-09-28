package com.mindhaven.service.chat;

import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.config.Settings;
import com.mindhaven.integration.ai.AiGateway;
import com.mindhaven.integration.ai.PromptRepository;
import com.mindhaven.manager.MessageManager;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.manager.SessionManager;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.chat.Metrics;
import com.mindhaven.model.chat.Session;
import com.mindhaven.model.chat.Summary;
import com.mindhaven.model.chat.TurnResult;
import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.model.knowledge.RetrievalResult;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.ai.AiOperations;
import com.mindhaven.service.ai.RunContext;
import com.mindhaven.service.knowledge.KnowledgeService;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Service
public class ChatService {
    private final RecordManager store;
    private final SessionManager sessions;
    private final MessageManager messages;
    private final AiOperations ai;
    private final PromptRepository prompts;
    private final KnowledgeService knowledge;
    private final ConversationContextService contexts;
    private final Settings settings;
    private final CitationVerifier citationVerifier;
    private final ConversationIntent intents;
    private final TransactionTemplate tx;
    private final ConcurrentHashMap<String, Boolean> active = new ConcurrentHashMap<>();

    public ChatService(SessionManager sessions, MessageManager messages, RecordManager store, AiOperations ai, PromptRepository prompts, KnowledgeService knowledge, ConversationContextService contexts, Settings settings, CitationVerifier citationVerifier, ConversationIntent intents, PlatformTransactionManager tm) {
        this.store = store;
        this.sessions = sessions;
        this.messages = messages;
        this.ai = ai;
        this.prompts = prompts;
        this.knowledge = knowledge;
        this.contexts = contexts;
        this.settings = settings;
        this.citationVerifier = citationVerifier;
        this.intents = intents;
        this.tx = new TransactionTemplate(tm);
    }

    public Session create() {
        var s = new Session(UUID.randomUUID().toString(), "新的对话", now());
        sessions.save(s);
        return s;
    }

    public List<Session> sessions() {
        return sessions.list().reversed();
    }

    public Session session(String id) {
        return sessions.get(id).orElseThrow(() -> new NoSuchElementException("对话不存在"));
    }

    public List<ChatMessage> history(String id) {
        session(id);
        return messages.list(id);
    }

    public Optional<Summary> summary(String id) {
        session(id);
        return store.get("summaries", id, Summary.class);
    }

    public List<Metrics> metrics() {
        return store.list("metrics", Metrics.class).reversed();
    }

    public static String now() {
        return Instant.now().toString();
    }

    public static boolean urgent(String text) {
        return List.of("自杀", "不想活", "伤害自己", "割腕", "结束生命", "想死").stream().anyMatch(text::contains);
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

    public void turn(String id, String input, String topic, String version, boolean rewrite, BiConsumer<String, Object> event) {
        turn(id, input, topic, version, rewrite, event, result -> {
        });
    }


    /**
     * onCommit stores the runtime completion in the same transaction as the final messages.
     */
    public void turn(String id, String input, String topic, String version, boolean rewrite, BiConsumer<String, Object> event, Consumer<TurnResult> onCommit) {
        var who = TenantContext.require();
        String lockKey = who.tenantId() + ":" + who.userId() + ":" + id;
        if (active.putIfAbsent(lockKey, true) != null) throw new HttpProblem(409, "上一条回复仍在生成，请稍后再试");
        long started = System.nanoTime();
        ChatMessage user = null;
        boolean committed = false;
        try {
            Session session = session(id);
            contexts.validateInput(input);
            Summary summary = contexts.summary(id);
            var complete = messages.completeAfter(id, summary == null ? 0 : summary.coveredThroughSeq());
            boolean safety = urgent(input);
            var intent = intents.decide(input, !complete.isEmpty() || summary != null);
            RunContext.check();
            if (safety) summary = null;
            String query = rewrite && !safety && intent.retrieve() ? rewrite(input, complete, summary) : input;
            var retrieval = safety ? new RetrievalResult("safety", List.of(), List.of()) : intent.retrieve() ? knowledge.retrieve(query, topic, version, 4) : new RetrievalResult(intent.mode(), List.of(), List.of());
            var found = retrieval.citations();
            var prepared = contexts.prepare(id, safety ? List.of() : complete, summary, input, found);
            var plan = prepared.plan();
            summary = prepared.summary();
            long seq = messages.maxSequence(id) + 1;
            user = new ChatMessage(UUID.randomUUID().toString(), id, seq, "user", input, now(), List.of(), "pending");
            messages.save(user);
            event.accept("sources", plan.citations());
            long[] first = {-1};
            StringBuilder answer = new StringBuilder();
            Consumer<String> delta = d -> {
                if (first[0] < 0) first[0] = (System.nanoTime() - started) / 1_000_000;
                answer.append(d);
                event.accept("delta", Map.of("text", d));
            };
            AiGateway.Result response;
            if (urgent(input)) {
                String help = "听到你这样说，我很在意你现在的安全。你此刻是否处在危险中，或者已经伤害了自己？如果是，请立即联系当地急救服务，并请身边可信任的人陪着你。你不需要独自面对这些。这个应用无法提供紧急救援。";
                delta.accept(help);
                response = new AiGateway.Result(help, null, null);
            } else response = ai.stream("answer", plan.messages(), settings.outputBudget(), delta);
            var citationCheck = citationVerifier.verify(answer.toString(), plan.citations());
            var assistant = new ChatMessage(UUID.randomUUID().toString(), id, seq + 1, "assistant", answer.toString(), now(), plan.citations(), "complete", citationCheck);
            var doneUser = new ChatMessage(user.id(), id, seq, "user", input, user.createdAt(), List.of(), "complete");
            var metrics = new Metrics(UUID.randomUUID().toString(), id, settings.aiMode(), settings.aiMode().equals("demo") ? "deterministic-demo" : settings.chatModel(), prompts.get("answer").hash(), version, query, found.size(), found.stream().map(Citation::id).toList(), plan.estimate(), response.promptTokens(), response.completionTokens(), first[0], (System.nanoTime() - started) / 1_000_000, summary == null ? 0 : summary.version(), summary == null ? 0 : summary.coveredThroughSeq(), citationCheck.passed(), now(), citationCheck, retrieval.mode(), retrieval.matches(), plan.citations().stream().map(Citation::id).toList(), retrieval.configuration());
            RunContext.check();
            tx.executeWithoutResult(status -> {
                messages.save(doneUser);
                messages.save(assistant);
                store.put("metrics", metrics.id(), metrics);
                if (session.title().equals("新的对话"))
                    sessions.save(new Session(id, input.substring(0, Math.min(input.length(), 18)), session.createdAt()));
                // The runtime's done event and completed messages commit atomically.
                onCommit.accept(new TurnResult(assistant, metrics));
            });
            committed = true;
            event.accept("done", new TurnResult(assistant, metrics));
        } catch (RuntimeException e) {
            if (user != null && !committed) {
                var failed = new ChatMessage(user.id(), id, user.seq(), "user", user.content(), user.createdAt(), List.of(), "failed");
                messages.save(failed);
            }
            throw e;
        } finally {
            active.remove(lockKey);
        }
    }
}
