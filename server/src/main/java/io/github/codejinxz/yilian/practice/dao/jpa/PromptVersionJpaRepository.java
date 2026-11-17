package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.PromptVersion;
import io.github.codejinxz.yilian.practice.entity.PromptVersion.PromptLabel;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /**
     * 分页检索：name/label 可选过滤（IS NULL 短路），按 name 升序 + version 倒序稳定排列。
     *
     * @param name 场景名过滤，null 不过滤
     * @param label 标签过滤，null 不过滤
     * @param pageable 分页（offset/limit 由调用方换算）
     * @return 当前页版本行
     */
    @Query(
            "SELECT p FROM PromptVersion p"
                    + " WHERE (:name IS NULL OR p.name = :name)"
                    + " AND (:label IS NULL OR p.label = :label)"
                    + " ORDER BY p.name ASC, p.version DESC")
    List<PromptVersion> searchRows(
            @Param("name") String name, @Param("label") PromptLabel label, Pageable pageable);

    /**
     * 分页检索总数（与 {@link #searchRows} 同过滤口径）。
     *
     * @param name 场景名过滤，null 不过滤
     * @param label 标签过滤，null 不过滤
     * @return 总行数
     */
    @Query(
            "SELECT COUNT(p) FROM PromptVersion p"
                    + " WHERE (:name IS NULL OR p.name = :name)"
                    + " AND (:label IS NULL OR p.label = :label)")
    long countRows(@Param("name") String name, @Param("label") PromptLabel label);

    /**
     * 取某场景现存最大版本号。
     *
     * @param name 场景名
     * @return 最大版本号；未登记时为空
     */
    @Query("SELECT MAX(p.version) FROM PromptVersion p WHERE p.name = :name")
    Optional<Integer> findMaxVersion(@Param("name") String name);

    /**
     * 定向 UPDATE 改标签（激活/降级唯一写入口）：SET 面只有 label 一列，行内容三列 （name/version/content）在 SQL
     * 面不可达——append-only 行内容不改写由 UPDATE 面约束兜底。
     *
     * <p>{@code clearAutomatically = true}：JPQL 批量 UPDATE 绕过持久化上下文，不清缓存时同事务内的 后续读取会命中一级缓存里的旧标签实体
     * （激活返回值读到 CANDIDATE 的真实事故），清空让回读走库。
     *
     * @param id 版本行 id
     * @param label 目标标签
     * @return 受影响行数（0=行不存在）
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PromptVersion p SET p.label = :label WHERE p.id = :id")
    int updateLabel(@Param("id") long id, @Param("label") PromptLabel label);
}
