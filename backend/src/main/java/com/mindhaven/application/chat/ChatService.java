package com.mindhaven.application.chat;

import com.mindhaven.application.ai.AiOperations;
import com.mindhaven.application.ai.RunContext;
import com.mindhaven.application.knowledge.KnowledgeService;
import com.mindhaven.config.Settings;
import com.mindhaven.domain.model.Models;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.model.RetrievalResult;
import com.mindhaven.domain.port.AiGateway;
import com.mindhaven.domain.port.PromptRepository;
import com.mindhaven.domain.port.RecordStore;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatService {
  private final RecordStore store;
  private final AiOperations ai;
  private final PromptRepository prompts;
  private final KnowledgeService knowledge;
  private final ContextPlanner planner;
  private final Settings settings;
  private final CitationVerifier citationVerifier;
  private final ConversationIntent intents;
  private final TransactionTemplate tx;
  private final java.util.concurrent.ConcurrentHashMap<String, Boolean> active =
      new java.util.concurrent.ConcurrentHashMap<>();

  public ChatService(
      RecordStore store,
      AiOperations ai,
      PromptRepository prompts,
      KnowledgeService knowledge,
      ContextPlanner planner,
      Settings settings,
      CitationVerifier citationVerifier,
      ConversationIntent intents,
      PlatformTransactionManager tm) {
    this.store = store;
    this.ai = ai;
    this.prompts = prompts;
    this.knowledge = knowledge;
    this.planner = planner;
    this.settings = settings;
    this.citationVerifier = citationVerifier;
    this.intents = intents;
    this.tx = new TransactionTemplate(tm);
  }

  public Session create() {
    var s = new Session(UUID.randomUUID().toString(), "新的对话", now());
    store.put("sessions", s.id(), s);
    return s;
  }

  public List<Session> sessions() {
    return store.list("sessions", Session.class).reversed();
  }

  public Session session(String id) {
    return store
        .get("sessions", id, Session.class)
        .orElseThrow(() -> new NoSuchElementException("对话不存在"));
  }

  public List<Models.Message> history(String id) {
    session(id);
    return store.list("messages:" + id, Models.Message.class).stream()
        .sorted(Comparator.comparingLong(Models.Message::seq))
        .toList();
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

  public String rewrite(String input, List<Models.Message> history, Summary summary) {
    if (history.isEmpty() && summary == null) return input;
    if (settings.aiMode().equals("demo")) {
      if (input.matches(".*(它|这个|那个|怎么办|那我|为什么|这样|这种).*"))
        return history.stream()
            .filter(m -> m.role().equals("user"))
            .reduce((a, b) -> b)
            .map(m -> m.content() + "；" + input)
            .orElse(input);
      return input;
    }
    StringBuilder context = new StringBuilder();
    if (summary != null) context.append(summary.content());
    for (var m : history.subList(Math.max(0, history.size() - 4), history.size()))
      context.append("\n").append(m.role()).append(":").append(m.content());
    var result =
        ai.complete(
            "rewrite",
            List.of(
                new SystemMessage(prompts.get("rewrite").text()),
                new UserMessage(
                    "历史：" + ContextPlanner.clip(context.toString(), 2400) + "\n当前问题：" + input)),
            200);
    return result.text().isBlank() ? input : ContextPlanner.clip(result.text(), 1200);
  }

  private Summary compress(String id, List<Models.Message> history, Summary summary) {
    List<Models.Message> uncovered =
        history.stream()
            .filter(m -> summary == null || m.seq() > summary.coveredThroughSeq())
            .toList();
    if (uncovered.size() <= 4
        || (uncovered.size() <= 8
            && uncovered.stream().mapToInt(m -> ContextPlanner.estimate(m.content())).sum()
                <= settings.historyBudget())) return summary;
    List<Models.Message> prefix = new ArrayList<>();
    int budget =
        settings.contextBudget()
            - 500
            - 400
            - ContextPlanner.estimate(prompts.get("summary").text())
            - ContextPlanner.estimate(summary == null ? "无" : summary.content());
    for (int i = 0; i + 1 < uncovered.size() - 4; i += 2) {
      var a = uncovered.get(i);
      var b = uncovered.get(i + 1);
      int size = ContextPlanner.estimate(a.content()) + ContextPlanner.estimate(b.content());
      if (size > budget) {
        if (prefix.isEmpty())
          throw new IllegalArgumentException("有一轮对话超过摘要输入预算，请提高上下文预算或开始新的对话；原始记录仍保留");
        break;
      }
      prefix.add(a);
      prefix.add(b);
      budget -= size;
    }
    if (prefix.isEmpty()) return summary;
    StringBuilder input =
        new StringBuilder("已有摘要：")
            .append(summary == null ? "无" : summary.content())
            .append("\n新增完整轮次：");
    for (var m : prefix) input.append("\n").append(m.role()).append(":").append(m.content());
    String content =
        ai.complete(
                "summary",
                List.of(
                    new SystemMessage(prompts.get("summary").text()),
                    new UserMessage(input.toString())),
                500)
            .text();
    if (content.isBlank()) throw new IllegalStateException("Empty summary");
    if (ContextPlanner.estimate(content) > 1400)
      throw new IllegalStateException("摘要超出预算，覆盖范围保持不变，请重试");
    RunContext.check();
    Summary next =
        new Summary(
            id,
            summary == null ? 1 : summary.version() + 1,
            prefix.getLast().seq(),
            content,
            now());
    store.put("summaries", id, next);
    return next;
  }

  public void turn(
      String id,
      String input,
      String topic,
      String version,
      boolean rewrite,
      boolean compression,
      BiConsumer<String, Object> event) {
    turn(id, input, topic, version, rewrite, compression, event, result -> {});
  }

  public record TurnResult(Models.Message message, Metrics metrics) {}

  /** onCommit stores the runtime completion in the same transaction as the final messages. */
  public void turn(
      String id,
      String input,
      String topic,
      String version,
      boolean rewrite,
      boolean compression,
      BiConsumer<String, Object> event,
      java.util.function.Consumer<TurnResult> onCommit) {
    var who = com.mindhaven.security.TenantContext.require();
    String lockKey = who.tenantId() + ":" + who.userId() + ":" + id;
    if (active.putIfAbsent(lockKey, true) != null)
      throw new com.mindhaven.common.error.HttpProblem(409, "上一条回复仍在生成，请稍后再试");
    long started = System.nanoTime();
    Models.Message user = null;
    boolean committed = false;
    try {
      Session session = session(id);
      var all = history(id);
      var complete = all.stream().filter(m -> m.status().equals("complete")).toList();
      Summary summary = store.get("summaries", id, Summary.class).orElse(null);
      boolean safety = urgent(input);
      var intent = intents.decide(input, !complete.isEmpty() || summary != null);
      RunContext.check();
      if (compression && !safety) summary = compress(id, complete, summary);
      // Disabling compression also disables summary injection, enabling a clean baseline.
      if (!compression || safety) summary = null;
      String query =
          rewrite && !safety && intent.retrieve() ? rewrite(input, complete, summary) : input;
      var retrieval =
          safety
              ? new RetrievalResult("safety", List.of(), List.of())
              : intent.retrieve()
                  ? knowledge.retrieve(query, topic, version, 4)
                  : new RetrievalResult(intent.mode(), List.of(), List.of());
      var found = retrieval.citations();
      var plan = planner.plan(safety ? List.of() : complete, summary, input, found, settings);
      long seq = all.stream().mapToLong(Models.Message::seq).max().orElse(0) + 1;
      user =
          new Models.Message(
              UUID.randomUUID().toString(), id, seq, "user", input, now(), List.of(), "pending");
      store.put("messages:" + id, user.id(), user);
      event.accept("sources", plan.citations());
      long[] first = {-1};
      StringBuilder answer = new StringBuilder();
      java.util.function.Consumer<String> delta =
          d -> {
            if (first[0] < 0) first[0] = (System.nanoTime() - started) / 1_000_000;
            answer.append(d);
            event.accept("delta", Map.of("text", d));
          };
      AiGateway.Result response;
      if (urgent(input)) {
        String help =
            "听到你这样说，我很在意你现在的安全。你此刻是否处在危险中，或者已经伤害了自己？如果是，请立即联系当地急救服务，并请身边可信任的人陪着你。你不需要独自面对这些。这个应用无法提供紧急救援。";
        delta.accept(help);
        response = new AiGateway.Result(help, null, null);
      } else response = ai.stream("answer", plan.messages(), settings.outputBudget(), delta);
      var citationCheck = citationVerifier.verify(answer.toString(), plan.citations());
      var assistant =
          new Models.Message(
              UUID.randomUUID().toString(),
              id,
              seq + 1,
              "assistant",
              answer.toString(),
              now(),
              plan.citations(),
              "complete",
              citationCheck);
      var doneUser =
          new Models.Message(
              user.id(), id, seq, "user", input, user.createdAt(), List.of(), "complete");
      var metrics =
          new Metrics(
              UUID.randomUUID().toString(),
              id,
              settings.aiMode(),
              settings.aiMode().equals("demo") ? "deterministic-demo" : settings.chatModel(),
              prompts.get("answer").hash(),
              version,
              query,
              found.size(),
              found.stream().map(Citation::id).toList(),
              plan.estimate(),
              response.promptTokens(),
              response.completionTokens(),
              first[0],
              (System.nanoTime() - started) / 1_000_000,
              summary == null ? 0 : summary.version(),
              summary == null ? 0 : summary.coveredThroughSeq(),
              citationCheck.passed(),
              now(),
              citationCheck,
              retrieval.mode(),
              retrieval.matches(),
              plan.citations().stream().map(Citation::id).toList(),
              retrieval.configuration());
      RunContext.check();
      tx.executeWithoutResult(
          status -> {
            store.put("messages:" + id, doneUser.id(), doneUser);
            store.put("messages:" + id, assistant.id(), assistant);
            store.put("metrics", metrics.id(), metrics);
            if (session.title().equals("新的对话"))
              store.put(
                  "sessions",
                  id,
                  new Session(
                      id, input.substring(0, Math.min(input.length(), 18)), session.createdAt()));
            // The runtime's done event and completed messages commit atomically.
            onCommit.accept(new TurnResult(assistant, metrics));
          });
      committed = true;
      event.accept("done", new TurnResult(assistant, metrics));
    } catch (RuntimeException e) {
      if (user != null && !committed) {
        var failed =
            new Models.Message(
                user.id(),
                id,
                user.seq(),
                "user",
                user.content(),
                user.createdAt(),
                List.of(),
                "failed");
        store.put("messages:" + id, user.id(), failed);
      }
      throw e;
    } finally {
      active.remove(lockKey);
    }
  }
}
