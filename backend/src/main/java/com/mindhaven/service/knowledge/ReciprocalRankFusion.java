package com.mindhaven.service.knowledge;

import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.model.knowledge.RetrievalResult;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ReciprocalRankFusion {
    private Map<String, Integer> ranks(List<Citation> results) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (var c : results) ranks.putIfAbsent(c.id(), ranks.size() + 1);
        return ranks;
    }

    public RetrievalResult fuse(List<Citation> dense, List<Citation> lexical, int k, int limit) {
        if (k < 1 || limit < 1) throw new IllegalArgumentException("Invalid RRF limits");
        var vectorRanks = ranks(dense);
        var lexicalRanks = ranks(lexical);
        Map<String, Citation> docs = new LinkedHashMap<>();
        Map<String, Double> vectorScores = new HashMap<>(), lexicalScores = new HashMap<>();
        dense.forEach(c -> {
            docs.putIfAbsent(c.id(), c);
            vectorScores.putIfAbsent(c.id(), c.score());
        });
        lexical.forEach(c -> {
            docs.putIfAbsent(c.id(), c);
            lexicalScores.putIfAbsent(c.id(), c.score());
        });
        List<RetrievalResult.Match> matches = docs.keySet().stream().map(id -> {
            Integer v = vectorRanks.get(id), l = lexicalRanks.get(id);
            double score = (v == null ? 0 : 1.0 / (k + v)) + (l == null ? 0 : 1.0 / (k + l));
            return new RetrievalResult.Match(id, v, l, vectorScores.get(id), lexicalScores.get(id), score);
        }).sorted(Comparator.comparingDouble((RetrievalResult.Match m) -> m.rrfScore()).reversed().thenComparing(RetrievalResult.Match::chunkId)).limit(limit).toList();
        var citations = matches.stream().map(m -> {
            var c = docs.get(m.chunkId());
            return new Citation(c.id(), c.title(), c.topic(), c.version(), c.sourceUrl(), c.text(), m.rrfScore());
        }).toList();
        return new RetrievalResult("hybrid-rrf", citations, matches);
    }
}
