package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.Appeal;
import io.github.codejinxz.yilian.practice.entity.Appeal.AppealKind;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 申诉/不认同 Dao：overlay 排除的数据源。 */
public interface AppealDao {

    /**
     * 保存记录。
     *
     * @param appeal 记录
     * @return 持久化后的记录
     */
    Appeal save(Appeal appeal);

    /**
     * 按提交与批注取记录（uk 唯一性校验）。
     *
     * @param attemptId 提交 id
     * @param annotationKey 批注 key
     * @param kind 类型
     * @return 记录
     */
    Optional<Appeal> findByAttemptAndKey(String attemptId, String annotationKey, AppealKind kind);

    /**
     * 用户在若干题目上的 ACCEPTED DISPUTE（overlay 排除）。
     *
     * @param userId 用户 id
     * @param sentenceIds 题目 id 集合
     * @return 记录列表
     */
    List<Appeal> findDisputed(Long userId, Collection<String> sentenceIds);

    /**
     * 按 id 取记录。
     *
     * @param id 记录 id
     * @return 记录
     */
    Optional<Appeal> findById(Long id);
}
