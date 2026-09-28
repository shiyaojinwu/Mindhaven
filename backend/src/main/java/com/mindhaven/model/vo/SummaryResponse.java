package com.mindhaven.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mindhaven.model.chat.Summary;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SummaryResponse(String id, Integer version, Long coveredThroughSeq, String content, String updatedAt) {
    public static SummaryResponse from(Summary s) {
        return new SummaryResponse(s.id(), s.version(), s.coveredThroughSeq(), s.content(), s.updatedAt());
    }

    public static SummaryResponse empty() {
        return new SummaryResponse(null, null, null, null, null);
    }
}
