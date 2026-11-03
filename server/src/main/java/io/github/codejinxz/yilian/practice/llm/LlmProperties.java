package io.github.codejinxz.yilian.practice.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * LLM 接入配置（017 题目答疑），前缀 {@code yilian.llm}，值来自 {@code YILIAN_LLM_*} 环境变量。
 *
 * <p>三态判定的部署侧依据：{@link #hasCredentials()} 为 false 时功能不存在（答疑端点 40400、前端无入口）。 API Key 是密钥，按 AGENTS
 * §3.4 只进环境变量，不落库、不进配置文件；baseUrl/model 是普通运营参数， 管理员可在后台覆盖（存 {@code app_settings} 键 {@code
 * llm.tutor}），此处值是缺省回退。
 *
 * @param baseUrl OpenAI 兼容端点，例如智谱 bigmodel 的 {@code https://open.bigmodel.cn/api/paas/v4}
 * @param apiKey 模型服务密钥；只从环境变量注入
 * @param model 模型名；智谱套餐常用 {@code glm-4.7}，缺省即它
 * @param timeout 单次调用超时；缺省 15s（成本护栏：超时即降级，不拖住答题主链路）
 */
@ConfigurationProperties(prefix = "yilian.llm")
public record LlmProperties(String baseUrl, String apiKey, String model, Duration timeout) {

    /** 模型名缺省值：智谱 GLM 主力对话模型（017 计划示例），可用 {@code YILIAN_LLM_MODEL} 覆盖。 */
    static final String DEFAULT_MODEL = "glm-4.7";

    /** 超时缺省值：15s。 */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /** 规范化：null/空白归一为空串，model/timeout 补缺省，保证下游不需要判空。 */
    public LlmProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        timeout =
                timeout == null || timeout.isNegative() || timeout.isZero()
                        ? DEFAULT_TIMEOUT
                        : timeout;
    }

    /**
     * 是否已具备调用条件：端点与密钥都已配置（模型名恒有缺省）。
     *
     * @return 配置齐备返回 true
     */
    public boolean hasCredentials() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty();
    }
}
