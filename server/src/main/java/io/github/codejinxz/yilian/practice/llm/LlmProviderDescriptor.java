package io.github.codejinxz.yilian.practice.llm;

/**
 * provider 目录条目：CC Switch 式多 provider 的元数据（name/protocol/baseURL/model），<strong>不含
 * apiKey</strong>——目录只存元数据；密钥解析链见 {@code LlmProviderKeys}：入库密文解密 &gt; env {@code
 * YILIAN_LLM_KEY_<大写ID>} &gt; legacy，UI 随时切换（激活）。
 *
 * @param id 目录内唯一标识；{@value #ID_PATTERN}（小写字母/数字/连字符），env 变量名按连字符→下划线大写映射
 * @param name 展示名（管理端列表卡）
 * @param protocol 对话协议（决定客户端实现）
 * @param baseUrl 协议端点前缀（各协议拼接口径见对应实现类）
 * @param model 模型名
 */
public record LlmProviderDescriptor(
        String id, String name, LlmProtocol protocol, String baseUrl, String model) {

    /** id 形态约束：小写字母/数字/连字符（管理端 POST 校验，服务端目录读取侧同样校验后跳过非法条目）。 */
    public static final String ID_PATTERN = "[a-z0-9-]+";

    /** id 长度上限：64，为 env 变量名留足余量。 */
    public static final int ID_MAX_LENGTH = 64;

    /** 规范化：全部字段去两端空白，保证目录 JSON 里不存脏空白。 */
    public LlmProviderDescriptor {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        model = model == null ? "" : model.trim();
    }

    /**
     * id 是否合法（目录写入与读取侧共用的唯一口径）。
     *
     * @return 合法返回 true
     */
    public boolean hasValidId() {
        return !id.isEmpty() && id.length() <= ID_MAX_LENGTH && id.matches(ID_PATTERN);
    }

    /**
     * 密钥齐备口径（按协议分）：anthropic-messages 轨 key 可空（网关本机无鉴权），openai 两轨必填；端点一律必填。
     *
     * @param hasEnvKey 该 provider 是否有可用密钥（解析链口径：入库密钥或 env 任一）
     * @return 凭证齐备返回 true
     */
    public boolean hasCredentials(boolean hasEnvKey) {
        return !baseUrl.isEmpty() && (protocol == LlmProtocol.ANTHROPIC_MESSAGES || hasEnvKey);
    }
}
