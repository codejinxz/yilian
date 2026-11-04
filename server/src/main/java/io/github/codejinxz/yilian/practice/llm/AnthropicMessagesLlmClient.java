package io.github.codejinxz.yilian.practice.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Model;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.function.LongSupplier;

/**
 * Anthropic Messages 协议实现（021）：服务主人的 ZCode 本地网关（GLM 套餐额度，多账号轮询）。
 *
 * <p>协议形状（主人 2026-11-05 实测）：{@code POST {base}/v1/messages}，请求 {@code {model,max_tokens,messages}}
 * + {@code anthropic-version: 2023-06-01} 头（SDK 自动补），响应 {@code
 * {content:[{type:"text",text}],usage:{input_tokens, output_tokens}}}。<strong>拼接口径</strong>：{@code
 * YILIAN_LLM_BASE_URL} 填到网关前缀（如 {@code http://127.0.0.1:47860/gateway/zcode}），{@code /v1/messages}
 * 由 SDK 拼（与 openai 轨「填到 /v4 级前缀」同风格）。
 *
 * <p>装配镜像 {@link SpringAiLlmClient}：不走 starter 自动配置（application.yml 已把 {@code spring.ai.model.*}
 * 全部置 none），按 {@code YILIAN_LLM_PROVIDER=anthropic} 由 {@link TutorLlmConfiguration} 惰性装配。重试显式置
 * 0：SDK 层自动重试 会把最坏等待翻倍，退避节奏由本类统一掌控。apiKey 可空（网关本机无鉴权；非空时 SDK 以 {@code x-api-key} 头携带，兼容两种网关）。
 *
 * <p><strong>限流退避</strong>：网关 429 代表所有账号进入冷却（30s–1min，主人实测），且错误体是非标准形状 （{@code {"error":"ZCode
 * 网关:...","attempts":[...]}}）——因此识别只按 HTTP 状态码（SDK 按状态码抛 {@link AnthropicServiceException}
 * 子类，不依赖响应体形状）。429/5xx 先退避 {@link #RATE_LIMIT_BACKOFF_MS} 再试 <strong>一次</strong>：立即重试只会再吃
 * 429，而重试更多次会把最坏等待拖过 1 分钟（15s 超时 ×2 + 30s 退避 ≈ 60s）， 仍失败交给 {@code TutorServiceImpl} 统一降级为「答疑暂时不可用」。
 */
public class AnthropicMessagesLlmClient implements LlmChatClient {

    /** 单次回复输出 token 上限：与 OpenAI 轨同口径（017 R1 成本护栏），单一事实来源在 {@link SpringAiLlmClient}。 */
    static final int MAX_OUTPUT_TOKENS = SpringAiLlmClient.MAX_OUTPUT_TOKENS;

    /** 限流退避时长 30s：ZCode 网关冷却档位（主人实测 30s–1min）的较短一档——退避过短则第二次调用大概率仍 429， 过长则最坏请求等待超出可接受范围。 */
    static final long RATE_LIMIT_BACKOFF_MS = 30_000L;

    private final ChatClient chatClient;

    /** 退避等待函数；测试注入 0ms 替身，避免单测真睡 30s。 */
    private final LongSupplier backoffSleep;

    /**
     * 按配置组装模型客户端；只做本地对象构造，不发任何网络请求。
     *
     * @param properties 已过 {@code hasCredentials()} 口径校验的 LLM 配置（anthropic 轨 apiKey 可空）
     */
    public AnthropicMessagesLlmClient(LlmProperties properties) {
        this(
                properties,
                () -> {
                    sleepBackoff();
                    return 0L;
                });
    }

    /**
     * 测试可见装配：退避节奏可替换。
     *
     * @param properties LLM 配置
     * @param backoffSleep 退避等待函数（毫秒）
     */
    AnthropicMessagesLlmClient(LlmProperties properties, LongSupplier backoffSleep) {
        AnthropicClient sync =
                AnthropicSetup.setupSyncClient(
                        properties.baseUrl(),
                        properties.apiKey(),
                        properties.timeout(),
                        0,
                        null,
                        null);
        AnthropicClientAsync async =
                AnthropicSetup.setupAsyncClient(
                        properties.baseUrl(),
                        properties.apiKey(),
                        properties.timeout(),
                        0,
                        null,
                        null);
        AnthropicChatOptions options =
                AnthropicChatOptions.builder()
                        .model(Model.of(properties.model()))
                        .temperature(0.3)
                        .maxTokens(MAX_OUTPUT_TOKENS)
                        .maxRetries(0)
                        .build();
        this.chatClient =
                ChatClient.create(
                        AnthropicChatModel.builder()
                                .anthropicClient(sync)
                                .anthropicClientAsync(async)
                                .options(options)
                                .build());
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
     * 单次调用（无重试）：系统提示 + 用户消息经 ChatClient 组装，模型回复与 token 用量取自 Anthropic 响应形状。
     *
     * @param systemPrompt 系统提示
     * @param userPrompt 用户消息
     * @return 回复内容与用量
     */
    private Reply askOnce(String systemPrompt, String userPrompt) {
        ChatResponse response =
                chatClient.prompt().system(systemPrompt).user(userPrompt).call().chatResponse();
        String content =
                response.getResult() == null || response.getResult().getOutput() == null
                        ? null
                        : response.getResult().getOutput().getText();
        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            // Anthropic usage: input_tokens/output_tokens → Spring AI Usage 的 prompt/completion 口径。
            promptTokens = response.getMetadata().getUsage().getPromptTokens();
            completionTokens = response.getMetadata().getUsage().getCompletionTokens();
        }
        return new Reply(
                content,
                promptTokens == null ? 0 : promptTokens,
                completionTokens == null ? 0 : completionTokens);
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
