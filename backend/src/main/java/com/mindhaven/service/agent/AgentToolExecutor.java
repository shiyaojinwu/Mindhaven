package com.mindhaven.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.config.AgentSettings;
import com.mindhaven.model.ai.AgentTool;
import com.mindhaven.model.ai.Recommendation;
import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.model.knowledge.RetrievalResult;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.ai.RunContext;
import com.mindhaven.service.chat.ContextPlanner;
import com.mindhaven.service.course.LearningService;
import com.mindhaven.service.knowledge.KnowledgeService;
import com.mindhaven.service.questionnaire.QuestionnaireService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;

@Service
public class AgentToolExecutor {
    private static final String QUERY_SCHEMA = """
            {"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":200,"description":"结合历史补全后的独立知识问题，不可为空"}},"required":["query"],"additionalProperties":false}
            """;
    private static final String PAGE_SCHEMA = """
            {"type":"object","properties":{
              "query":{"type":"string","maxLength":200,"default":"","description":"单个主题关键词；省略或空字符串表示列出。不要把多个关键词拼成一句话"},
              "offset":{"type":"integer","minimum":0,"maximum":1000000,"default":0,"description":"从0开始的位置，下一页使用上次返回的nextOffset"},
              "limit":{"type":"integer","minimum":1,"maximum":30,"default":5,"description":"一次最多30项，默认5。用户要前30项时传offset=0、limit=30，一次调用即可，无需拆成6次"}},
             "additionalProperties":false}
            """;
    private static final List<AgentTool> TOOLS = List.of(
            new AgentTool("searchKnowledge", "查询当前机构已选主题和版本的知识，返回可引用documents。只读。", QUERY_SCHEMA),
            new AgentTool("findCourses", "分页查询当前机构已发布课程并生成可点击卡片。{}列出第一页。返回total、returned、hasMore、nextOffset；total是匹配总数，不得把一页称为全部。不支持按上线时间排序。", PAGE_SCHEMA),
            new AgentTool("listAvailableSurveys", "分页查询当前机构已发布问卷并生成可填写卡片。{}列出第一页。返回total、returned、hasMore、nextOffset。只读，不读取答卷、不提交问卷。", PAGE_SCHEMA));
    private final KnowledgeService knowledge;
    private final LearningService learning;
    private final QuestionnaireService questionnaires;
    private final ObjectMapper json;
    private final AgentSettings settings;

    public record Result(String payload, List<Citation> citations, List<Recommendation> recommendations, RetrievalResult retrieval) { }
    private record Payload(String status, List<Citation> documents, List<Recommendation> items) { }

    public AgentToolExecutor(KnowledgeService knowledge, LearningService learning, QuestionnaireService questionnaires,
                             ObjectMapper json, AgentSettings settings) {
        this.knowledge = knowledge;
        this.learning = learning;
        this.questionnaires = questionnaires;
        this.json = json;
        this.settings = settings;
    }

    public List<AgentTool> definitions() { return TOOLS; }

