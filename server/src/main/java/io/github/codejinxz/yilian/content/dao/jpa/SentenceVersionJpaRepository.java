package io.github.codejinxz.yilian.content.dao.jpa;

import io.github.codejinxz.yilian.content.entity.SentenceVersion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 内容快照 Spring Data 仓库；快照只增不改。 */
public interface SentenceVersionJpaRepository extends JpaRepository<SentenceVersion, Long> {

    /**
     * 按 id 批量取当前版本快照。
     *
     * @param ids 版本行 id
     * @return 版本列表
     */
    List<SentenceVersion> findByIdIn(Collection<Long> ids);

    /**
     * 取单题最新版本号，用于生成下一个 version_no。
     *
     * @param sentenceId 题目 id
     * @return 最新版本
     */
    Optional<SentenceVersion> findFirstBySentenceIdOrderByVersionNoDesc(String sentenceId);
}
