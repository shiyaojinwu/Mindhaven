package com.mindhaven.service.report;

import com.mindhaven.common.Hashes;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.config.Settings;
import com.mindhaven.integration.ai.*;
import com.mindhaven.integration.storage.*;
import com.mindhaven.integration.vector.*;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.model.questionnaire.Assessment;
import com.mindhaven.model.report.ReportAnalysis;
import com.mindhaven.model.vo.ReportAnalysisResponse;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.ai.AiOperations;
import com.mindhaven.service.ai.RunContext;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Service
public class ReportService {
    private final RecordManager store;
    private final AiOperations ai;
    private final Settings settings;
    private final PromptRepository prompts;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Semaphore capacity = new Semaphore(4);

    public ReportService(RecordManager store, AiOperations ai, Settings settings, PromptRepository prompts) {
        this.store = store;
        this.ai = ai;
        this.settings = settings;
        this.prompts = prompts;
    }

    private Assessment report(String id) {
        return store.get("assessments", id, Assessment.class).orElseThrow(() -> new NoSuchElementException("报告不存在"));
    }

    public ReportAnalysisResponse read(String id) {
        report(id);
        return store.get("report-analysis", id, ReportAnalysis.class).map(ReportAnalysisResponse::from).orElseGet(ReportAnalysisResponse::empty);
    }

    public ReportAnalysis analyze(String id) {
        Assessment r = report(id);
        var who = TenantContext.require();
        String key = who.tenantId() + ":" + who.userId() + ":" + id;
        if (!active.add(key)) throw new HttpProblem(409, "这份报告正在生成，请稍后查看");
        if (!capacity.tryAcquire()) {
            active.remove(key);
            throw new HttpProblem(429, "报告任务较多，请稍后重试");
        }
        try (var scope = RunContext.open("report:" + id, () -> false)) {
            StringBuilder facts = new StringBuilder("问卷：" + r.surveyTitle() + "，版本" + r.surveyVersion() + "\n");
            for (var answer : r.answers())
                facts.append(answer.title()).append("：").append(answer.type().equals("TEXT") ? answer.text() : String.join("、", answer.selectedLabels())).append("\n");
            var template = prompts.get("report");
            String fingerprint = Hashes.sha256(settings.aiMode() + settings.chatModel() + template.hash() + facts);
            var old = store.get("report-analysis", id, ReportAnalysis.class);
            if (old.isPresent() && fingerprint.equals(old.get().fingerprint())) return old.get();
            String text = ai.complete("report", List.of(new SystemMessage(template.text()), new UserMessage(facts.toString())), 600).text();
            var result = new ReportAnalysis(id, settings.aiMode(), text, Instant.now().toString(), fingerprint);
            store.put("report-analysis", id, result);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            capacity.release();
            active.remove(key);
        }
    }
}