    public Result execute(String name, String arguments, String topic, String version) {
        TenantContext.require(); // Identity always comes from the authenticated worker context.
        RunContext.check();
        if (TOOLS.stream().noneMatch(t -> t.name().equals(name))) throw new IllegalArgumentException("不允许调用该工具");
        JsonNode args;
        try {
            if (arguments == null || arguments.length() > 2048) throw new ToolArgumentException("参数不能为空且不得超过2048字符");
            args = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(arguments);
        } catch (JsonProcessingException e) {
            throw new ToolArgumentException("参数必须是合法 JSON 对象");
        }
        if (args == null || !args.isObject()) throw new ToolArgumentException("参数必须是 JSON 对象");
        boolean knowledgeQuery = name.equals("searchKnowledge");
        var fields = args.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!(knowledgeQuery ? List.of("query") : List.of("query", "offset", "limit")).contains(field))
                throw new ToolArgumentException("不允许该参数字段；身份与检索范围由服务端确定");
        }
        if (args.has("query") && !args.get("query").isTextual()) throw new ToolArgumentException("query 必须是字符串");
        String query = args.path("query").asText("").strip();
        if (query.length() > 200 || (knowledgeQuery && query.isEmpty()))
            throw new ToolArgumentException("query 不得超过200字符，知识检索的 query 不得为空");
        int offset = integer(args, "offset", 0, 0, 1000000);
        int limit = integer(args, "limit", 5, 1, 30);
        List<Citation> docs = new ArrayList<>();
        List<Recommendation> items = new ArrayList<>();
        RetrievalResult retrieval = null;
        switch (name) {
            case "searchKnowledge" -> {
                retrieval = knowledge.retrieve(query, topic, version, 4);
                for (Citation doc : retrieval.citations()) {
                    // The saved citation is exactly the clipped text exposed to the model.
                    var candidate = new Citation(doc.id(), ContextPlanner.clip(doc.title(), 160), doc.topic(), doc.version(),
                            doc.sourceUrl(), ContextPlanner.clip(doc.text(), 700), doc.score());
                    docs.add(candidate);
                    if (!fits(docs, items)) { docs.removeLast(); break; }
                }
            }
            case "findCourses" -> learning.courses().stream()
                    .filter(c -> matches(query, c.title() + " " + c.category() + " " + c.intro()))
                    .sorted(Comparator.comparing(c -> c.id()))
                    .forEach(c -> items.add(new Recommendation("course", c.id(), ContextPlanner.clip(c.title(), 160), ContextPlanner.clip(c.intro(), 240))));
            case "listAvailableSurveys" -> questionnaires.published().stream()
                    .filter(s -> matches(query, s.title() + " " + s.description()))
                    .sorted(Comparator.comparing(s -> s.id()))
                    .forEach(s -> items.add(new Recommendation("survey", s.id(), ContextPlanner.clip(s.title(), 160), ContextPlanner.clip(s.description(), 240))));
            default -> throw new IllegalArgumentException("不允许调用该工具");
        }
        if (!knowledgeQuery) {
            int total = items.size();
            var pageItems = new ArrayList<>(items.subList(Math.min(offset, total), (int) Math.min((long) offset + limit, total)));
            while (!pageItems.isEmpty() && ContextPlanner.estimate(page(pageItems, total, offset, limit)) > resourceBudget(limit)) pageItems.removeLast();
            if (pageItems.isEmpty() && offset < total) throw new IllegalArgumentException("工具结果容量不足以返回一项资源");
            RunContext.check();
            return new Result(page(pageItems, total, offset, limit), List.of(), List.copyOf(pageItems), null);
        }
        RunContext.check();
        return new Result(encode(docs, items), List.copyOf(docs), List.copyOf(items), retrieval);
    }

    private int integer(JsonNode args, String name, int fallback, int min, int max) {
        if (!args.has(name)) return fallback;
        var value = args.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw new ToolArgumentException(name + " 必须是范围内的整数");
        return value.intValue();
    }

    private int resourceBudget(int limit) {
        return Math.min(14400, settings.toolResultBudget() * ((limit + 4) / 5));
    }

    private String page(List<Recommendation> items, int total, int offset, int limit) {
        var result = json.createObjectNode().put("status", "ok").put("total", total)
                .put("offset", offset).put("limit", limit).put("returned", items.size())
                .put("hasMore", (long) offset + items.size() < total)
                .put("source", "current_query").put("order", "id_asc");
        if ((long) offset + items.size() < total) result.put("nextOffset", offset + items.size());
        else result.putNull("nextOffset");
        if (limit > 5) {
            var compact = result.putArray("items");
            items.forEach(item -> compact.addObject().put("kind", item.kind()).put("id", item.id()).put("title", item.title()));
            result.put("detailLevel", "titles");
        } else result.set("items", json.valueToTree(items));
        return result.toString();
    }

    private boolean matches(String query, String text) {
        return query.isBlank() || text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private boolean fits(List<Citation> docs, List<Recommendation> items) {
        return ContextPlanner.estimate(encode(docs, items)) <= settings.toolResultBudget();
    }

    private String encode(List<Citation> docs, List<Recommendation> items) {
        try { return json.writeValueAsString(new Payload("ok", docs, items)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode tool result", e); }
    }
}
