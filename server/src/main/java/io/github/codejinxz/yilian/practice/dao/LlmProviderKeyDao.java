package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.LlmProviderKey;

import java.util.List;
import java.util.Optional;

/** provider 入库密钥 Dao：密钥投影表（llm_provider_keys）的读写口，业务代码只依赖本接口。 */
public interface LlmProviderKeyDao {

    /**
     * 按目录条目 id 取密钥行。
     *
     * @param providerId 目录条目 id
     * @return 密钥行；未录入时为空
     */
    Optional<LlmProviderKey> findByProviderId(String providerId);

    /**
     * 全量密钥行（密钥行数 = 目录条目数上限，量极小；供注册表缓存指纹与状态盘点， 不做分页）。
     *
     * @return 全部密钥行（存储顺序无保证，调用方自行排序）
     */
    List<LlmProviderKey> findAllKeys();

    /**
     * upsert 密钥行：同 provider_id 已存在则覆盖密文/辨识号/时间，否则插入（密钥投影表的 受控 UPDATE 语义，见实体类注释）。
     *
     * @param key 密钥行（密文已由 {@code ProviderKeyCrypto} 加密，调用方保证不含明文）
     * @return 落库后的行
     */
    LlmProviderKey upsert(LlmProviderKey key);

    /**
     * 删除密钥行（目录条目删除时同事务调用；无行返回 false，幂等）。
     *
     * @param providerId 目录条目 id
     * @return 实际删除了一行返回 true
     */
    boolean deleteByProviderId(String providerId);
}
