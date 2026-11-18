package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.errors.AnthropicServiceException;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AnthropicMessagesLlmClient} 桩服务单测 [AC-1]：用 JDK 内置 HttpServer 模拟 Anthropic Messages 端点 （含
 * ZCode 网关的非标准 429 错误体），离线验证请求形状（/v1/messages、model/max_tokens/system/messages、 anthropic-version
 * 头）、响应映射（content[].text 与 usage.input_tokens/output_tokens）、<strong>思考模型 content 块提取（ thinking
 * 块不进正文、只拼 text 块）</strong>与 429/5xx 退避重试一次的节奏 （退避注入 0ms，不真睡 30s）。
 */
class AnthropicMessagesLlmClientTest {

    /** ZCode 网关 429 时的非标准错误体（实测形状）：error 是字符串而非标准 error 对象。 */
    private static final String GATEWAY_429_BODY =
            "{\"error\":\"ZCode 网关：所有账号尝试均失败\",\"attempts\":[{\"account\":\"eokfa899\","
                    + "\"ok\":false,\"error\":\"429 限流，冷却 1 分钟\"}]}";

    private static final String SUCCESS_BODY =
            "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"glm-5.3\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"先看句子的主干，注意时态搭配。\"}],"
                    + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":12,\"output_tokens\":7}}";

    /**
     * 思考模型响应形状（实测）：content 首块为 thinking（内部推理），正文在其后的 text 块；usage 含
     * cache_read_input_tokens。max_tokens 过小时（如 32）只有 thinking 块、stop_reason=max_tokens。
     */
    private static final String THINKING_THEN_TEXT_BODY =
            "{\"id\":\"msg_t\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"glm-5.3-flash\","
                    + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"用户问的是介词搭配，先看名词短语。\","
                    + "\"signature\":\"sig-abc\"},"
                    + "{\"type\":\"text\",\"text\":\"先比较两个介词的搭配对象。\"}],"
                    + "\"stop_reason\":\"end_turn\","
                    + "\"usage\":{\"input_tokens\":20,\"output_tokens\":15,\"cache_read_input_tokens\":3}}";

    /** max_tokens 被 thinking 吃尽的响应形状（实测）：只有 thinking 块、无 text 块，stop_reason=max_tokens。 */
    private static final String THINKING_ONLY_BODY =
            "{\"id\":\"msg_o\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"glm-5.3-flash\","
                    + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"推理未完成……\",\"signature\":\"sig-x\"}],"
                    + "\"stop_reason\":\"max_tokens\","
                    + "\"usage\":{\"input_tokens\":20,\"output_tokens\":32}}";

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();

    /** 按请求序号出队的响应脚本；空队缺省 200。 */
    private final ConcurrentLinkedQueue<Integer> statusQueue = new ConcurrentLinkedQueue<>();

    private final ConcurrentLinkedQueue<String> bodyQueue = new ConcurrentLinkedQueue<>();

