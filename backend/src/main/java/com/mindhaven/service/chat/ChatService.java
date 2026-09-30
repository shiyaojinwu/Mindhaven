package com.mindhaven.service.chat;

import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.config.Settings;
import com.mindhaven.integration.ai.AiGateway;
import com.mindhaven.integration.ai.PromptRepository;
import com.mindhaven.manager.MessageManager;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.chat.PreparedContext;
import com.mindhaven.model.chat.ContextPlan;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import com.mindhaven.model.chat.ChatEvent;
import com.mindhaven.model.chat.Metrics;
import com.mindhaven.model.chat.Session;
import com.mindhaven.model.chat.Summary;
import com.mindhaven.model.chat.TurnResult;
import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.model.knowledge.RetrievalResult;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.ai.AiOperations;
import com.mindhaven.service.agent.AgentRunner;
import com.mindhaven.model.ai.Recommendation;
import com.mindhaven.service.ai.RunContext;
import com.mindhaven.service.knowledge.KnowledgeService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.mindhaven.common.Times.now;

@Service
public class ChatService {
    private final AgentRunner agent;
    private final SessionService sessions;
    private final MessageManager messages;
    private final AiOperations ai;
    private final PromptRepository prompts;
    private final KnowledgeService knowledge;
    private final ConversationContextService contexts;
    private final Settings settings;
    private final CitationVerifier citationVerifier;
    private final ConversationIntent intents;
    private final QueryRewriteService rewriter;
    private final ChatCommitService commits;
    private final ConcurrentHashMap<String, Boolean> active = new ConcurrentHashMap<>();

    public ChatService(SessionService sessions, MessageManager messages, AiOperations ai,
                       PromptRepository prompts, KnowledgeService knowledge, ConversationContextService contexts,
                       Settings settings, CitationVerifier citationVerifier, ConversationIntent intents,
                       QueryRewriteService rewriter, ChatCommitService commits, AgentRunner agent) {
        this.agent = agent;
        this.sessions = sessions;
        this.messages = messages;
        this.ai = ai;
        this.prompts = prompts;
        this.knowledge = knowledge;
        this.contexts = contexts;
        this.settings = settings;
        this.citationVerifier = citationVerifier;
        this.intents = intents;
        this.rewriter = rewriter;
        this.commits = commits;
    }

    public static boolean urgent(String text) {
        return List.of("自杀", "不想活", "伤害自己", "割腕", "结束生命", "想死").stream().anyMatch(text::contains);
    }

    public void turn(String id, String input, String topic, String version, boolean rewrite, Consumer<ChatEvent> event) {
        turn(id, input, topic, version, rewrite, event, result -> {
        });
    }


