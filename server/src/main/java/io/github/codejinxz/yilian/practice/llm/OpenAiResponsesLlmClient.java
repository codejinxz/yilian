package io.github.codejinxz.yilian.practice.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses 协议实现：服务 OpenAI Responses API 兼容端点。
 *
 * <p><strong>为什么手写 {@code java.net.http}</strong>：spring-ai 2.0.0 的 openai 模块只提供 Chat Completions
 * 路径（无 Responses API 支持），官方 openai-java SDK 亦不在 classpath 专职此事；Responses 形状简单 （单请求单响应），手写 JDK
 * 客户端不引入任何新依赖。超时 15s、SDK 无重试、429/5xx 不做应用层 退避（openai 两轨无网关冷却特性），失败统一上抛交 {@code TutorServiceImpl}
 * 降级。
 *
 * <p>协议形状（OpenAI Responses API）：{@code POST {base}/v1/responses}，请求 {@code {model,
 * max_output_tokens, temperature, input:[{role, content}]}}（系统提示以 {@code role=system} 输入项置于首位）， 响应
 * {@code output[]} 数组取 {@code type=message} 项的 {@code content[]} 中 {@code type=output_text} 的
 * {@code text} 拼为正文——{@code type=reasoning}（思考摘要）等非 message 项不进正文，天然对思考模型鲁棒； usage 取 {@code
 * input_tokens/output_tokens}。<strong>拼接口径</strong>：{@code baseUrl} 填到 {@code /v1} 之前的 前缀（如 {@code
 * https://api.openai.com}），{@code /v1/responses} 由本客户端拼。
 */
public class OpenAiResponsesLlmClient implements LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesLlmClient.class);

    /** 连接建立超时：与请求超时分离，DNS/握手卡死不该占满 15s 请求预算。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final LlmEndpoint endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 按端点参数组装客户端；只做本地对象构造，不发任何网络请求。
     *
     * @param endpoint 已过齐备性校验的端点参数（openai-responses 轨 apiKey 必填，以 {@code Authorization: Bearer}
     *     头携带）
     */
    public OpenAiResponsesLlmClient(LlmEndpoint endpoint) {
        this.endpoint = endpoint;
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
    }

    @Override
    public Reply ask(String systemPrompt, String userPrompt) {
        HttpRequest request = buildRequest(systemPrompt, userPrompt);
        HttpResponse<String> response;
        try {
            response =
                    httpClient.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI Responses 调用被中断", interrupted);
        } catch (IOException ioException) {
            // 异常消息只带类型不带响应体/URL 细节：堆栈走 error_log，客户端文案由上层统一降级。
            throw new IllegalStateException(
                    "OpenAI Responses 调用失败：" + ioException.getClass().getSimpleName(), ioException);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            log.warn("OpenAI Responses 非成功响应：status={}, model={}", status, endpoint.model());
            throw new IllegalStateException("OpenAI Responses 端点返回 HTTP " + status);
        }
        return parseReply(response.body());
    }

    /**
     * 组装请求：{@code POST {base}/v1/responses}，Bearer 鉴权，body 为 Responses API 请求形状（LinkedHashMap 固定
     * 字段名，不依赖注解）。
     *
     * @param systemPrompt 系统提示
     * @param userPrompt 用户消息
     * @return HTTP 请求
     */
    private HttpRequest buildRequest(String systemPrompt, String userPrompt) {
        String url = endpoint.baseUrl() + "/v1/responses";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model());
        body.put("max_output_tokens", MAX_OUTPUT_TOKENS);
        body.put("temperature", 0.3);
        body.put(
                "input",
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(endpoint.timeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + endpoint.apiKey())
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 解析响应：{@code output[]} 只取 {@code type=message} 项内 {@code type=output_text} 的 {@code text}（多个块
     * 直接拼接，与 anthropic 轨 Spring AI 的拼接口径一致）；{@code type=reasoning} 等思考项不进正文。结构缺失或为空
     * 时不抛错、返回空正文，由上层按「空回复降级」统一处理。
     *
     * @param payload 响应体 JSON 文本
     * @return 回复内容与用量
     */
    private Reply parseReply(String payload) {
        Map<String, Object> root = objectMapper.readValue(payload, Map.class);
        StringBuilder content = new StringBuilder();
        if (root.get("output") instanceof List<?> output) {
            for (Object item : output) {
                if (!(item instanceof Map<?, ?> message)
                        || !"message".equals(message.get("type"))) {
                    continue;
                }
                if (message.get("content") instanceof List<?> blocks) {
                    for (Object block : blocks) {
                        if (block instanceof Map<?, ?> text
                                && "output_text".equals(text.get("type"))
                                && text.get("text") instanceof String value) {
                            content.append(value);
                        }
                    }
                }
            }
        }
        int promptTokens = 0;
        int completionTokens = 0;
        if (root.get("usage") instanceof Map<?, ?> usage) {
            promptTokens = intValue(usage.get("input_tokens"));
            completionTokens = intValue(usage.get("output_tokens"));
        }
        String text = content.isEmpty() ? null : content.toString();
        return new Reply(text, promptTokens, completionTokens);
    }

    /**
     * 宽松取整：usage 字段缺失或非数值一律 0（用量缺失不构成失败）。
     *
     * @param value 原始值
     * @return 非负整数
     */
    private static int intValue(Object value) {
        return value instanceof Number number && number.longValue() >= 0
                ? (int) number.longValue()
                : 0;
    }
}