    /** 捕获的请求：method + path + 头 + 原始体。 */
    private final List<Map<String, Object>> captured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/messages",
                exchange -> {
                    requestCount.getAndIncrement();
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    // 请求头捕获到大小写不敏感映射：HttpServer 的 Headers 本身大小写不敏感，
                    // 直接 Map.copyOf 会退化为大小写敏感 get，导致断言对大小写形态产生依赖。
                    Map<String, List<String>> headers =
                            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    headers.putAll(exchange.getRequestHeaders());
                    Map<String, Object> record =
                            Map.of(
                                    "method",
                                    exchange.getRequestMethod(),
                                    "path",
                                    exchange.getRequestURI().getPath(),
                                    "headers",
                                    headers,
                                    "body",
                                    new String(body, StandardCharsets.UTF_8));
                    captured.add(record);
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
     * [AC-1] 成功映射：POST {base}/v1/messages，请求体含 model/max_tokens/system/messages，回复与 usage 双 token
     * 正确落位；max_tokens 为三协议同源的 2048（思考模型 thinking 计入 max_tokens，768 档不够）。
     */
    @Test
    void mapsRequestAndResponseShape_ac1() {
        LlmEndpoint endpoint = endpoint("");
        AnthropicMessagesLlmClient client = new AnthropicMessagesLlmClient(endpoint, () -> 0L);

        LlmChatClient.Reply reply = client.ask("你是答疑助手", "这道题为什么错？");

        assertThat(reply.content()).isEqualTo("先看句子的主干，注意时态搭配。");
        assertThat(reply.promptTokens()).isEqualTo(12);
        assertThat(reply.completionTokens()).isEqualTo(7);

        assertThat(requestCount.get()).isEqualTo(1);
        Map<String, Object> request = captured.get(0);
        assertThat(request.get("method")).isEqualTo("POST");
        // 拼接口径：baseUrl 只填网关前缀，/v1/messages 由客户端（SDK）拼。
        assertThat(request.get("path")).isEqualTo("/v1/messages");
        @SuppressWarnings("unchecked")
        Map<String, List<String>> headers = (Map<String, List<String>>) request.get("headers");
        assertThat(headers.get("anthropic-version")).containsExactly("2023-06-01");
        // 网关本机无鉴权：key 为空时不携带 x-api-key（或仅空值，不构成鉴权语义）。
        List<String> apiKeyHeader = headers.get("x-api-key");
        assertThat(apiKeyHeader == null || apiKeyHeader.get(0).isBlank()).isTrue();

        Map<String, Object> body = parseBody((String) request.get("body"));
        assertThat(body.get("model")).isEqualTo("glm-5.3");
        assertThat(body.get("max_tokens")).isEqualTo(LlmChatClient.MAX_OUTPUT_TOKENS);
        assertThat((int) body.get("max_tokens")).isGreaterThanOrEqualTo(2048);
        assertThat(body.get("system")).isEqualTo("你是答疑助手");
        assertThat((List<?>) body.get("messages"))
                .singleElement()
                .satisfies(
                        message -> {
                            Map<?, ?> entry = (Map<?, ?>) message;
                            assertThat(entry.get("role")).isEqualTo("user");
                            assertThat(entry.get("content")).isEqualTo("这道题为什么错？");
                        });
    }

    /**
     * [AC-1] 思考模型 content 提取（桩锁死）：content=[thinking, text] 时只拼 text 块——thinking 内部推理不得 混进答疑正文；usage
     * 双 token 照常映射（cache_read 等额外字段不破坏解析）。若 Spring AI 变更丢掉 text 块， 本用例即红，届时在客户端手写提取兜底（见实现类注释）。
     */
    @Test
    void extractsOnlyTextBlocksFromThinkingModelResponse_ac1() {
        bodyQueue.add(THINKING_THEN_TEXT_BODY);
        AnthropicMessagesLlmClient client = new AnthropicMessagesLlmClient(endpoint(""), () -> 0L);

        LlmChatClient.Reply reply = client.ask("s", "u");

        assertThat(reply.content()).isEqualTo("先比较两个介词的搭配对象。");
        assertThat(reply.content()).doesNotContain("介词搭配，先看名词短语");
        assertThat(reply.promptTokens()).isEqualTo(20);
        assertThat(reply.completionTokens()).isEqualTo(15);
    }

    /** [AC-1] max_tokens 被 thinking 吃尽（实测形状）：无 text 块 → 正文为空（上层按「空回复降级」统一处理）， 客户端本身不抛错、不重试。 */
    @Test
    void returnsBlankContentWhenThinkingExhaustsTokens_ac1() {
        bodyQueue.add(THINKING_ONLY_BODY);
        AnthropicMessagesLlmClient client = new AnthropicMessagesLlmClient(endpoint(""), () -> 0L);

        LlmChatClient.Reply reply = client.ask("s", "u");

        assertThat(reply.content() == null || reply.content().isBlank()).isTrue();
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(reply.completionTokens()).isEqualTo(32);
    }

    /** [AC-1] 429 退避重试：第一次 429（网关非标准错误体）→ 退避（0ms 注入）→ 第二次成功，恰好两次请求。 */
    @Test
    void retries429OnceThenSucceeds_ac1() {
        statusQueue.add(429);
        bodyQueue.add(GATEWAY_429_BODY);
        List<Long> backoffs = new ArrayList<>();
        AnthropicMessagesLlmClient client =
                new AnthropicMessagesLlmClient(
                        endpoint(""),
                        () -> {
                            backoffs.add(1L);
                            return 0L;
                        });

        LlmChatClient.Reply reply = client.ask("s", "u");

        assertThat(reply.content()).isNotBlank();
        assertThat(requestCount.get()).isEqualTo(2);
        assertThat(backoffs).hasSize(1);
    }

    /** [AC-1] 连续 429：只重试一次，第二次仍 429 则向上抛（TutorServiceImpl 统一降级），不出现第三次请求。 */
    @Test
    void givesUpAfterSingleRetryOnPersistent429_ac1() {
        // 两段失败脚本：重试后的第二次调用仍是 429（未入队即缺省 200，须显式补第二段失败）。
        for (int i = 0; i < 2; i++) {
            statusQueue.add(429);
            bodyQueue.add(GATEWAY_429_BODY);
        }
        AnthropicMessagesLlmClient client = new AnthropicMessagesLlmClient(endpoint(""), () -> 0L);

        assertThatThrownBy(() -> client.ask("s", "u"))
                .isInstanceOf(AnthropicServiceException.class)
                .satisfies(
                        e ->
                                assertThat(((AnthropicServiceException) e).statusCode())
                                        .isEqualTo(429));
        assertThat(requestCount.get()).isEqualTo(2);
    }

    /** [AC-1] 4xx 不重试：参数/鉴权类错误重试也不会变好，一次请求直接上抛。 */
    @Test
    void doesNotRetry4xx_ac1() {
        statusQueue.add(400);
        bodyQueue.add("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\"}}");
        AnthropicMessagesLlmClient client = new AnthropicMessagesLlmClient(endpoint(""), () -> 0L);

        assertThatThrownBy(() -> client.ask("s", "u"))
                .isInstanceOf(AnthropicServiceException.class)
                .satisfies(
                        e ->
                                assertThat(((AnthropicServiceException) e).statusCode())
                                        .isEqualTo(400));
        assertThat(requestCount.get()).isEqualTo(1);
    }

    /** [AC-1] 5xx 退避重试一次：第一次 500 → 重试仍 500 → 上抛，恰好两次请求。 */
    @Test
    void retries5xxOnceThenPropagates_ac1() {
        for (int i = 0; i < 2; i++) {
            statusQueue.add(500);
            bodyQueue.add("{\"type\":\"error\",\"error\":{\"type\":\"api_error\"}}");
        }
        AnthropicMessagesLlmClient client = new AnthropicMessagesLlmClient(endpoint(""), () -> 0L);

        assertThatThrownBy(() -> client.ask("s", "u"))
                .isInstanceOf(AnthropicServiceException.class)
                .satisfies(
                        e ->
                                assertThat(((AnthropicServiceException) e).statusCode())
                                        .isEqualTo(500));
        assertThat(requestCount.get()).isEqualTo(2);
    }

    /** [AC-1] 非空 key：以 x-api-key 头携带（兼容需要鉴权的 Anthropic 兼容网关）。 */
    @Test
    void sendsApiKeyAsXApiKeyHeaderWhenPresent_ac1() {
        AnthropicMessagesLlmClient client =
                new AnthropicMessagesLlmClient(endpoint("sk-owner"), () -> 0L);

        client.ask("s", "u");

        @SuppressWarnings("unchecked")
        Map<String, List<String>> headers =
                (Map<String, List<String>>) captured.get(0).get("headers");
        assertThat(headers.get("x-api-key")).containsExactly("sk-owner");
    }

    /**
     * 构造指向桩服务的端点参数。
     *
     * @param apiKey 密钥（可空串）
     * @return 端点参数
     */
    private LlmEndpoint endpoint(String apiKey) {
        return new LlmEndpoint(baseUrl(), apiKey, "glm-5.3", null);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Map<String, Object> parseBody(String raw) {
        // Jackson 3（tools.jackson）：解析失败抛非受检 JacksonException，测试里无需捕获。
        return new ObjectMapper().readValue(raw, Map.class);
    }
}
