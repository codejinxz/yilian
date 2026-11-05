package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.LlmUsageDao;
import io.github.codejinxz.yilian.practice.entity.LlmUsage;

import org.springframework.stereotype.Repository;

/** LLM 用量账本 Dao 的 JPA 实现。 */
@Repository
public class JpaLlmUsageDao implements LlmUsageDao {

    private final LlmUsageJpaRepository usages;

    /**
     * @param usages 用量 Spring Data 仓库
     */
    public JpaLlmUsageDao(LlmUsageJpaRepository usages) {
        this.usages = usages;
    }

    @Override
    public LlmUsage save(LlmUsage usage) {
        return usages.save(usage);
    }
}
