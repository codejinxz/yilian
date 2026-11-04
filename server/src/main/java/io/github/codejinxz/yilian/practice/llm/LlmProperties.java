package io.github.codejinxz.yilian.practice.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * LLM 接入配置（017 引入，022 起 legacy 兼容轨），前缀 {@code yilian.llm}，值来自 {@code YILIAN_LLM_*} 环境变量。
 *
 * <p>022 起 provider 目录（app_settings 键 {@code llm.providers} / {@code llm.active}）是模型接入的首选形态； 本记录降级为
 * <strong>legacy 单 provider 兼容轨</strong>：目录为空且本记录凭证齐备时，视为一个隐式 provider（读路径 兼容 017/021 部署，见 {@code
 * LlmClientRegistry}）。API Key 是密钥，按 AGENTS §3.4 不落库、不进配置文件；目录模式下 密钥改按 provider id 索引（{@code
 * YILIAN_LLM_KEY_<大写ID>}），本记录的 {@code apiKey} 仅在 legacy 轨生效。
 *
 * <p>021 双轨（{@code provider=openai|anthropic}）语义不变，作为 legacy 轨的协议选择；022 三协议（{@link LlmProtocol}）只随
 * provider 目录启用。
 *
 * @param provider 协议族选择（legacy 轨）；缺省 {@link LlmProvider#OPENAI} 向后兼容既有部署
 * @param baseUrl 协议端点：openai 填到 /v4 级前缀（如智谱 {@code https://open.bigmodel.cn/api/paas/v4}）；
 *     anthropic 填到网关前缀 （如 {@code http://127.0.0.1:47860/gateway/zcode}），/v1/messages 由客户端拼
 * @param apiKey 模型服务密钥（legacy 轨）；只从环境变量注入
 * @param model 模型名；缺省 {@code glm-4.7}，ZCode 网关用 {@code YILIAN_LLM_MODEL=glm-5.3} 覆盖
 * @param timeout 单次调用超时；缺省 15s（成本护栏：超时即降级，不拖住答题主链路）
 */
@ConfigurationProperties(prefix = "yilian.llm")
public record LlmProperties(
        String baseUrl, String apiKey, String model, Duration timeout, LlmProvider provider) {

    /** 模型名缺省值：智谱 GLM 主力对话模型（017 计划示例），可用 {@code YILIAN_LLM_MODEL} 覆盖。 */
    static final String DEFAULT_MODEL = "glm-4.7";

    /** 超时缺省值：15s。 */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /** 协议族（021，legacy 轨）：决定客户端实现与凭证口径，见 {@link #hasCredentials()}。 */
    public enum LlmProvider {
        /** OpenAI 兼容协议（智谱/DeepSeek 等），Spring AI OpenAI 路径。 */
        OPENAI,
        /** Anthropic Messages 协议（ZCode 本地网关），Spring AI Anthropic 路径。 */
        ANTHROPIC
    }

    /** 规范化：null/空白归一为空串，model/timeout/provider 补缺省，保证下游不需要判空。 */
    public LlmProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        timeout =
                timeout == null || timeout.isNegative() || timeout.isZero()
                        ? DEFAULT_TIMEOUT
                        : timeout;
        provider = provider == null ? LlmProvider.OPENAI : provider;
    }

    /**
     * 是否已具备调用条件（021 起按 provider 分口径）：端点都必须配置；密钥仅 openai 轨必填—— anthropic 轨服务 ZCode
     * 本地网关，网关本机无鉴权（2026-11-05 主人探测实证），key 可空。
     *
     * @return 配置齐备返回 true
     */
    public boolean hasCredentials() {
        return !baseUrl.isEmpty() && (provider == LlmProvider.ANTHROPIC || !apiKey.isEmpty());
    }

    /**
     * 映射为客户端装配用的端点参数（022 legacy 轨）：本记录已经过绑定规范化，此处只做形状转换。
     *
     * @return 端点参数
     */
    public LlmEndpoint asEndpoint() {
        return new LlmEndpoint(baseUrl, apiKey, model, timeout);
    }

    /**
     * legacy 轨协议映射（022）：021 的双轨枚举映射到三协议枚举（openai→openai-chat，anthropic→anthropic-messages）。
     *
     * @return 对应协议
     */
    public LlmProtocol legacyProtocol() {
        return provider == LlmProvider.ANTHROPIC
                ? LlmProtocol.ANTHROPIC_MESSAGES
                : LlmProtocol.OPENAI_CHAT;
    }
}
