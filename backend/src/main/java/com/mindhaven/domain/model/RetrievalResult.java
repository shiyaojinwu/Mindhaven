package com.mindhaven.domain.model;

import com.mindhaven.domain.model.Models.Citation;
import java.util.List;

public record RetrievalResult(
    String mode, List<Citation> citations, List<Match> matches, Configuration configuration) {
  public RetrievalResult(String mode, List<Citation> citations, List<Match> matches) {
    this(mode, citations, matches, null);
  }

  public record Configuration(int candidateLimit, int rrfK, double vectorThreshold) {}

  public record Match(
      String chunkId,
      Integer vectorRank,
      Integer lexicalRank,
      Double vectorScore,
      Double lexicalScore,
      Double rrfScore) {}
}
