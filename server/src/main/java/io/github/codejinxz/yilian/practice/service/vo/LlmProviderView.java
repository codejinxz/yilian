package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 管理端 provider 目录条目视图（022，API §8.15.1）：元数据 + 密钥存在性（env 索引，不回读）+ 激活态。
 *
 * @param id 目录内唯一 id
 * @param name 展示名
 * @param protocol 协议 wire 值：anthropic-messages | openai-chat | openai-responses
 * @param baseUrl 协议端点前缀
 * @param model 模型名
 * @param hasKey 该 provider 的环境变量密钥（YILIAN_LLM_KEY_&lt;大写ID&gt;）是否已配置；anthropic-messages 轨无 key
 *     也可用（凭证齐备性看激活后的 /llm/config credentialsComplete）
 * @param active 是否为当前激活的 provider（激活唯一；答疑等 AI 功能的数据源）
 */
@Schema(description = "provider 目录条目：apiKey 不入库不回读，只回 hasKey 与激活态")
public record LlmProviderView(
        @Schema(description = "目录内唯一 id", requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(description = "展示名", requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(description = "协议 wire 值", requiredMode = Schema.RequiredMode.REQUIRED)
                String protocol,
        @Schema(description = "协议端点前缀", requiredMode = Schema.RequiredMode.REQUIRED) String baseUrl,
        @Schema(description = "模型名", requiredMode = Schema.RequiredMode.REQUIRED) String model,
        @Schema(description = "环境变量密钥是否已配置", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean hasKey,
        @Schema(description = "是否为当前激活 provider", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean active) {}
