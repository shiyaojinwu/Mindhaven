package com.mindhaven.config;

import com.mindhaven.integration.ai.AiGateway;
import com.mindhaven.service.chat.ContextPlanner;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Configuration
public class AiConfiguration {
    @Bean
    @ConditionalOnProperty(name = "mindhaven.ai-mode", havingValue = "li ve")
    AiGateway liveGateway(Settings s) {
        if (s.chatKey() == null || s.chatKey().isBlank())
            throw new IllegalArgumentException("Live mode requires DEEPSEEK_API_KEY");
        var api = OpenAiApi.builder().baseUrl(s.chatBaseUrl()).apiKey(s.chatKey()).completionsPath(s.chatPath()).build();
        var model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(OpenAiChatOptions.builder().model(s.chatModel()).temperature(0.2).build()).build();
        return new AiGateway() {
            private Prompt prompt(List<Message> m, int max) {
                return new Prompt(m, OpenAiChatOptions.builder().model(s.chatModel()).temperature(0.2).maxTokens(max).streamUsage(true).build());
            }

            private Result result(String text, ChatResponse response) {
                var u = response == null ? null : response.getMetadata().getUsage();
                return new Result(text, u == null ? null : u.getPromptTokens(), u == null ? null : u.getCompletionTokens());
            }

            public Result complete(List<Message> m, int max) {
                return stream(m, max, delta -> {
                });
            }

            public Result stream(List<Message> m, int max, Consumer<String> emit) {
                StringBuilder text = new StringBuilder();
                ChatResponse[] last = {null};
                // Consume chunks on the invoking worker: application callbacks write tenant-scoped
                // events and must not run on Reactor's HTTP threads with an empty ThreadLocal identity.
                var deadline = Mono.delay(Duration.ofSeconds(90)).then(Mono.error(new TimeoutException("Model call deadline exceeded")));
                try (var responses = model.stream(prompt(m, max)).timeout(Duration.ofSeconds(45)).takeUntilOther(deadline).toStream(1)) {
                    responses.forEachOrdered(response -> {
                        var usage = response.getMetadata().getUsage();
                        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0)
                            last[0] = response;
                        if (response.getResult() != null && response.getResult().getOutput() != null) {
                            String delta = response.getResult().getOutput().getText();
                            if (delta != null && !delta.isEmpty()) {
                                text.append(delta);
                                emit.accept(delta);
                            }
                        }
                    });
                }
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
                return stream(m, max, d -> {
                });
            }

            public Result stream(List<Message> m, int max, Consumer<String> emit) {
                String input = m.getLast().getText();
                String system = m.getFirst().getText();
                String text;
                if (system.startsWith("REWRITE")) text = input.substring(input.lastIndexOf("当前问题：") + 5).trim();
                else if (system.startsWith("SUMMARY")) text = ContextPlanner.clip(input, 1000);
                else if (system.startsWith("REPORT"))
                    text = "这是一段演示解读，用于验证报告生成与保存。你可以回看自己的回答，记录具体情境和希望得到的支持。这不是临床诊断。";
                else {
                    int referenceEnd = input.indexOf("</reference_documents>");
                    String reference = input.startsWith("<reference_documents>\n") && referenceEnd >= 0 ? input.substring(0, referenceEnd) : "";
                    var matcher = Pattern.compile("<document id=\"([A-Za-z0-9-]+)\"").matcher(reference);
                    if (matcher.find())
                        text = "谢谢你愿意把这些感受说出来。我们可以先不急着解决所有事情。\n\n" + "试着从一件具体的事开始：此刻最让你挂心的是什么？你也可以把它写下来，再想想今天能做的一个小动作。[" + matcher.group(1) + "]\n\n这是本地演示回复，用于验证聊天与引用流程，不是模型生成的个性化建议。";
                    else
                        text = "谢谢你愿意分享。当前知识库没有找到足够相关的材料，我不会据此作出判断。你可以补充具体的情境，或找可信任的人聊一聊。\n\n这是本地演示回复。";
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
        return new QdrantClient(QdrantGrpcClient.newBuilder(s.qdrantHost(), s.qdrantPort(), false).build());
    }

    @Bean
    @ConditionalOnProperty(name = "mindhaven.vector-mode", havingValue = "qdrant")
    VectorStore vectorStore(Settings s, QdrantClient client) {
        var api = OpenAiApi.builder().baseUrl(s.embeddingBaseUrl()).apiKey(s.embeddingKey()).embeddingsPath(s.embeddingPath()).build();
        var embeddings = new OpenAiEmbeddingModel(api, MetadataMode.EMBED, OpenAiEmbeddingOptions.builder().model(s.embeddingModel()).build());
        return QdrantVectorStore.builder(client, embeddings).collectionName(s.qdrantCollection()).initializeSchema(true).build();
    }
}