    /**
     * onCommit stores the runtime completion in the same transaction as the final messages.
     */
    public void turn(String id, String input, String topic, String version, boolean rewrite, Consumer<ChatEvent> event, Consumer<TurnResult> onCommit) {
        var who = TenantContext.require();
        String lockKey = who.tenantId() + ":" + who.userId() + ":" + id;
        if (active.putIfAbsent(lockKey, true) != null) throw new HttpProblem(409, "上一条回复仍在生成，请稍后再试");
        long started = System.nanoTime();
        ChatMessage user = null;
        boolean committed = false;
        try {
            Session session = sessions.session(id);
            contexts.validateInput(input);
            Summary summary = contexts.summary(id);
            var complete = messages.completeAfter(id, summary == null ? 0 : summary.coveredThroughSeq());
            boolean safety = urgent(input);
            boolean agentMode = agent.enabled() && !safety;
            var intent = intents.decide(input, !complete.isEmpty() || summary != null);
            RunContext.check();
            if (safety) summary = null;
            String query = rewrite && !safety && !agentMode && intent.retrieve() ? rewriter.rewrite(input, complete, summary) : input;
            var retrieval = agentMode ? new RetrievalResult("agent", List.of(), List.of()) : safety ? new RetrievalResult("safety", List.of(), List.of()) : intent.retrieve() ? knowledge.retrieve(query, topic, version, 4) : new RetrievalResult(intent.mode(), List.of(), List.of());
            var found = retrieval.citations();
            var prepared = agentMode && RunContext.parentId() != null
                    ? new PreparedContext(new ContextPlan(List.of(new SystemMessage(""), new UserMessage(input)), List.of(), 0), summary)
                    : contexts.prepare(id, safety ? List.of() : complete, summary, input, found);
            var plan = prepared.plan();
            summary = prepared.summary();
            long seq = messages.maxSequence(id) + 1;
            user = new ChatMessage(UUID.randomUUID().toString(), id, seq, "user", input, now(), List.of(), "pending");
            messages.save(user);
            event.accept(new ChatEvent.Sources(plan.citations()));
            long[] first = {-1};
            StringBuilder answer = new StringBuilder();
            Consumer<String> delta = d -> {
                if (first[0] < 0) first[0] = (System.nanoTime() - started) / 1_000_000;
                answer.append(d);
                event.accept(new ChatEvent.Delta(d));
            };
            AiGateway.Result response;
            List<Citation> usedCitations = plan.citations();
            List<Recommendation> recommendations = List.of();
            int contextEstimate = plan.estimate();
            boolean incomplete = false;
            List<ChatEvent.AgentStatus> execution = new ArrayList<>();
            if (safety) {
                String help = "听到你这样说，我很在意你现在的安全。你此刻是否处在危险中，或者已经伤害了自己？如果是，请立即联系当地急救服务，并请身边可信任的人陪着你。你不需要独自面对这些。这个应用无法提供紧急救援。";
                delta.accept(help);
                response = new AiGateway.Result(help, null, null);
            } else if (agentMode) {
                var result = agent.run(plan.messages(), topic, version, agentEvent -> {
                    if (agentEvent instanceof ChatEvent.AgentStatus stage) execution.add(stage);
                    if (agentEvent instanceof ChatEvent.Delta chunk) delta.accept(chunk.text());
                    else {
                        if (agentEvent instanceof ChatEvent.AnswerReset) {
                            // Archive the complete draft, including chunks still buffered by the event publisher.
                            if (!answer.toString().isBlank()) {
                                int step = execution.isEmpty() ? 1 : execution.getLast().step();
                                var draft = new ChatEvent.AgentStatus("draft", "中间草稿 · 已撤回，非最终答案",
                                        step, null, null, null, answer.toString());
                                execution.add(draft);
                                event.accept(draft);
                            }
                            answer.setLength(0);
                            first[0] = -1;
                        }
                        event.accept(agentEvent);
                    }
                });
                response = result.answer();
                incomplete = result.incomplete();
                usedCitations = result.citations();
                recommendations = result.recommendations();
                retrieval = result.retrieval();
                found = retrieval.citations();
                contextEstimate = result.contextEstimate();
            } else response = ai.stream("answer", plan.messages(), settings.outputBudget(), delta);
            var citationCheck = citationVerifier.verify(answer.toString(), usedCitations);
            var assistant = new ChatMessage(UUID.randomUUID().toString(), id, seq + 1, "assistant", answer.toString(), now(), usedCitations, incomplete ? "partial" : "complete", citationCheck, recommendations, execution);
            var doneUser = new ChatMessage(user.id(), id, seq, "user", input, user.createdAt(), List.of(), "complete");
            var metrics = new Metrics(UUID.randomUUID().toString(), id, settings.aiMode(), settings.aiMode().equals("demo") ? "deterministic-demo" : settings.chatModel(), prompts.get(agentMode ? "agent" : "answer").hash(), version, query, found.size(), found.stream().map(Citation::id).toList(), contextEstimate, response.promptTokens(), response.completionTokens(), first[0], (System.nanoTime() - started) / 1_000_000, summary == null ? 0 : summary.version(), summary == null ? 0 : summary.coveredThroughSeq(), citationCheck.passed(), now(), citationCheck, retrieval.mode(), retrieval.matches(), usedCitations.stream().map(Citation::id).toList(), retrieval.configuration());
            RunContext.check();
            commits.commit(session, doneUser, assistant, metrics, onCommit);
            committed = true;
            event.accept(new ChatEvent.Done(new TurnResult(assistant, metrics)));
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
