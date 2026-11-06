package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.PromptVersion;
import io.github.codejinxz.yilian.practice.entity.PromptVersion.PromptLabel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Prompt 版本 Spring Data 仓库，只允许同包 JpaPromptVersionDao 使用。 */
public interface PromptVersionJpaRepository extends JpaRepository<PromptVersion, Long> {

    /**
     * 取某场景的当前 ACTIVE 版本（version 倒序取一，多行 ACTIVE 时取最高版本）。
     *
     * @param name 场景名
     * @param label 标签（恒 ACTIVE）
     * @return 版本行；未登记时为空
     */
    Optional<PromptVersion> findFirstByNameAndLabelOrderByVersionDesc(
            String name, PromptLabel label);
}
