package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 管理端 provider 目录条目视图（API §8.15.1）：元数据 + 密钥状态 + 激活态。 明文密钥永不回读——只回 {@code hasKey} 布尔与 {@code
 * keyLast4} 尾 4 位辨识号。
 *
 * @param id 目录内唯一 id
 * @param name 展示名
 * @param protocol 协议 wire 值：anthropic-messages | openai-chat | openai-responses
 * @param baseUrl 协议端点前缀
 * @param model 模型名
 * @param hasKey 该 provider 是否有可用密钥（解析链口径：入库密钥可解密或环境变量 YILIAN_LLM_KEY_&lt;大写ID&gt;
 *     任一存在）；anthropic-messages 轨无 key 也可用（凭证齐备性看 /llm/config 的 credentialsComplete）
 * @param keyLast4 入库密钥的尾 4 位辨识号（录入时留痕）；无入库密钥（env 态/未配置）时为 null， 即 env 态不显示 last4
 * @param active 是否为当前激活的 provider（激活唯一；答疑等 AI 功能的数据源）
 */
@Schema(description = "provider 目录条目：明文不回读，只回 hasKey/keyLast4 与激活态")
public record LlmProviderView(
        @Schema(description = "目录内唯一 id", requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(description = "展示名", requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(description = "协议 wire 值", requiredMode = Schema.RequiredMode.REQUIRED)
                String protocol,
        @Schema(description = "协议端点前缀", requiredMode = Schema.RequiredMode.REQUIRED) String baseUrl,
        @Schema(description = "模型名", requiredMode = Schema.RequiredMode.REQUIRED) String model,
        @Schema(description = "是否有可用密钥（入库可解密或 env 任一）", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean hasKey,
        @Schema(description = "入库密钥尾 4 位辨识号；env 态/未配置为 null（不回显明文）", nullable = true)
                String keyLast4,
        @Schema(description = "是否为当前激活 provider", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean active) {}
