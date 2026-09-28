package com.mindhaven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.model.chat.Summary;
import com.mindhaven.model.report.ReportAnalysis;
import com.mindhaven.model.vo.ReportAnalysisResponse;
import com.mindhaven.model.vo.SummaryResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void absentSummaryAndAnalysisStillSerializeAsEmptyObjects() throws Exception {
        assertThat(json.writeValueAsString(SummaryResponse.empty())).isEqualTo("{}");
        assertThat(json.writeValueAsString(ReportAnalysisResponse.empty())).isEqualTo("{}");
    }

    @Test
    void populatedResponsesKeepExistingFieldNamesAndValues() {
        var summary = new Summary("s", 2, 8, "summary", "2026-09-28");
        var analysis = new ReportAnalysis("r", "demo", "analysis", "2026-09-28", "hash");
        assertThat(json.<JsonNode>valueToTree(SummaryResponse.from(summary))).isEqualTo(json.<JsonNode>valueToTree(summary));
        assertThat(json.<JsonNode>valueToTree(ReportAnalysisResponse.from(analysis))).isEqualTo(json.<JsonNode>valueToTree(analysis));
    }
}
