package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link OpenAiResponsesLlmClient} 桩服务单测 [AC-1]：用 JDK 内置 HttpServer 模拟 OpenAI Responses 端点，离线验证
 * 请求形状（{base}/v1/responses、Bearer 头、model/max_output_tokens/input 角色）、响应映射（output[] 取 type=message
 * 的 content[].text、usage.input_tokens/output_tokens）、思考摘要项（type=reasoning）不进正文与 失败即抛不重试的节奏。
 */
class OpenAiResponsesLlmClientTest {

    private static final String SUCCESS_BODY =
            "{\"id\":\"resp_1\",\"object\":\"response\","
                    + "\"output\":["
                    + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[]},"
                    + "{\"type\":\"message\",\"id\":\"m_1\",\"role\":\"assistant\","
                    + "\"content\":[{\"type\":\"output_text\",\"text\":\"先看主谓一致，再检查时态。\","
                    + "\"annotations\":[]}]}],"
                    + "\"usage\":{\"input_tokens\":21,\"output_tokens\":9,\"total_tokens\":30}}";

    /** usage 缺失/块为空的降级形状：正文空、用量 0，客户端不抛错（上层按空回复降级）。 */
    private static final String EMPTY_OUTPUT_BODY =
            "{\"id\":\"resp_2\",\"output\":[],\"usage\":{}}";

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();

    private final java.util.concurrent.ConcurrentLinkedQueue<Integer> statusQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final java.util.concurrent.ConcurrentLinkedQueue<String> bodyQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 捕获的请求：method + path + 头 + 原始体。 */
    private final List<Map<String, Object>> captured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/responses",
                exchange -> {
                    requestCount.getAndIncrement();
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    Map<String, List<String>> headers =
                            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    headers.putAll(exchange.getRequestHeaders());
                    captured.add(
                            Map.of(
                                    "method",
                                    exchange.getRequestMethod(),
                                    "path",
                                    exchange.getRequestURI().getPath(),
                                    "headers",
                                    headers,
                                    "body",
                                    new String(body, StandardCharsets.UTF_8)));
                    int status = statusQueue.isEmpty() ? 200 : statusQueue.poll();
                    String payload = bodyQueue.isEmpty() ? SUCCESS_BODY : bodyQueue.poll();
                    byte[] response = payload.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    /**
     * [AC-1] 成功映射：POST {base}/v1/responses（baseUrl 填到 /v1 之前的前缀），Bearer 鉴权头，请求体为
     * {model,max_output_tokens,temperature,input:[{role,content}]}（system 在首位），output 只取 message 项的
     * output_text，usage 双 token 落位。
     */
    @Test
    void mapsRequestAndResponseShape_ac1() {
        LlmEndpoint endpoint = endpoint("");
        OpenAiResponsesLlmClient client = new OpenAiResponsesLlmClient(endpoint);

        LlmChatClient.Reply reply = client.ask("你是答疑助手", "这道题为什么错？");

        assertThat(reply.content()).isEqualTo("先看主谓一致，再检查时态。");
        assertThat(reply.promptTokens()).isEqualTo(21);
        assertThat(reply.completionTokens()).isEqualTo(9);

        assertThat(requestCount.get()).isEqualTo(1);
        Map<String, Object> request = captured.get(0);
        assertThat(request.get("method")).isEqualTo("POST");
        assertThat(request.get("path")).isEqualTo("/v1/responses");
        @SuppressWarnings("unchecked")
        Map<String, List<String>> headers = (Map<String, List<String>>) request.get("headers");
        assertThat(headers.get("Authorization")).containsExactly("Bearer sk-test");
        assertThat(headers.get("Content-Type").get(0)).startsWith("application/json");

        Map<String, Object> body = parseBody((String) request.get("body"));
        assertThat(body.get("model")).isEqualTo("gpt-5.1");
        assertThat((int) body.get("max_output_tokens")).isGreaterThanOrEqualTo(2048);
        assertThat((List<?>) body.get("input"))
                .hasSize(2)
                .satisfies(
                        input -> {
                            Map<?, ?> system = (Map<?, ?>) input.get(0);
                            Map<?, ?> user = (Map<?, ?>) input.get(1);
                            assertThat(system.get("role")).isEqualTo("system");
                            assertThat(system.get("content")).isEqualTo("你是答疑助手");
                            assertThat(user.get("role")).isEqualTo("user");
                            assertThat(user.get("content")).isEqualTo("这道题为什么错？");
                        });
    }

    /** [AC-1] 思考摘要项不进正文：output 里的 reasoning 项被跳过，只有 message 项的 output_text 拼为正文。 */
    @Test
    void skipsReasoningItemsInOutput_ac1() {
        bodyQueue.add(
                "{\"output\":["
                        + "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"内部思考摘要\"}]},"
                        + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"正文A\"},"
                        + "{\"type\":\"output_text\",\"text\":\"正文B\"}]}],"
                        + "\"usage\":{\"input_tokens\":5,\"output_tokens\":6}}");
        OpenAiResponsesLlmClient client = new OpenAiResponsesLlmClient(endpoint(""));

        LlmChatClient.Reply reply = client.ask("s", "u");

        assertThat(reply.content()).isEqualTo("正文A正文B");
        assertThat(reply.content()).doesNotContain("内部思考摘要");
    }

    /** [AC-1] 空 output / usage 缺失：正文空、用量 0，客户端不抛错（空回复降级由上层统一处理）。 */
    @Test
    void returnsBlankContentOnEmptyOutput_ac1() {
        bodyQueue.add(EMPTY_OUTPUT_BODY);
        OpenAiResponsesLlmClient client = new OpenAiResponsesLlmClient(endpoint(""));

        LlmChatClient.Reply reply = client.ask("s", "u");

        assertThat(reply.content() == null || reply.content().isBlank()).isTrue();
        assertThat(reply.promptTokens()).isZero();
        assertThat(reply.completionTokens()).isZero();
    }

    /** [AC-1] 非 2xx 即抛不重试：429/500 各一次请求（openai 轨无网关冷却特性，降级统一在上层）。 */
    @Test
    void throwsWithoutRetryOnNon2xx_ac1() {
        statusQueue.add(429);
        bodyQueue.add("{\"error\":{\"message\":\"rate limited\"}}");
        OpenAiResponsesLlmClient client = new OpenAiResponsesLlmClient(endpoint(""));

        assertThatThrownBy(() -> client.ask("s", "u"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("429");
        assertThat(requestCount.get()).isEqualTo(1);

        statusQueue.add(500);
        assertThatThrownBy(() -> client.ask("s", "u"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
        assertThat(requestCount.get()).isEqualTo(2);
    }

    /**
     * 构造指向桩服务的端点参数。
     *
     * @param apiKey 密钥
     * @return 端点参数
     */
    private LlmEndpoint endpoint(String apiKey) {
        return new LlmEndpoint(
                "http://127.0.0.1:" + server.getAddress().getPort(), "sk-test", "gpt-5.1", null);
    }

    private Map<String, Object> parseBody(String raw) {
        return new ObjectMapper().readValue(raw, Map.class);
    }
}
