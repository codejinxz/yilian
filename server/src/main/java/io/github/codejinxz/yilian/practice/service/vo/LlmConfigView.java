package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 管理端 LLM 配置视图（API §8.14）。
 *
 * <p>API Key 永不回读，只回 {@code hasKey} 布尔。baseUrl/model/hasKey 反映<strong>激活的 provider</strong>（目录模式）或
 * legacy 环境变量（目录为空时），见 {@code LlmClientRegistry}。
 *
 * @param enabled 答疑是否开放（app_settings 键 llm.tutor）
 * @param baseUrl 生效端点（激活 provider 的 baseUrl 或 legacy YILIAN_LLM_BASE_URL）
 * @param model 生效模型名
 * @param hasKey 环境变量密钥是否已配置（目录模式按 provider id 索引 YILIAN_LLM_KEY_*；legacy 为 YILIAN_LLM_API_KEY）
 * @param providerId 生效 provider id；legacy 环境变量直配时为 legacy，目录未激活/未配置时为 null
 * @param protocol 生效协议 wire 值（anthropic-messages | openai-chat | openai-responses）；未配置时为 null
 * @param credentialsComplete 凭证按协议口径是否齐备（anthropic-messages 轨 key 可空，齐备只看端点）；前端据此判定 答疑可用性，不能用
 *     hasKey 代替（anthropic 轨无 key 也可用）
 */
@Schema(description = "LLM 答疑配置视图：apiKey 不回读，只回 hasKey 与凭证齐备性")
public record LlmConfigView(
        @Schema(description = "答疑是否开放", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean enabled,
        @Schema(description = "生效端点", requiredMode = Schema.RequiredMode.REQUIRED) String baseUrl,
        @Schema(description = "生效模型名", requiredMode = Schema.RequiredMode.REQUIRED) String model,
        @Schema(description = "环境变量密钥是否已配置", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean hasKey,
        @Schema(description = "生效 provider id（legacy=环境变量直配；null=未配置）", nullable = true)
                String providerId,
        @Schema(description = "生效协议 wire 值；未配置时为 null", nullable = true) String protocol,
        @Schema(
                        description = "凭证按协议口径是否齐备（anthropic-messages 轨 key 可空）",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                boolean credentialsComplete) {}
