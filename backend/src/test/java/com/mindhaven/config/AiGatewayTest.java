package com.mindhaven.config;

import static org.assertj.core.api.Assertions.*;

import com.mindhaven.domain.port.AiGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Exercises the real Spring AI adapter against a local OpenAI-compatible SSE fixture, not DeepSeek.
 */
class AiGatewayTest {
  @Test
  void liveAdapterStreamsAndRetainsUsage() throws Exception {
    var request = new AtomicReference<String>();
    var auth = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          String sse =
              "data:"
                  + " {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"你好\"},\"finish_reason\":null}]}\n\n"
                  + "data:"
                  + " {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\",\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":2,\"total_tokens\":14}}\n\n"
                  + "data: [DONE]\n\n";
          byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var settings =
          new Settings(
              "live",
              "local",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "/chat/completions",
              "test",
              "test-only-key",
              "",
              "",
              "",
              "",
              "localhost",
              6334,
              "test",
              12000,
              1400,
              4500,
              3600);
      AiGateway gateway = new AiConfiguration().liveGateway(settings);
      StringBuilder out = new StringBuilder();
      Thread caller = Thread.currentThread();
      var result =
          gateway.stream(
              List.of(new UserMessage("你好")),
              100,
              delta -> {
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
}
