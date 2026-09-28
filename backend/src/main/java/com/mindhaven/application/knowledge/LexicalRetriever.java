package com.mindhaven.application.knowledge;

import com.mindhaven.domain.model.Models.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Small-corpus BM25: Chinese character bigrams and Latin words/identifiers; no extra service. */
@Component
public class LexicalRetriever {
  private static final double K1 = 1.2, B = .75;
  private static final Pattern TOKENS = Pattern.compile("\\p{IsHan}+|[a-z0-9]+(?:[-_.][a-z0-9]+)*");
  private static final Set<String> STOP =
      Set.of(
          "什么", "怎么", "如何", "可以", "这个", "那个", "一下", "是什", "为什", "些什", "么办", "情况", "这种", "那我", "先做",
          "请问", "谢谢", "这是", "测试", "联调");

  private Map<String, Integer> terms(String text) {
    Map<String, Integer> result = new HashMap<>();
    var matcher =
        TOKENS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      String token = matcher.group();
      int[] points = token.codePoints().toArray();
      if (Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN) {
        for (int i = 0; i < points.length - 1; i++) {
          String bigram = new String(points, i, 2);
          if (!STOP.contains(bigram)) result.merge(bigram, 1, Integer::sum);
        }
      } else result.merge(token, 1, Integer::sum);
    }
    return result;
  }

  public List<Citation> search(String query, List<Knowledge> corpus, int limit) {
    if (corpus.isEmpty()) return List.of();
    var queryTerms = terms(query).keySet();
    if (queryTerms.isEmpty()) return List.of();
    List<Map<String, Integer>> documents =
        corpus.stream()
            .map(k -> terms(k.id() + " " + k.title() + " " + k.topic() + " " + k.text()))
            .toList();
    double average =
        documents.stream()
            .mapToInt(d -> d.values().stream().mapToInt(Integer::intValue).sum())
            .average()
            .orElse(1);
    Map<String, Long> frequencies = new HashMap<>();
    for (String term : queryTerms)
      frequencies.put(term, documents.stream().filter(d -> d.containsKey(term)).count());
    List<Citation> results = new ArrayList<>();
    for (int i = 0; i < corpus.size(); i++) {
      var doc = documents.get(i);
      int length = doc.values().stream().mapToInt(Integer::intValue).sum();
      double score = 0;
      for (String term : queryTerms) {
        int tf = doc.getOrDefault(term, 0);
        if (tf == 0) continue;
        long df = frequencies.get(term);
        double idf = Math.log(1 + (corpus.size() - df + .5) / (df + .5));
        score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * length / Math.max(1, average)));
      }
      if (score > 0) results.add(KnowledgeService.citation(corpus.get(i), score));
    }
    return results.stream()
        .sorted(Comparator.comparingDouble(Citation::score).reversed().thenComparing(Citation::id))
        .limit(limit)
        .toList();
  }
}
