package com.mindhaven;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mindhaven.application.ai.AiOperations;
import com.mindhaven.config.Settings;
import com.mindhaven.domain.model.AiUsage;
import com.mindhaven.domain.port.*;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;

class AiOperationsTest {
  AiGateway gateway = mock(AiGateway.class);
  UsageRepository usage = mock(UsageRepository.class);
  AiOperations ai;

  @BeforeEach
  void setup() {
    var settings = mock(Settings.class);
    when(settings.aiMode()).thenReturn("live");
    when(settings.contextBudget()).thenReturn(12000);
    when(settings.chatModel()).thenReturn("test-model");
    var prompts = mock(PromptRepository.class);
    when(prompts.get("answer"))
        .thenReturn(new PromptRepository.Template("answer", "Test prompt", "hash"));
    ai = new AiOperations(gateway, usage, prompts, settings);
  }

  @Test
  void missingProviderUsageStaysUnknownWithoutUnboxingNull() {
    when(gateway.stream(anyList(), eq(100), any()))
        .thenReturn(new AiGateway.Result("你好", null, null));
    assertThat(ai.complete("answer", List.of(new UserMessage("你好")), 100).text()).isEqualTo("你好");
    var saved = ArgumentCaptor.forClass(AiUsage.class);
    verify(usage).save(saved.capture());
    assertThat(saved.getValue().inputTokens()).isNull();
    assertThat(saved.getValue().outputTokens()).isNull();
    assertThat(saved.getValue().source()).isEqualTo("estimated");
    assertThat(saved.getValue().status()).isEqualTo("COMPLETED");
  }

  @Test
  void providerFailureIsPreservedAndPartialUsageIsRecorded() {
    var failure = new IllegalStateException("Synthetic provider failure");
    when(gateway.stream(anyList(), eq(100), any()))
        .thenAnswer(
            call -> {
              Consumer<String> emit = call.getArgument(2);
              emit.accept("部分回复");
              throw failure;
            });
    assertThatThrownBy(() -> ai.complete("answer", List.of(new UserMessage("你好")), 100))
        .isSameAs(failure);
    var saved = ArgumentCaptor.forClass(AiUsage.class);
    verify(usage).save(saved.capture());
    assertThat(saved.getValue().inputTokens()).isNull();
    assertThat(saved.getValue().outputTokens()).isNull();
    assertThat(saved.getValue().estimatedOutput()).isGreaterThan(12);
    assertThat(saved.getValue().status()).isEqualTo("FAILED");
  }
}
