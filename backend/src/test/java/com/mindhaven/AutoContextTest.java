package com.mindhaven;

import com.mindhaven.config.ContextSettings;
import com.mindhaven.config.Settings;
import com.mindhaven.integration.ai.AiGateway;
import com.mindhaven.integration.ai.PromptRepository;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.chat.Summary;
import com.mindhaven.service.ai.AiOperations;
import com.mindhaven.service.chat.ContextPlanner;
import com.mindhaven.service.chat.ContextRenderer;
import com.mindhaven.service.chat.ConversationContextService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoContextTest {
    private final Settings settings = mock(Settings.class);
    private final PromptRepository prompts = name -> new PromptRepository.Template(name, "rules", "hash");
    private final ContextPlanner planner = new ContextPlanner(prompts, new ContextRenderer());
    private final AiOperations ai = mock(AiOperations.class);
    private final RecordManager records = mock(RecordManager.class);

    private ConversationContextService service(boolean enabled, int window) {
        when(settings.contextBudget()).thenReturn(window);
        when(settings.outputBudget()).thenReturn(100);
        when(settings.knowledgeBudget()).thenReturn(600);
        return new ConversationContextService(new ContextSettings(enabled, .8, 3, 4, 1500), settings, planner, prompts, ai, records);
    }

    private List<ChatMessage> history(int rounds, int length) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 1; i <= rounds * 2; i++) {
            messages.add(new ChatMessage("m" + i, "s", i, i % 2 == 1 ? "user" : "assistant", "message-" + i + "-" + "a".repeat(length), "now", List.of(), "complete"));
        }
        return messages;
    }

    @Test
    void belowThresholdKeepsAllHistoryWithoutCallingSummary() {
        var service = service(true, 5000);
        var result = service.prepare("s", history(7, 100), null, "question", List.of());
        assertThat(result.summary()).isNull();
        assertThat(result.plan().messages()).hasSize(16);
        verifyNoInteractions(ai, records);
    }

    @Test
    void exactThresholdTriggersAndKeepsExactlyThreeRecentTurns() {
        var history = history(7, 100);
        service(true, 5000);
        int size = planner.candidate(history, null, "question", List.of(), settings).estimate();
        // Pick a window whose 80% threshold is exactly the candidate estimate.
        int window = (int) Math.ceil(size / .8);
        var service = service(true, window);
        when(ai.complete(eq("summary"), anyList(), eq(500))).thenReturn(new AiGateway.Result("compressed facts", null, null));
        var result = service.prepare("s", history, null, "question", List.of());
        assertThat(result.summary().coveredThroughSeq()).isEqualTo(8);
        assertThat(result.plan().messages()).hasSize(9);
        assertThat(result.plan().messages().subList(2, 8)).extracting(m -> m.getText()).containsExactlyElementsOf(history.subList(8, 14).stream().map(ChatMessage::content).toList());
        verify(records).put("summaries", "s", result.summary());
    }

    @Test
    void incrementalCompressionDoesNotRepeatCoveredMessages() {
        var previous = new Summary("s", 4, 4, "previous facts", "now");
        var service = service(true, 1900);
        when(ai.complete(eq("summary"), anyList(), eq(500))).thenAnswer(call -> {
            List<Message> messages = call.getArgument(1);
            assertThat(messages.getLast().getText()).contains("previous facts", "message-5-").doesNotContain("message-1-", "message-2-");
            return new AiGateway.Result("updated facts", null, null);
        });
        var result = service.prepare("s", history(9, 100), previous, "question", List.of());
        assertThat(result.summary().version()).isGreaterThan(4);
        assertThat(result.summary().coveredThroughSeq()).isEqualTo(12);
    }

    @Test
    void failedSummaryLeavesCoverageUnchanged() {
        var service = service(true, 1800);
        when(ai.complete(eq("summary"), anyList(), eq(500))).thenReturn(new AiGateway.Result("", null, null));
        assertThatThrownBy(() -> service.prepare("s", history(7, 100), null, "question", List.of())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(records);
    }

    @Test
    void doesNotSilentlyDropRecentTurnsWhenTheyCannotFit() {
        var service = service(true, 1800);
        assertThatThrownBy(() -> service.prepare("s", history(3, 400), null, "question", List.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ai, records);
    }

    @Test
    void disabledExperimentDoesNotUseSummaryOrSilentlyTrimHistory() {
        var service = service(false, 1800);
        var previous = new Summary("s", 1, 8, "old summary", "now");
        assertThatThrownBy(() -> service.prepare("s", history(7, 100), previous, "question", List.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ai, records);
    }

    @Test
    void laterBatchFailureDoesNotPersistPartialSummary() {
        var service = service(true, 1800);
        when(ai.complete(eq("summary"), anyList(), eq(500))).thenReturn(new AiGateway.Result("first batch summary", null, null)).thenThrow(new IllegalStateException("provider failed"));
        assertThatThrownBy(() -> service.prepare("s", history(12, 100), null, "question", List.of())).hasMessage("provider failed");
        verify(ai, times(2)).complete(eq("summary"), anyList(), eq(500));
        verifyNoInteractions(records);
    }

    @Test
    void invalidOrOversizedInputIsRejectedBeforeContextPreparation() {
        var service = service(true, 5000);
        assertThatThrownBy(() -> service.validateInput(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateInput("a".repeat(1501))).isInstanceOf(IllegalArgumentException.class);
        service.validateInput("a".repeat(1500));
        verifyNoInteractions(ai, records);
    }
}
