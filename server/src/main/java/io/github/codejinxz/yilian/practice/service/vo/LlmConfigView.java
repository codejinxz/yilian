package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 管理端 LLM 配置视图（017，API §8.11）。
 *
 * <p>API Key 永不回读（AGENTS §3.4），只回 {@code hasKey} 布尔。
 *
 * @param enabled 答疑是否开放（app_settings 键 llm.tutor）
 * @param baseUrl 生效端点（后台覆盖值，缺省回退 YILIAN_LLM_BASE_URL）
 * @param model 生效模型名（后台覆盖值，缺省回退 YILIAN_LLM_MODEL）
 * @param hasKey 环境变量是否已配置 API Key；false 时答疑功能不存在
 */
@Schema(description = "LLM 答疑配置视图：apiKey 不回读，只回 hasKey")
public record LlmConfigView(
        @Schema(description = "答疑是否开放", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean enabled,
        @Schema(description = "生效端点", requiredMode = Schema.RequiredMode.REQUIRED) String baseUrl,
        @Schema(description = "生效模型名", requiredMode = Schema.RequiredMode.REQUIRED) String model,
        @Schema(description = "API Key 是否已配置（环境变量）", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean hasKey) {}
