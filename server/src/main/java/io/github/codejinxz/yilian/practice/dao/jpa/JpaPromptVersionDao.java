package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.PromptVersionDao;
import io.github.codejinxz.yilian.practice.entity.PromptVersion;
import io.github.codejinxz.yilian.practice.entity.PromptVersion.PromptLabel;

import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Prompt 版本 Dao 的 JPA 实现。 */
@Repository
public class JpaPromptVersionDao implements PromptVersionDao {

    private final PromptVersionJpaRepository promptVersions;

    /**
     * @param promptVersions Prompt 版本 Spring Data 仓库
     */
    public JpaPromptVersionDao(PromptVersionJpaRepository promptVersions) {
        this.promptVersions = promptVersions;
    }

    @Override
    public Optional<PromptVersion> findActiveByName(String name) {
        return promptVersions.findFirstByNameAndLabelOrderByVersionDesc(name, PromptLabel.ACTIVE);
    }
}
