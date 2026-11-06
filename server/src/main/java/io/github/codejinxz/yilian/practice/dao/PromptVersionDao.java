package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.PromptVersion;

import java.util.Optional;

/** Prompt 版本 Dao：PromptRegistry 的数据源（按场景名取 ACTIVE 版本）。 */
public interface PromptVersionDao {

    /**
     * 取某场景的当前 ACTIVE 版本（同 name 多行 ACTIVE 时取 version 最高者，注册表不做跨行约束）。
     *
     * @param name 场景名（ask / review / appeal）
     * @return ACTIVE 版本；未登记时为空
     */
    Optional<PromptVersion> findActiveByName(String name);
}
