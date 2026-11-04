package io.github.codejinxz.yilian.practice.llm;

import java.time.Duration;

/**
 * 单次客户端装配的端点参数：由激活的 provider 目录条目或 legacy 环境变量（{@link
 * LlmProperties#asEndpoint()}）归一而来。客户端实现只依赖本记录，不感知配置来源（目录 / 环境变量）。
 *
 * <p>apiKey 只在本记录内存中出现，不落库、不进日志；anthropic-messages 轨 key 可空（网关本机 无鉴权），openai 两轨必填——齐备性判定在 {@code
 * LlmClientRegistry}，本记录只做规范化。
 *
 * @param baseUrl 协议端点前缀（各协议的拼接口径见对应实现类）
 * @param apiKey 模型服务密钥；可为空（anthropic-messages 轨）
 * @param model 模型名；空白回落 {@link LlmProperties#DEFAULT_MODEL}
 * @param timeout 单次调用超时；非正值回落 15s
 */
record LlmEndpoint(String baseUrl, String apiKey, String model, Duration timeout) {

    /** 规范化：null/空白归一为空串，model/timeout 补缺省，保证下游不需要判空。 */
    LlmEndpoint {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null || model.isBlank() ? LlmProperties.DEFAULT_MODEL : model.trim();
        timeout =
                timeout == null || timeout.isNegative() || timeout.isZero()
                        ? LlmProperties.DEFAULT_TIMEOUT
                        : timeout;
    }
}
