package com.mindhaven.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mindhaven.common.Hashes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** One execution owns this state; never shared across tasks or identities. */
final class AgentTaskProgress {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, AgentToolExecutor.Result> cache = new HashMap<>();
    private final Set<String> outcomes = new HashSet<>();
    private final Set<String> resourceIds = new HashSet<>();
    private final Map<String, JsonNode> pages = new LinkedHashMap<>();
    private int stagnant;

    String key(String name, String arguments) {
        try { return name + ":" + json.writeValueAsString(canonical(json.readTree(arguments))); }
        catch (Exception e) { return name + ":" + arguments; }
    }

    private JsonNode canonical(JsonNode node) {
        if (node == null) return json.nullNode();
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(e -> fields.put(e.getKey(), canonical(e.getValue())));
            ObjectNode sorted = json.createObjectNode();
            fields.forEach(sorted::set);
            return sorted;
        }
        if (node.isArray()) {
            var array = json.createArrayNode();
            node.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return node;
    }

    AgentToolExecutor.Result cached(String key) { return cache.get(key); }
    String cachedPayload(AgentToolExecutor.Result result) {
        try {
            var payload = json.readTree(result.payload());
            if (payload instanceof ObjectNode object) {
                object.put("source", "task_cache");
                object.put("notice", "这是本任务已经查询的结果，未执行新查询。请勿重复查询相同参数。");
                return json.writeValueAsString(object);
            }
        } catch (JsonProcessingException ignored) { }
        return result.payload();
    }

    boolean stalled() { return stagnant >= 3; }
    void repeated() { stagnant++; }
    void failed(String code) { stagnant++; }

    void confirmed(String key, AgentToolExecutor.Result result) {
        cache.put(key, result);
        boolean changed = false;
        for (var item : result.recommendations()) changed |= resourceIds.add(item.kind() + ":" + item.id());
        for (var item : result.citations()) changed |= resourceIds.add("knowledge:" + item.id());
        try {
            JsonNode payload = json.readTree(result.payload());
            // Keep actual query results and cursors separate from the compact model view.
            if (payload != null && payload.has("nextOffset")) pages.put(key, payload);
            // A first empty result is useful evidence. Rephrased identical results are not progress.
            JsonNode evidence = payload;
            if (payload != null && payload.isObject()) {
                var stable = ((ObjectNode) payload).deepCopy();
                stable.remove(List.of("offset", "nextOffset", "limit", "source", "order", "resultRef"));
                evidence = stable;
            }
            changed |= outcomes.add(Hashes.sha256(json.writeValueAsString(canonical(evidence))));
        } catch (JsonProcessingException e) { changed |= outcomes.add(Hashes.sha256(result.payload())); }
        stagnant = changed ? 0 : stagnant + 1;
    }

    record Cached(String key, AgentToolExecutor.Result result) { }
    List<Cached> cacheSnapshot() {
        return cache.entrySet().stream().map(e -> new Cached(e.getKey(), e.getValue())).toList();
    }
    void restore(List<Cached> entries) {
        if (entries != null) entries.forEach(e -> confirmed(e.key(), e.result()));
    }

    Map<String, Object> snapshot() {
        return Map.of("confirmedResourceIds", Set.copyOf(resourceIds), "queries", Map.copyOf(pages),
                "consecutiveNoProgress", stagnant);
    }
}
