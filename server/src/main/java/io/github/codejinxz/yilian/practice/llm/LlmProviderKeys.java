package io.github.codejinxz.yilian.practice.llm;

import io.github.codejinxz.yilian.practice.dao.LlmProviderKeyDao;
import io.github.codejinxz.yilian.practice.entity.LlmProviderKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.Optional;

/**
 * provider 条目密钥解析链与入库存取（027B）：<strong>DB 密文解密 &gt; {@code YILIAN_LLM_KEY_<大写ID>} env &gt;
 * 空</strong>（legacy 三件套的回退在 {@code LlmClientRegistry} 的目录空分支，与本链无关、语义不变）。
 *
 * <p>fail-safe 口径：DB 行存在但解密失败（主密钥被换/密文被篡改）时该条目密钥<strong>视为不存在</strong> （回退 env），只记 warn
 * 不抛——密钥坏了不该把目录读与答疑装配一并打挂；hasKey 口径 = 「本链能解析出密钥」（DB 可解密或 env 任一），与实际生效密钥恒一致。密钥不进任何缓存 （AGENTS
 * §3.8），每次解析现读现解；llm_provider_keys 量级 = 目录条目数 + 1 行答疑预留行（{@link #TUTOR_PROVIDER_ID}），代价可忽略。
 *
 * <p>明文只在 {@link #upsert} 加密前与 {@link #resolve} 解密后的内存短暂存在；本类不打印任何 含明文/密文的日志。
 */
@Component
public class LlmProviderKeys {

    /**
     * llm_provider_keys 的预留 id（028）：答疑 LLM 自身的密钥行——不属于任何目录条目，目录增删/激活切换 不会触碰本行；它只在 legacy 形态（目录空）的
     * tutor 覆盖轨里生效（见 {@code LlmClientRegistryImpl} 的覆盖轨装配：本行密文解密 &gt; {@code YILIAN_LLM_API_KEY}
     * env）。复用本表而非新建表是 028 预披露 裁决：加密基建（{@link ProviderKeyCrypto}）、last4 投影与状态指纹全部同源。
     */
    public static final String TUTOR_PROVIDER_ID = "tutor";

    private static final Logger log = LoggerFactory.getLogger(LlmProviderKeys.class);

    private final LlmProviderKeyDao keyDao;
    private final LlmProviderCatalog catalog;
    private final ProviderKeyCrypto crypto;
    private final Clock clock;

    /**
     * 注入依赖。
     *
     * @param keyDao 密钥行存取（llm_provider_keys）
     * @param catalog provider 目录（env 索引 {@code YILIAN_LLM_KEY_<大写ID>} 在其上）
     * @param crypto AES-256-GCM 加解密（UTC 时钟在构造器内部创建，用于 updated_at 留痕）
     */
    public LlmProviderKeys(
            LlmProviderKeyDao keyDao, LlmProviderCatalog catalog, ProviderKeyCrypto crypto) {
        this.keyDao = keyDao;
        this.catalog = catalog;
        this.crypto = crypto;
        this.clock = Clock.systemUTC();
    }

    /**
     * 解析条目当前生效密钥：DB 密文解密成功优先；DB 行缺失或解密失败回退 env。
     *
     * @param providerId 目录条目 id
     * @return 生效密钥；DB 与 env 都不可用时为空（anthropic 轨空密钥是合法态）
     */
    public Optional<String> resolve(String providerId) {
        Optional<String> fromDb = resolveDecrypted(providerId);
        if (fromDb.isPresent()) {
            return fromDb;
        }
        String envKey = catalog.envKey(providerId);
        return envKey.isEmpty() ? Optional.empty() : Optional.of(envKey);
    }

    /**
     * 只读 DB 轨：密文解密；行缺失或解密失败返回空（不回退 env，供调用方区分来源）。
     *
     * @param providerId 目录条目 id
     * @return 解密后的入库密钥；不可用时为空
     */
    public Optional<String> resolveDecrypted(String providerId) {
        Optional<LlmProviderKey> row = keyDao.findByProviderId(providerId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        try {
            String plaintext = crypto.decrypt(row.get().getSecretCipher());
            return plaintext.isEmpty() ? Optional.empty() : Optional.of(plaintext);
        } catch (ProviderKeyCrypto.ProviderKeyCryptoException exception) {
            log.warn(
                    "provider 入库密钥解密失败，按密钥不存在处理（回退 env）：providerId={}, error={}",
                    providerId,
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 条目是否有可用密钥（hasKey 口径 =「DB 可解密或 env 任一」，与 {@link #resolve} 恒一致）。
     *
     * @param providerId 目录条目 id
     * @return 有可用密钥返回 true
     */
    public boolean hasKey(String providerId) {
        return resolve(providerId).isPresent();
    }

    /**
     * 入库密钥行的尾 4 位辨识号（env 态没有本行 → 空，UI 不显示 last4）。
     *
     * @param providerId 目录条目 id
     * @return 录入时留痕的 last4；无 DB 行时为空
     */
    public Optional<String> storedLast4(String providerId) {
        return keyDao.findByProviderId(providerId).map(LlmProviderKey::getKeyLast4);
    }

    /**
     * 加密 upsert 条目密钥：明文先在内存加密，密文 + 尾 4 位落库；调用方须在管理端写事务内 （与目录写同事务，异常一并回滚）。
     *
     * @param providerId 目录条目 id
     * @param plaintext 明文 API Key（调用方保证非空白；不落日志/审计/响应）
     * @throws ProviderKeyCrypto.ProviderKeyCryptoException 加密失败时上抛（写路径显式失败， 元数据变更一并回滚，主密钥修复后重试）
     */
    @Transactional
    public void upsert(String providerId, String plaintext) {
        String cipher = crypto.encrypt(plaintext);
        keyDao.upsert(
                new LlmProviderKey(
                        providerId, cipher, ProviderKeyCrypto.last4(plaintext), clock.instant()));
    }

    /**
     * 删除条目密钥行（目录条目删除时同事务调用；无行幂等返回 false）。
     *
     * @param providerId 目录条目 id
     * @return 实际删除了一行返回 true
     */
    @Transactional
    public boolean delete(String providerId) {
        return keyDao.deleteByProviderId(providerId);
    }

    /**
     * 全部密钥行的状态指纹（id + updated_at 排序拼接）：注册表用它把「密钥变更」纳入客户端 缓存失效——目录 JSON 不变、只换密钥时也要换轨，否则 UI
     * 录入的新密钥要等下一次目录 变更才生效。字符串直接比对，不做哈希（行数极小，无碰撞风险）。
     *
     * @return 状态指纹（无任何行时为空串）
     */
    public String stateFingerprint() {
        return keyDao.findAllKeys().stream()
                .sorted(Comparator.comparing(LlmProviderKey::getProviderId))
                .map(key -> key.getProviderId() + ":" + key.getUpdatedAt().toEpochMilli())
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }
}
