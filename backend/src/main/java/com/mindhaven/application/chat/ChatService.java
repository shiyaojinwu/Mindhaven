package com.mindhaven.application.chat;

import com.mindhaven.application.knowledge.KnowledgeService;
import com.mindhaven.config.Settings;
import com.mindhaven.domain.model.Models;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.port.AiGateway;
import com.mindhaven.domain.port.RecordStore;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatService {
  private final RecordStore store;
  private final AiGateway ai;
  private final KnowledgeService knowledge;
  private final ContextPlanner planner;
  private final Settings settings;
  private final TransactionTemplate tx;
  private final ReentrantLock[] locks = new ReentrantLock[64];

  public ChatService(
      RecordStore store,
      AiGateway ai,
      KnowledgeService knowledge,
      ContextPlanner planner,
      Settings settings,
      PlatformTransactionManager tm) {
    this.store = store;
    this.ai = ai;
    this.knowledge = knowledge;
    this.planner = planner;
    this.settings = settings;
    this.tx = new TransactionTemplate(tm);
    Arrays.setAll(locks, i -> new ReentrantLock());
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
            List.of(
                new SystemMessage("REWRITE 将当前问题改写为独立可检索的问题，仅在历史明确支持时补全指代；不要回答，不要添加事实，只输出改写问题。"),
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
    int budget = 5200;
    for (int i = 0; i + 1 < uncovered.size() - 4; i += 2) {
      var a = uncovered.get(i);
      var b = uncovered.get(i + 1);
      int size = ContextPlanner.estimate(a.content()) + ContextPlanner.estimate(b.content());
      if (size > budget) break;
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
                List.of(
                    new SystemMessage(
                        "SUMMARY"
                            + " 更新历史摘要，最多250个汉字。保留用户明确陈述的事实、偏好、约束、未解决问题；区分用户事实和助手建议，不做诊断，不把推测变成事实。合并旧摘要与新增轮次，仅输出摘要。"),
                    new UserMessage(input.toString())),
                500)
            .text();
    if (content.isBlank()) throw new IllegalStateException("Empty summary");
    Summary next =
        new Summary(
            id,
            summary == null ? 1 : summary.version() + 1,
            prefix.getLast().seq(),
            ContextPlanner.clip(content, 1400),
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
    var lock = locks[Math.floorMod(id.hashCode(), locks.length)];
    if (!lock.tryLock()) throw new IllegalArgumentException("上一条回复仍在生成，请稍后再试");
    long started = System.nanoTime();
    Models.Message user = null;
    boolean committed = false;
    try {
      Session session = session(id);
      var all = history(id);
      var complete = all.stream().filter(m -> m.status().equals("complete")).toList();
      Summary summary = store.get("summaries", id, Summary.class).orElse(null);
      if (compression) summary = compress(id, complete, summary);
      // Disabling compression also disables summary injection, enabling a clean baseline.
      if (!compression) summary = null;
      String query = rewrite ? rewrite(input, complete, summary) : input;
      var found = urgent(input) ? List.<Citation>of() : knowledge.search(query, topic, version, 4);
      var plan = planner.plan(complete, summary, input, found, settings);
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
      } else response = ai.stream(plan.messages(), settings.outputBudget(), delta);
      Set<String> ids = new HashSet<>();
      plan.citations().forEach(c -> ids.add(c.id()));
      var matcher =
          java.util.regex.Pattern.compile("\\[([a-zA-Z0-9-]+)\\]").matcher(response.text());
      boolean valid = true;
      while (matcher.find()) if (!ids.contains(matcher.group(1))) valid = false;
      // Identifier validity is not semantic citation support; the latter is reviewed in evaluation.
      var assistant =
          new Models.Message(
              UUID.randomUUID().toString(),
              id,
              seq + 1,
              "assistant",
              answer.toString(),
              now(),
              plan.citations(),
              "complete");
      var doneUser =
          new Models.Message(
              user.id(), id, seq, "user", input, user.createdAt(), List.of(), "complete");
      var metrics =
          new Metrics(
              UUID.randomUUID().toString(),
              id,
              settings.aiMode(),
              settings.aiMode().equals("demo") ? "deterministic-demo" : settings.chatModel(),
              "rag-v1",
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
              valid,
              now());
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
          });
      committed = true;
      event.accept("done", Map.of("message", assistant, "metrics", metrics));
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
      lock.unlock();
    }
  }
}
