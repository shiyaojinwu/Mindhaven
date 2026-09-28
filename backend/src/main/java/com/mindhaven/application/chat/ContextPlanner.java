package com.mindhaven.application.chat;

import com.mindhaven.config.Settings;
import com.mindhaven.domain.model.Models;
import com.mindhaven.domain.model.Models.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Component;

@Component
public class ContextPlanner {
  public record Plan(
      List<org.springframework.ai.chat.messages.Message> messages,
      List<Citation> citations,
      int estimate) {}

  private final com.mindhaven.domain.port.PromptRepository prompts;

  private final ContextRenderer renderer;

  public ContextPlanner(
      com.mindhaven.domain.port.PromptRepository prompts, ContextRenderer renderer) {
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

  public Plan plan(
      List<Models.Message> history,
      Summary summary,
      String input,
      List<Citation> docs,
      Settings s) {
    int max = s.contextBudget() - s.outputBudget();
    int mandatory = estimate(prompts.get("answer").text()) + estimate(input) + 200;
    if (mandatory > max) throw new IllegalArgumentException("消息太长，请缩短后再发送");
    int remaining = max - mandatory;
    String summaryText = "";
    if (summary != null) {
      summaryText = renderer.summary(summary.content());
      if (estimate(summaryText) > remaining) throw new IllegalArgumentException("摘要超过预算，请新建对话");
      remaining -= estimate(summaryText);
    }
    List<Citation> included = new ArrayList<>();
    String currentTurn = renderer.currentTurn(input, included);
    int docBudget = Math.min(s.knowledgeBudget(), remaining / 2);
    for (Citation d : docs) {
      var candidate = new ArrayList<>(included);
      candidate.add(d);
      String rendered = renderer.currentTurn(input, candidate);
      // Includes tags, metadata and escaping expansion, not only the source body.
      int cost = estimate(rendered) - estimate(currentTurn);
      if (cost <= docBudget) {
        included.add(d);
        currentTurn = rendered;
        docBudget -= cost;
        remaining -= cost;
      }
    }
    List<Models.Message> recent =
        history.stream()
            .filter(
                m ->
                    m.status().equals("complete")
                        && (summary == null || m.seq() > summary.coveredThroughSeq()))
            .toList();
    List<Models.Message> keep = new ArrayList<>();
    int histBudget = Math.min(s.historyBudget(), remaining);
    // Select whole completed user/assistant pairs, never an orphan assistant turn.
    for (int i = recent.size() - 1; i >= 1; i -= 2) {
      var assistant = recent.get(i);
      var user = recent.get(i - 1);
      if (!assistant.role().equals("assistant") || !user.role().equals("user")) continue;
      int cost = estimate(assistant.content()) + estimate(user.content());
      if (cost > histBudget) break;
      keep.add(0, assistant);
      keep.add(0, user);
      histBudget -= cost;
    }
    List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(prompts.get("answer").text()));
    if (summary != null) messages.add(new UserMessage(summaryText));
    for (var m : keep)
      messages.add(
          m.role().equals("user")
              ? new UserMessage(m.content())
              : new AssistantMessage(m.content()));
    messages.add(new UserMessage(currentTurn));
    int total = messages.stream().mapToInt(m -> estimate(m.getText())).sum();
    if (total > max) throw new IllegalArgumentException("上下文超过预算");
    return new Plan(messages, included, total);
  }
}
