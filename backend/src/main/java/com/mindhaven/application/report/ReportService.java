package com.mindhaven.application.report;

import com.mindhaven.application.chat.ChatService;
import com.mindhaven.config.Settings;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.port.AiGateway;
import com.mindhaven.domain.port.RecordStore;
import java.util.*;
import org.springframework.ai.chat.messages.*;

@org.springframework.stereotype.Service
public class ReportService {
  private final RecordStore store;
  private final AiGateway ai;
  private final Settings settings;

  public ReportService(RecordStore store, AiGateway ai, Settings settings) {
    this.store = store;
    this.ai = ai;
    this.settings = settings;
  }

  private Assessment report(String id) {
    return store
        .get("assessments", id, Assessment.class)
        .orElseThrow(() -> new NoSuchElementException("报告不存在"));
  }

  public Object read(String id) {
    report(id);
    return store
        .get("report-analysis", id, ReportAnalysis.class)
        .<Object>map(a -> a)
        .orElse(Map.of());
  }

  public synchronized ReportAnalysis analyze(String id) {
    Assessment r = report(id);
    var existing = store.get("report-analysis", id, ReportAnalysis.class);
    if (existing.isPresent() && existing.get().mode().equals(settings.aiMode()))
      return existing.get();
    StringBuilder facts =
        new StringBuilder("问卷：" + r.surveyTitle() + "，版本" + r.surveyVersion() + "\n");
    for (var answer : r.answers())
      facts
          .append(answer.title())
          .append("：")
          .append(
              answer.type().equals("TEXT")
                  ? answer.text()
                  : String.join("、", answer.selectedLabels()))
          .append("\n");
    String content =
        settings.aiMode().equals("demo")
            ? "这是一段演示解读，用于验证报告生成与保存。你可以回看得分较高的题目，记录具体情境，以及希望得到的支持。分数没有临床阈值，不能据此判断疾病或风险等级。"
            : ai.complete(
                    List.of(
                        new SystemMessage(
                            "根据用户的自编状态记录，用中文写一段不超过300字的温和解读。只描述已提供的感受和频率，提出可以选择的小行动。不诊断，不使用临床分级或风险等级，不推断疾病。不建议药物。明确这不是经过验证的量表；当困扰持续影响生活时，可以寻求可信任的人或专业人员帮助。不要编造用户经历。"),
                        new UserMessage(facts.toString())),
                    600)
                .text();
    var analysis = new ReportAnalysis(id, settings.aiMode(), content, ChatService.now());
    store.put("report-analysis", id, analysis);
    return analysis;
  }
}
