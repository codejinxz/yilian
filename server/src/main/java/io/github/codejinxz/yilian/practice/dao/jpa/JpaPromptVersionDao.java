package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.PromptVersionDao;
import io.github.codejinxz.yilian.practice.entity.PromptVersion;
import io.github.codejinxz.yilian.practice.entity.PromptVersion.PromptLabel;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    @Override
    public Optional<PromptVersion> findById(long id) {
        return promptVersions.findById(id);
    }

    @Override
    public Optional<Integer> findMaxVersion(String name) {
        return promptVersions.findMaxVersion(name);
    }

    @Override
    public List<PromptVersion> search(String name, PromptLabel label, int offset, int limit) {
        return promptVersions.searchRows(name, label, PageRequest.of(offset / limit, limit));
    }

    @Override
    public long countSearch(String name, PromptLabel label) {
        return promptVersions.countRows(name, label);
    }

    @Override
    public PromptVersion save(PromptVersion version) {
        return promptVersions.save(version);
    }

    @Override
    public int updateLabel(long id, PromptLabel label) {
        return promptVersions.updateLabel(id, label);
    }
}
