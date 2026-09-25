package com.mindhaven.config;

import com.mindhaven.application.chat.ContextPlanner;
import com.mindhaven.domain.port.AiGateway;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
public class AiConfiguration {
  @Bean
  @ConditionalOnProperty(name = "mindhaven.ai-mode", havingValue = "live")
  AiGateway liveGateway(Settings s) {
    if (s.chatKey() == null || s.chatKey().isBlank())
      throw new IllegalArgumentException("Live mode requires DEEPSEEK_API_KEY");
    var api =
        OpenAiApi.builder()
            .baseUrl(s.chatBaseUrl())
            .apiKey(s.chatKey())
            .completionsPath(s.chatPath())
            .build();
    var model =
        OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(
                OpenAiChatOptions.builder().model(s.chatModel()).temperature(0.2).build())
            .build();
    return new AiGateway() {
      private Prompt prompt(List<Message> m, int max) {
        return new Prompt(
            m,
            OpenAiChatOptions.builder()
                .model(s.chatModel())
                .temperature(0.2)
                .maxTokens(max)
                .streamUsage(true)
                .build());
      }

      private Result result(String text, ChatResponse response) {
        var u = response == null ? null : response.getMetadata().getUsage();
        return new Result(
            text,
            u == null ? null : u.getPromptTokens(),
            u == null ? null : u.getCompletionTokens());
      }

      public Result complete(List<Message> m, int max) {
        return stream(m, max, delta -> {});
      }

      public Result stream(List<Message> m, int max, Consumer<String> emit) {
        StringBuilder text = new StringBuilder();
        ChatResponse[] last = {null};
        model.stream(prompt(m, max))
            .timeout(Duration.ofSeconds(45))
            .doOnNext(
                response -> {
                  if (response.getMetadata().getUsage() != null
                      && response.getMetadata().getUsage().getTotalTokens() > 0) last[0] = response;
                  if (response.getResult() != null) {
                    String delta = response.getResult().getOutput().getText();
                    if (delta != null && !delta.isEmpty()) {
                      text.append(delta);
                      emit.accept(delta);
                    }
                  }
                })
            .blockLast(Duration.ofSeconds(90));
        if (text.isEmpty()) throw new IllegalStateException("Model returned no content");
        return result(text.toString(), last[0]);
      }
    };
  }

  @Bean
  @ConditionalOnProperty(name = "mindhaven.ai-mode", havingValue = "demo", matchIfMissing = true)
  AiGateway demoGateway() {
    return new AiGateway() {
      public Result complete(List<Message> m, int max) {
        return stream(m, max, d -> {});
      }

      public Result stream(List<Message> m, int max, Consumer<String> emit) {
        String input = m.get(m.size() - 1).getText();
        String system = m.get(0).getText();
        String text;
        if (system.startsWith("REWRITE"))
          text = input.substring(input.lastIndexOf("当前问题：") + 5).trim();
        else if (system.startsWith("SUMMARY")) text = ContextPlanner.clip(input, 1000);
        else {
          var matcher = java.util.regex.Pattern.compile("\\[来源:([^]]+)\\]").matcher(system);
          if (matcher.find())
            text =
                "谢谢你愿意把这些感受说出来。我们可以先不急着解决所有事情。\n\n"
                    + "试着从一件具体的事开始：此刻最让你挂心的是什么？你也可以把它写下来，再想想今天能做的一个小动作。["
                    + matcher.group(1)
                    + "]\n\n这是本地演示回复，用于验证聊天与引用流程，不是模型生成的个性化建议。";
          else text = "谢谢你愿意分享。当前知识库没有找到足够相关的材料，我不会据此作出判断。你可以补充具体的情境，或找可信任的人聊一聊。\n\n这是本地演示回复。";
        }
        text = ContextPlanner.clip(text, max * 2);
        for (int i = 0; i < text.length(); i += 10)
          emit.accept(text.substring(i, Math.min(i + 10, text.length())));
        return new Result(text, null, null);
      }
    };
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "mindhaven.vector-mode", havingValue = "qdrant")
  QdrantClient qdrant(Settings s) {
    return new QdrantClient(
        QdrantGrpcClient.newBuilder(s.qdrantHost(), s.qdrantPort(), false).build());
  }

  @Bean
  @ConditionalOnProperty(name = "mindhaven.vector-mode", havingValue = "qdrant")
  VectorStore vectorStore(Settings s, QdrantClient client) {
    var api =
        OpenAiApi.builder()
            .baseUrl(s.embeddingBaseUrl())
            .apiKey(s.embeddingKey())
            .embeddingsPath(s.embeddingPath())
            .build();
    var embeddings =
        new OpenAiEmbeddingModel(
            api,
            org.springframework.ai.document.MetadataMode.EMBED,
            OpenAiEmbeddingOptions.builder().model(s.embeddingModel()).build());
    return QdrantVectorStore.builder(client, embeddings)
        .collectionName(s.qdrantCollection())
        .initializeSchema(true)
        .build();
  }
}
