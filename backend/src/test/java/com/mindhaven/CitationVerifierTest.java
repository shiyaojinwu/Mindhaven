package com.mindhaven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.model.chat.CitationCheck.Status;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.service.chat.CitationVerifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CitationVerifierTest {
    private final CitationVerifier verifier = new CitationVerifier();
    private final List<Citation> sources = List.of(new Citation("sleep-v1", "Sleep", "睡眠", "v1", "", "text", .8), new Citation("stress-v1", "Stress", "压力", "v1", "", "text", .7));

    @Test
    void absentReferencesAreMissingOnlyWhenSourcesWereIncluded() {
        var missing = verifier.verify("没有写引用", sources);
        assertThat(missing.status()).isEqualTo(Status.MISSING);
        assertThat(missing.passed()).isFalse();
        assertThat(verifier.verify("知识不足", List.of()).status()).isEqualTo(Status.NOT_REQUIRED);
    }

    @Test
    void recognizesGroupedRepeatedIdsWithoutCountingThemTwice() {
        var check = verifier.verify("建议[sleep-v1]，还有[sleep-v1，stress-v1]。", sources);
        assertThat(check.status()).isEqualTo(Status.VALID);
        assertThat(check.citedIds()).containsExactly("sleep-v1", "stress-v1");
        assertThat(check.invalidIds()).isEmpty();
    }

    @Test
    void unknownNumberedAndHistoricalReferencesAreInvalid() {
        var check = verifier.verify("依据[sleep-v1]，其他[old-v1][1]", sources);
        assertThat(check.status()).isEqualTo(Status.INVALID);
        assertThat(check.invalidIds()).containsExactly("old-v1", "1");
        assertThat(check.passed()).isFalse();
        assertThat(verifier.verify("[sleep-v1]", List.of()).status()).isEqualTo(Status.INVALID);
    }

    @Test
    void markdownLinksAndEscapedExamplesAreNotSourceReferences() {
        assertThat(verifier.verify("[site](https://example.com) \\[sleep-v1]", sources).status()).isEqualTo(Status.MISSING);
    }

    @Test
    void oldStoredMessagesRemainReadableWithoutFabricatedValidation() throws Exception {
        var mapper = new ObjectMapper();
        var old = mapper.readValue("{\"id\":\"a\",\"sessionId\":\"s\",\"seq\":2,\"role\":\"assistant\",\"content\":\"旧回复\",\"createdAt\":\"now\",\"citations\":[],\"status\":\"complete\"}", ChatMessage.class);
        assertThat(old.citationCheck()).isNull();
        var message = new ChatMessage("b", "s", 4, "assistant", "建议[sleep-v1]", "now", sources, "complete", verifier.verify("建议[sleep-v1]", sources));
        assertThat(mapper.readValue(mapper.writeValueAsString(message), ChatMessage.class)).isEqualTo(message);
    }
}
