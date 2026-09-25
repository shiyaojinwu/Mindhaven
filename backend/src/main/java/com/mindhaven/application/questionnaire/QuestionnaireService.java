package com.mindhaven.application.questionnaire;

import com.mindhaven.application.chat.ChatService;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.port.RecordStore;
import com.mindhaven.security.TenantContext;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QuestionnaireService {
  private final RecordStore store;
  private final TransactionTemplate tx;

  public QuestionnaireService(RecordStore store, TransactionTemplate tx) {
    this.store = store;
    this.tx = tx;
  }

  public record DraftInput(
      String title, String description, List<Question> questions, int expectedRevision) {}

  public record Submission(int version, List<AnswerInput> answers) {}

  private void check(boolean valid, String message) {
    if (!valid) throw new IllegalArgumentException(message);
  }

  private void text(String value, int max, String label) {
    check(
        value != null && !value.isBlank() && value.length() <= max, label + "不能为空且最多" + max + "字");
  }

  private void validate(DraftInput i) {
    text(i.title(), 120, "标题");
    check(i.description() != null && i.description().length() <= 2000, "说明最多2000字");
    check(
        i.questions() != null && i.questions().size() >= 1 && i.questions().size() <= 50,
        "问卷需要1至50道题");
    Set<String> ids = new HashSet<>();
    for (var q : i.questions()) {
      check(q != null, "题目不能为空");
      text(q.id(), 60, "题目ID");
      check(ids.add(q.id()), "题目ID重复");
      text(q.title(), 500, "题目");
      check(
          Set.of("SINGLE", "MULTIPLE", "TEXT").contains(q.type() == null ? "" : q.type()), "题型无效");
      check(q.options() != null, "选项不能为空");
      if (q.type().equals("TEXT")) {
        check(q.options().isEmpty(), "文本题不能设置选项");
        continue;
      }
      check(q.options().size() >= 2 && q.options().size() <= 12, "选择题需要2至12个选项");
      Set<String> opts = new HashSet<>();
      for (var o : q.options()) {
        check(o != null, "选项不能为空");
        text(o.id(), 60, "选项ID");
        check(opts.add(o.id()), "选项ID重复");
        text(o.label(), 200, "选项");
        check(o.score() >= 0 && o.score() <= 100, "选项分值为0至100");
      }
    }
  }

  public List<SurveyDraft> drafts() {
    TenantContext.requireAdmin();
    return store.list("survey-drafts", SurveyDraft.class).reversed();
  }

  private SurveyDraft draft(String id) {
    return store
        .get("survey-drafts", id, SurveyDraft.class)
        .orElseThrow(() -> new NoSuchElementException("问卷不存在"));
  }

  public synchronized SurveyDraft save(String id, DraftInput input) {
    TenantContext.requireAdmin();
    validate(input);
    return tx.execute(
        status -> {
          SurveyDraft old = id == null ? null : draft(id);
          if (old != null && old.revision() != input.expectedRevision())
            throw new HttpProblem(409, "问卷已被修改，请重新加载后编辑");
          var d =
              new SurveyDraft(
                  old == null ? UUID.randomUUID().toString() : id,
                  input.title().strip(),
                  input.description().strip(),
                  List.copyOf(input.questions()),
                  old == null ? 1 : old.revision() + 1,
                  old == null ? 0 : old.publishedVersion(),
                  old == null ? 0 : old.publishedRevision(),
                  old == null ? "DRAFT" : old.status(),
                  ChatService.now());
          store.put("survey-drafts", d.id(), d);
          return d;
        });
  }

  public synchronized SurveyDraft publish(String id, int revision, boolean archive) {
    TenantContext.requireAdmin();
    return tx.execute(
        status -> {
          var d = draft(id);
          if (d.revision() != revision) throw new HttpProblem(409, "问卷已更新，请重新加载");
          if (!archive && d.status().equals("PUBLISHED") && d.publishedRevision() == d.revision())
            return d;
          int v = archive ? d.publishedVersion() : d.publishedVersion() + 1;
          int next = d.revision() + 1;
          if (!archive) {
            validate(new DraftInput(d.title(), d.description(), d.questions(), d.revision()));
            var snap =
                new SurveySnapshot(
                    id + ":" + v,
                    id,
                    d.title(),
                    d.description(),
                    v,
                    d.questions(),
                    ChatService.now());
            store.put("survey-versions", snap.id(), snap);
          }
          var updated =
              new SurveyDraft(
                  id,
                  d.title(),
                  d.description(),
                  d.questions(),
                  next,
                  v,
                  archive ? d.publishedRevision() : next,
                  archive ? "ARCHIVED" : "PUBLISHED",
                  ChatService.now());
          store.put("survey-drafts", id, updated);
          return updated;
        });
  }

  public List<SurveySnapshot> published() {
    return store.list("survey-drafts", SurveyDraft.class).stream()
        .filter(d -> d.status().equals("PUBLISHED"))
        .map(d -> snapshot(d.id(), d.publishedVersion()))
        .toList();
  }

  private SurveySnapshot snapshot(String id, int version) {
    return store
        .get("survey-versions", id + ":" + version, SurveySnapshot.class)
        .orElseThrow(() -> new NoSuchElementException("问卷版本不存在"));
  }

  public synchronized Assessment submit(String id, Submission input) {
    return tx.execute(
        status -> {
          var d = draft(id);
          if (!d.status().equals("PUBLISHED")) throw new HttpProblem(409, "问卷尚未发布或已停用");
          var s = snapshot(id, input.version());
          check(input.answers() != null && input.answers().size() <= 50, "答卷格式无效");
          Map<String, AnswerInput> answers = new HashMap<>();
          Set<String> known = new HashSet<>();
          s.questions().forEach(q -> known.add(q.id()));
          for (var a : input.answers()) {
            check(a != null && a.questionId() != null && known.contains(a.questionId()), "包含未知题目");
            check(answers.put(a.questionId(), a) == null, "题目重复作答");
          }
          List<AnswerSnapshot> snapshots = new ArrayList<>();
          int score = 0, max = 0;
          for (var q : s.questions()) {
            var a = answers.getOrDefault(q.id(), new AnswerInput(q.id(), List.of(), ""));
            var selected = a.optionIds() == null ? List.<String>of() : a.optionIds();
            String value = a.text() == null ? "" : a.text().strip();
            check(
                selected.size() <= 12
                    && new HashSet<>(selected).size() == selected.size()
                    && selected.stream().noneMatch(Objects::isNull),
                "选项重复或无效");
            check(value.length() <= 2000, "文本答案最多2000字");
            int n = 0;
            List<String> labels = new ArrayList<>();
            if (q.type().equals("TEXT")) {
              check(selected.isEmpty(), "文本题不接受选项");
              check(!q.required() || !value.isBlank(), "请填写必答题：" + q.title());
            } else {
              check(value.isEmpty(), "选择题不接受文本答案");
              check(!q.required() || !selected.isEmpty(), "请填写必答题：" + q.title());
              check(!q.type().equals("SINGLE") || selected.size() <= 1, "单选题只能选择一项");
              for (String oid : selected) {
                var o =
                    q.options().stream()
                        .filter(x -> x.id().equals(oid))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("包含未知选项"));
                labels.add(o.label());
                n += o.score();
              }
              max +=
                  q.type().equals("SINGLE")
                      ? q.options().stream().mapToInt(Option::score).max().orElse(0)
                      : q.options().stream().mapToInt(Option::score).sum();
            }
            score += n;
            snapshots.add(new AnswerSnapshot(q.id(), q.title(), q.type(), labels, value, n));
          }
          var r =
              new Assessment(
                  UUID.randomUUID().toString(),
                  id,
                  s.version(),
                  s.title(),
                  snapshots,
                  score,
                  max,
                  "问卷填写记录",
                  "你完成了「"
                      + s.title()
                      + "」。得分 "
                      + score
                      + " / "
                      + max
                      + "，仅为管理员设置的选项分值加总（含选填题的满分），不代表疾病或风险等级。你可以回看自己的回答，记录需要的支持。这不是临床诊断。",
                  ChatService.now());
          store.put("assessments", r.id(), r);
          var who = TenantContext.require();
          store.put(
              "survey-responses",
              r.id(),
              new TenantResponse(r.id(), who.userId(), who.username(), r));
          return r;
        });
  }

  public List<TenantResponse> responses(String id) {
    TenantContext.requireAdmin();
    draft(id);
    return store.list("survey-responses", TenantResponse.class).stream()
        .filter(r -> r.assessment().surveyId().equals(id))
        .toList()
        .reversed();
  }

  public void seed() {
    var options =
        List.of(
            new Option("never", "从不", 0),
            new Option("sometimes", "偶尔", 1),
            new Option("often", "经常", 2),
            new Option("daily", "几乎每天", 3));
    var titles =
        List.of(
            "最近一周，我感到学习任务让我有些吃力",
            "最近一周，我入睡前会反复想事情",
            "最近一周，我感到情绪低落",
            "最近一周，我在人际交流中感到紧张",
            "最近一周，我觉得难以向别人表达需要");
    List<Question> qs = new ArrayList<>();
    for (int i = 0; i < titles.size(); i++)
      qs.add(new Question("q" + i, titles.get(i), "SINGLE", true, options));
    // Registration owns the transaction; this new tenant is not visible yet.
    // Do not acquire the editor monitor while holding the only SQLite connection.
    TenantContext.requireAdmin();
    String id = UUID.randomUUID().toString(), now = ChatService.now();
    var d =
        new SurveyDraft(
            id,
            "本周的心情温度",
            "自编状态记录，用于体验填写流程，不是经过验证的心理量表。",
            List.copyOf(qs),
            2,
            1,
            2,
            "PUBLISHED",
            now);
    store.put("survey-drafts", id, d);
    store.put(
        "survey-versions",
        id + ":1",
        new SurveySnapshot(id + ":1", id, d.title(), d.description(), 1, d.questions(), now));
  }
}
