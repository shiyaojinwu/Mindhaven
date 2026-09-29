package com.mindhaven.config;

import com.mindhaven.integration.ai.AiGateway;
import com.mindhaven.model.ai.AgentTool;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.Message;
import java.util.ArrayList;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Exercises the real Spring AI adapter against a local OpenAI-compatible SSE fixture, not DeepSeek.
 */
class AiGatewayTest {
    @Test
    void liveAdapterStreamsAndRetainsUsage() throws Exception {
        var request = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String sse = "data:" + " {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"你好\"},\"finish_reason\":null}]}\n\n" + "data:" + " {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\",\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":2,\"total_tokens\":14}}\n\n" + "data: [DONE]\n\n";
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var settings = new Settings("live", "local", "http://127.0.0.1:" + server.getAddress().getPort(), "/chat/completions", "test", "test-only-key", "", "", "", "", "localhost", 6334, "test", 12000, 1400, 3600);
            AiGateway gateway = new AiConfiguration().liveGateway(settings);
            StringBuilder out = new StringBuilder();
            Thread caller = Thread.currentThread();
            var result = gateway.stream(List.of(new UserMessage("你好")), 100, delta -> {
                assertThat(Thread.currentThread()).isSameAs(caller);
                out.append(delta);
            });
            assertThat(out.toString()).isEqualTo("你好");
            assertThat(result.promptTokens()).isEqualTo(12);
            assertThat(result.completionTokens()).isEqualTo(2);
            assertThat(request.get()).contains("\"max_tokens\":100");
            assertThat(auth.get()).isEqualTo("Bearer test-only-key");
        } finally {
            server.stop(0);
        }
    }
    @Test
    void nativeToolsStreamWithoutInternalExecution() throws Exception {
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = requests.size() == 1 ? """
                    {"id":"t","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"findCourses","arguments":"{\\\"query\\\":\\\"睡眠\\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":20,"completion_tokens":10,"total_tokens":30}}
                    """ : """
                    {"id":"t","choices":[{"index":0,"message":{"role":"assistant","content":"找到课程"},"finish_reason":"stop"}]}
                    """;
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            var choice = (com.fasterxml.jackson.databind.node.ObjectNode) tree.path("choices").get(0);
            var message = choice.remove("message");
            if (message.path("tool_calls").isArray()) ((com.fasterxml.jackson.databind.node.ObjectNode) message.path("tool_calls").get(0)).put("index", 0);
            choice.set("delta", message);
            byte[] bytes = ("data: " + tree + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var settings = new Settings("live", "local", "http://127.0.0.1:" + server.getAddress().getPort(), "/chat/completions", "test", "fixture", "", "", "", "", "localhost", 6334, "test", 12000, 1400, 3600);
            var gateway = new AiConfiguration().liveGateway(settings);
            var messages = new ArrayList<Message>(List.of(new UserMessage("找课程")));
            var step = gateway.step(messages, 100, List.of(new AgentTool("findCourses", "find courses", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}")));
            assertThat(requests).hasSize(1);
            assertThat(requests.getFirst()).contains("\"tool_choice\":\"auto\"");
            assertThat(requests.getFirst()).contains("\"stream\":true");
            assertThat(step.finishReason()).isEqualTo("tool_calls");
            assertThat(step.message().getToolCalls().getFirst().arguments()).isEqualTo("{\"query\":\"睡眠\"}");
            assertThat(step.promptTokens()).isEqualTo(20);
            messages.add(step.message());
            messages.add(ToolResponseMessage.builder().responses(List.of(new ToolResponse("call-1", "findCourses", "[]"))).build());
            var end = gateway.step(messages, 100, List.of());
            assertThat(end.finishReason()).isEqualTo("stop");
            assertThat(end.promptTokens()).isNull();
            assertThat(end.message().getText()).isEqualTo("找到课程");
            assertThat(requests.get(1)).contains("\"tool_call_id\":\"call-1\"");
        } finally { server.stop(0); }
    }
}
