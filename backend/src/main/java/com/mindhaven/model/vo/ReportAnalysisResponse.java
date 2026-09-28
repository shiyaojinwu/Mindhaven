package com.mindhaven.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mindhaven.model.report.ReportAnalysis;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportAnalysisResponse(String id, String mode, String content, String createdAt, String fingerprint) {
    public static ReportAnalysisResponse from(ReportAnalysis r) {
        return new ReportAnalysisResponse(r.id(), r.mode(), r.content(), r.createdAt(), r.fingerprint());
    }

    public static ReportAnalysisResponse empty() {
        return new ReportAnalysisResponse(null, null, null, null, null);
    }
}
