package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.LlmProviderKeyDao;
import io.github.codejinxz.yilian.practice.entity.LlmProviderKey;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** provider 入库密钥 Dao 的 JPA 实现：主键即目录条目 id，upsert 走「先查后写」。 */
@Repository
public class JpaLlmProviderKeyDao implements LlmProviderKeyDao {

    private final LlmProviderKeyJpaRepository providerKeys;

    /**
     * @param providerKeys 密钥行 Spring Data 仓库
     */
    public JpaLlmProviderKeyDao(LlmProviderKeyJpaRepository providerKeys) {
        this.providerKeys = providerKeys;
    }

    @Override
    public Optional<LlmProviderKey> findByProviderId(String providerId) {
        return providerKeys.findById(providerId);
    }

    @Override
    public List<LlmProviderKey> findAllKeys() {
        return providerKeys.findAll();
    }

    /**
     * upsert（见 Dao 接口）：方法级事务保证「先查到的既有行在本事务内保持受管」——脏检查在 提交时 flush 出 UPDATE；若不加事务，findById
     * 的只读事务结束后实体已分离，applyUpdate 的修改会静默丢失（集成测试抓到过：第二次 upsert 后密文/时间不变）。
     */
    @Override
    @Transactional
    public LlmProviderKey upsert(LlmProviderKey key) {
        providerKeys
                .findById(key.getProviderId())
                .ifPresentOrElse(
                        existing ->
                                existing.applyUpdate(
                                        key.getSecretCipher(),
                                        key.getKeyLast4(),
                                        key.getUpdatedAt()),
                        () -> providerKeys.save(key));
        return key;
    }

    /** exists + delete 在同一事务内（避免两调用之间并发插入造成误判）。 */
    @Override
    @Transactional
    public boolean deleteByProviderId(String providerId) {
        if (!providerKeys.existsById(providerId)) {
            return false;
        }
        providerKeys.deleteById(providerId);
        return true;
    }
}
