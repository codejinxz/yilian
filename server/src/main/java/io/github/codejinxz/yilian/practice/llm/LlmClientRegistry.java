package io.github.codejinxz.yilian.practice.llm;

import java.util.Optional;

/**
 * LLM 客户端注册表：按 provider 目录的激活条目（或 legacy 环境变量）在运行时装配 {@link LlmChatClient}，
 * 业务层（答疑）只依赖本接口——客户端随目录变更自动换轨，不要求重启。
 *
 * <p>三态语义与 017/021 一致：无可用客户端（目录空且 legacy 未配置、目录未激活、激活条目凭证不齐）时 {@link #chatHandle()}
 * 为空，答疑走「功能不存在」（40400）。
 */
public interface LlmClientRegistry {

    /** legacy 单 provider 在管理端视图里的 providerId（目录模式从不出现）。 */
    String LEGACY_PROVIDER_ID = "legacy";

    /**
     * 取当前可用的对话客户端句柄：目录模式下要求激活条目凭证按协议口径齐备（anthropic-messages key 可空，openai 两轨 key 必填）；目录空且 legacy
     * 环境变量齐备时回退 legacy 单 provider（读路径兼容 017/021 部署）。
     *
     * @return 客户端句柄；无可用配置时为空（功能不存在）
     */
    Optional<ChatHandle> chatHandle();

    /**
     * 取当前生效配置的管理端视图：目录模式返回激活条目元数据 + 密钥可用性（027B 解析链：入库密钥或 env 任一；即使凭证不齐也返回，便于管理员 看到「差什么」）；legacy
     * 轨返回环境变量生效值；两者皆无（目录未激活/为空且 legacy 未配置）时为空。
     *
     * @return 生效配置视图
     */
    Optional<EffectiveConfig> effectiveConfig();

    /**
     * 是否处于 provider 目录模式（目录里至少有一个合法条目）；用于开启答疑被拒时的定向文案（指向 env key 索引而非 legacy 环境变量两件套）。
     *
     * @return 目录模式返回 true
     */
    boolean directoryMode();

    /** 一次可用调用的句柄。 */
    record ChatHandle(LlmChatClient client, String providerId, String model) {}

    /** 管理端生效配置视图（密钥只回 hasKey 布尔——入库可解密或 env 任一，不回读）。 */
    record EffectiveConfig(
            String providerId,
            String protocol,
            String baseUrl,
            String model,
            boolean hasKey,
            boolean credentialsComplete) {}
}
