package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 管理端 LLM 配置视图（API §8.14）。
 *
 * <p>API Key 永不回读，只回 {@code hasKey} 布尔与 {@code keyLast4} 尾号投影。生效值优先级（028）：目录激活条目 &gt; tutor
 * 覆盖（app_settings 键 {@code llm.tutor}）&gt; legacy 环境变量；{@code source} 描述 legacy 形态的取值来源（目录激活态恒为
 * env，前端以目录徽标优先）。027B 起 hasKey 为解析链口径（入库密钥可解密或 env 任一，见 {@code LlmProviderKeys}）。
 *
 * @param enabled 答疑是否开放（app_settings 键 llm.tutor）
 * @param baseUrl 生效端点：目录激活=激活条目 baseUrl；legacy 形态=覆盖值 &gt; YILIAN_LLM_BASE_URL
 * @param model 生效模型名：目录激活=激活条目 model；legacy 形态=覆盖值 &gt; YILIAN_LLM_MODEL
 * @param hasKey 是否有可用密钥（027B 解析链口径：llm_provider_keys 入库密钥可解密或 YILIAN_LLM_KEY_* env 任一；legacy 形态为
 *     tutor 预留行可解密或 YILIAN_LLM_API_KEY）
 * @param providerId 生效 provider id；legacy 形态（覆盖轨与环境变量轨）与目录未激活/未配置时为 legacy 或 null
 * @param protocol 生效协议 wire 值（anthropic-messages | openai-chat | openai-responses）；未配置时为 null
 * @param credentialsComplete 凭证按协议口径是否齐备（anthropic-messages 轨 key 可空，齐备只看端点）；前端据此判定 答疑可用性，不能用
 *     hasKey 代替（anthropic 轨无 key 也可用）
 * @param source legacy 形态取值来源：custom=存在 tutor 覆盖（baseUrl/model 任一非空且目录未激活）；env=纯环境变量。目录激活态恒为
 *     env（覆盖休眠，生效值来自目录条目，前端以目录徽标优先）
 * @param keyLast4 tutor 密钥行（llm_provider_keys 预留 id=tutor）的尾 4 位辨识号；env 态密钥与目录激活态为 null（明文永不回读）
 */
@Schema(description = "LLM 答疑配置视图：apiKey 不回读，只回 hasKey/keyLast4 与凭证齐备性")
public record LlmConfigView(
        @Schema(description = "答疑是否开放", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean enabled,
        @Schema(
                        description = "生效端点（覆盖值 > env；目录激活时为激活条目端点）",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String baseUrl,
        @Schema(
                        description = "生效模型名（覆盖值 > env；目录激活时为激活条目模型）",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String model,
        @Schema(description = "是否有可用密钥（入库密钥或 env 任一）", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean hasKey,
        @Schema(description = "生效 provider id（legacy=legacy 形态；null=未配置）", nullable = true)
                String providerId,
        @Schema(description = "生效协议 wire 值；未配置时为 null", nullable = true) String protocol,
        @Schema(
                        description = "凭证按协议口径是否齐备（anthropic-messages 轨 key 可空）",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                boolean credentialsComplete,
        @Schema(
                        description = "legacy 形态取值来源：custom=tutor 覆盖生效；env=纯环境变量（目录激活态恒为 env）",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String source,
        @Schema(description = "tutor 密钥行尾 4 位辨识号；env 态密钥/目录激活态为 null", nullable = true)
                String keyLast4) {

    /** source 取值：custom=tutor 覆盖生效。 */
    public static final String SOURCE_CUSTOM = "custom";

    /** source 取值：env=纯环境变量（含目录激活态）。 */
    public static final String SOURCE_ENV = "env";
}
