package io.github.codejinxz.yilian.practice.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import org.springframework.ai.anthropic.AnthropicSetup;

import java.util.function.LongSupplier;

/**
 * Anthropic Messages 协议实现：服务 ZCode 本地网关（GLM 套餐额度，多账号轮询）等 Anthropic 兼容端点。
 *
 * <p>协议形状（实测）：{@code POST {base}/v1/messages}，请求 {@code {model,max_tokens,messages}} + {@code
 * anthropic-version: 2023-06-01} 头（SDK 自动补），响应 {@code
 * {content:[{type:"text",text}],usage:{input_tokens, output_tokens}}}。<strong>拼接口径</strong>：{@code
 * baseUrl} 填到网关前缀（如 {@code http://127.0.0.1:47860/gateway/zcode}），{@code /v1/messages} 由 SDK 拼（与
 * openai 轨 「填到 /v4 级前缀」同风格）。客户端底座沿用 Spring AI 的 {@link AnthropicSetup}（okhttp 传输 + 超时 + 重试 0），
 * 但<strong>不再经 Spring AI 的 ChatClient 提取正文</strong>。
 *
 * <p><strong>思考模型适配（手写提取兜底）</strong>：网关侧思考型模型（glm-5.3-flash 等）默认输出 {@code thinking} 块且 thinking 计入
 * max_tokens（实测 max_tokens=32 时全被 thinking 吃掉、stop_reason=max_tokens），输出 上限用 {@link
 * LlmChatClient#MAX_OUTPUT_TOKENS}（2048）。content 提取只拼 {@code type=text} 块、跳过 thinking 块： 桩实测 Spring
 * AI 2.0 的 {@code getText()} 会把 thinking 块内容当正文返回（text 块被丢），因此直接走官方 SDK 的 {@code Message.content()}
 * 按块类型过滤——该行为由 {@code AnthropicMessagesLlmClientTest} 的桩响应用例锁死。
 *
 * <p>装配：不走容器条件 bean，由 {@code LlmClientRegistry} 按激活 provider（或 legacy 环境变量）构造。 SDK 层自动重试显式置
 * 0：把最坏等待翻倍的自动重试交给本类统一掌控。apiKey 可空（网关本机无鉴权；非空时 SDK 以 {@code x-api-key} 头携带，兼容两种网关）。
 *
 * <p><strong>限流退避</strong>：网关 429 代表所有账号进入冷却（30s–1min，实测），且错误体是非标准形状 （{@code {"error":"ZCode
 * 网关:...","attempts":[...]}}）——因此识别只按 HTTP 状态码（SDK 按状态码抛 {@link AnthropicServiceException}
 * 子类，不依赖响应体形状）。429/5xx 先退避 {@link #RATE_LIMIT_BACKOFF_MS} 再试 <strong>一次</strong>：立即重试只会再吃
 * 429，而重试更多次会把最坏等待拖过 1 分钟（15s 超时 ×2 + 30s 退避 ≈ 60s）， 仍失败交给 {@code TutorServiceImpl} 统一降级为「答疑暂时不可用」。
 */
public class AnthropicMessagesLlmClient implements LlmChatClient {

    /** 限流退避时长 30s：ZCode 网关冷却档位（实测 30s–1min）的较短一档——退避过短则第二次调用大概率仍 429， 过长则最坏请求等待超出可接受范围。 */
    static final long RATE_LIMIT_BACKOFF_MS = 30_000L;

    /** 答疑温度：与 openai 两轨同口径，克制的解释性输出，不做发散创作。 */
    private static final double TEMPERATURE = 0.3;

    private final LlmEndpoint endpoint;
    private final AnthropicClient client;

    /** 退避等待函数；测试注入 0ms 替身，避免单测真睡 30s。 */
    private final LongSupplier backoffSleep;

    /**
     * 按端点参数组装 SDK 客户端；只做本地对象构造，不发任何网络请求。
     *
     * @param endpoint 已过齐备性校验的端点参数（anthropic-messages 轨 apiKey 可空）
     */
    public AnthropicMessagesLlmClient(LlmEndpoint endpoint) {
        this(
                endpoint,
                () -> {
                    sleepBackoff();
                    return 0L;
                });
    }

    /**
     * 测试可见装配：退避节奏可替换。
     *
     * @param endpoint 端点参数
     * @param backoffSleep 退避等待函数（毫秒）
     */
    AnthropicMessagesLlmClient(LlmEndpoint endpoint, LongSupplier backoffSleep) {
        this.endpoint = endpoint;
        this.client =
                AnthropicSetup.setupSyncClient(
                        endpoint.baseUrl(), endpoint.apiKey(), endpoint.timeout(), 0, null, null);
        this.backoffSleep = backoffSleep;
    }

    @Override
    public Reply ask(String systemPrompt, String userPrompt) {
        try {
            return askOnce(systemPrompt, userPrompt);
        } catch (AnthropicServiceException exception) {
            if (!retryable(exception)) {
                throw exception;
            }
            // 429/5xx：退避一个冷却档后重试一次；第二次的成败即最终结果（仍败则向上抛，统一降级）。
            backoffSleep.getAsLong();
            return askOnce(systemPrompt, userPrompt);
        }
    }

    /**
     * 单次调用（无重试）：官方 SDK 发 Messages 请求，content 按块类型提取——只拼 {@code type=text} 块，thinking / tool-use
     * 等块不进正文（思考模型的内部推理不得混入答疑回答）。
     *
     * @param systemPrompt 系统提示
     * @param userPrompt 用户消息
     * @return 回复内容与用量
     */
    private Reply askOnce(String systemPrompt, String userPrompt) {
        MessageCreateParams params =
                MessageCreateParams.builder()
                        .model(endpoint.model())
                        .maxTokens(MAX_OUTPUT_TOKENS)
                        .temperature(TEMPERATURE)
                        .system(systemPrompt)
                        .addUserMessage(userPrompt)
                        .build();
        Message message = client.messages().create(params);
        StringBuilder content = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block.isText()) {
                content.append(block.asText().text());
            }
        }
        return new Reply(
                content.isEmpty() ? null : content.toString(),
                (int) message.usage().inputTokens(),
                (int) message.usage().outputTokens());
    }

    /**
     * 是否值得退避重试：网关冷却（429）与服务端瞬时故障（5xx）。4xx 参数/鉴权类错误重试也不会变好， 直接向上抛统一降级。
     *
     * @param exception SDK 服务端异常（带状态码）
     * @return 可重试返回 true
     */
    private static boolean retryable(AnthropicServiceException exception) {
        int status = exception.statusCode();
        return status == 429 || status >= 500;
    }

    /** 默认退避：睡满一个冷却档；等待被中断则恢复中断位并放弃重试（异常上抛走统一降级）。 */
    private static void sleepBackoff() {
        try {
            Thread.sleep(RATE_LIMIT_BACKOFF_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM 限流退避等待被中断", interrupted);
        }
    }
}
