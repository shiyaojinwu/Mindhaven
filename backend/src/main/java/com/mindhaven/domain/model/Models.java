package com.mindhaven.domain.model;

import java.util.List;

public final class Models {
  private Models() {}

  public record Session(String id, String title, String createdAt) {}

  public record Citation(
      String id,
      String title,
      String topic,
      String version,
      String sourceUrl,
      String text,
      double score) {}

  public record Message(
      String id,
      String sessionId,
      long seq,
      String role,
      String content,
      String createdAt,
      List<Citation> citations,
      String status) {}

  public record Summary(
      String id, int version, long coveredThroughSeq, String content, String updatedAt) {}

  public record Knowledge(
      String id, String title, String topic, String version, String sourceUrl, String text) {}

  public record Course(
      String id,
      String title,
      String category,
      int minutes,
      String intro,
      String content,
      String videoId) {
    public Course(
        String id, String title, String category, int minutes, String intro, String content) {
      this(id, title, category, minutes, intro, content, null);
    }
  }

  public record Video(
      String id,
      String filename,
      String contentType,
      long size,
      String createdAt,
      String storage,
      String bucket,
      String objectKey,
      String location) {}

  public record Progress(String id, String completedAt) {}

  public record Survey(String id, String title, String description, List<String> questions) {}

  public record Option(String id, String label, int score) {}

  public record Question(
      String id, String title, String type, boolean required, List<Option> options) {}

  public record SurveyDraft(
      String id,
      String title,
      String description,
      List<Question> questions,
      int revision,
      int publishedVersion,
      int publishedRevision,
      String status,
      String updatedAt) {}

  public record SurveySnapshot(
      String id,
      String surveyId,
      String title,
      String description,
      int version,
      List<Question> questions,
      String publishedAt) {}

  public record AnswerInput(String questionId, List<String> optionIds, String text) {}

  public record AnswerSnapshot(
      String questionId,
      String title,
      String type,
      List<String> selectedLabels,
      String text,
      int score) {}

  public record Assessment(
      String id,
      String surveyId,
      int surveyVersion,
      String surveyTitle,
      List<AnswerSnapshot> answers,
      int score,
      int maxScore,
      String label,
      String report,
      String createdAt) {}

  public record TenantResponse(String id, String userId, String username, Assessment assessment) {}

  public record ReportAnalysis(String id, String mode, String content, String createdAt) {}

  public record Post(String id, String content, String mood, int hugs, String createdAt) {}

  public record Metrics(
      String id,
      String sessionId,
      String mode,
      String model,
      String promptVersion,
      String knowledgeVersion,
      String rewrittenQuery,
      int retrievedCount,
      List<String> retrievedIds,
      int contextEstimate,
      Integer promptTokens,
      Integer completionTokens,
      long firstTokenMs,
      long totalMs,
      int summaryVersion,
      long coveredThroughSeq,
      boolean citationIdsValid,
      String createdAt) {}
}
