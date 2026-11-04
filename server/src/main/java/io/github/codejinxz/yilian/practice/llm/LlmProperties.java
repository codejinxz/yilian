package io.github.codejinxz.yilian.practice.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * LLM 接入配置（017 题目答疑，021 扩 provider），前缀 {@code yilian.llm}，值来自 {@code YILIAN_LLM_*} 环境变量。
 *
 * <p>三态判定的部署侧依据：{@link #hasCredentials()} 为 false 时功能不存在（答疑端点 40400、前端无入口）。 本记录是各项配置的
 * <strong>唯一生效来源</strong>，全部只经 {@code YILIAN_LLM_*} 环境变量注入：API Key 是密钥，按 AGENTS §3.4 不落库、不进配置文件；
 * provider/baseUrl/model 同样后台不可改——后台配置接口（PUT /api/admin/llm/config）对它们传非空值一律 40000 拒绝， {@code
 * app_settings} 键 {@code llm.tutor} 只存答疑 enabled 开关，不存任何覆盖值（017 R1 实态，019 复核修正旧注记）。
 *
 * <p>021 双轨：{@code provider=openai}（缺省，智谱/DeepSeek 等 OpenAI 兼容端点，baseUrl+apiKey 双必填） 与 {@code
 * provider=anthropic}（Anthropic Messages 协议，服务主人的 ZCode 本地网关——网关本机无鉴权， apiKey 可空；若非空则由客户端作为 {@code
 * x-api-key} 头带上，兼容两种网关）。
 *
 * @param provider 协议族选择；缺省 {@link LlmProvider#OPENAI} 向后兼容既有部署
 * @param baseUrl 协议端点：openai 填到 /v4 级前缀（如智谱 {@code https://open.bigmodel.cn/api/paas/v4}）；
 *     anthropic 填到网关前缀 （如 {@code http://127.0.0.1:47860/gateway/zcode}），/v1/messages 由客户端拼
 * @param apiKey 模型服务密钥；只从环境变量注入
 * @param model 模型名；openai 轨缺省 {@code glm-4.7}，ZCode 网关用 {@code YILIAN_LLM_MODEL=glm-5.3} 覆盖
 * @param timeout 单次调用超时；缺省 15s（成本护栏：超时即降级，不拖住答题主链路）
 */
@ConfigurationProperties(prefix = "yilian.llm")
public record LlmProperties(
        String baseUrl, String apiKey, String model, Duration timeout, LlmProvider provider) {

    /** 模型名缺省值：智谱 GLM 主力对话模型（017 计划示例），可用 {@code YILIAN_LLM_MODEL} 覆盖。 */
    static final String DEFAULT_MODEL = "glm-4.7";

    /** 超时缺省值：15s。 */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /** 协议族（021）：决定客户端实现与凭证口径，见 {@link #hasCredentials()}。 */
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
}
