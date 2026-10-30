package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.Appeal;

import io.github.codejinxz.yilian.practice.entity.Appeal.AppealKind;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

import java.util.List;

import java.util.Optional;

/** 申诉/不认同 Spring Data 仓库，只允许同包 JpaAppealDao 使用。 */
public interface AppealJpaRepository extends JpaRepository<Appeal, Long> {

    /**
     * 按提交与批注取记录。
     *
     * @param attemptId 提交 id
     * @param annotationKey 批注 key
     * @param kind 类型
     * @return 记录
     */
    Optional<Appeal> findByAttemptIdAndAnnotationKeyAndKind(
            String attemptId, String annotationKey, AppealKind kind);

    /**
     * 用户在题目集合上的指定类型记录。
     *
     * @param userId 用户 id
     * @param kind 类型
     * @param status 状态
     * @param sentenceIds 题目集合
     * @return 记录列表
     */
    List<Appeal> findByUserIdAndKindAndStatusAndSentenceIdIn(
            Long userId, AppealKind kind, String status, Collection<String> sentenceIds);

    /**
     * 用户全部指定类型记录。
     *
     * @param userId 用户 id
     * @param kind 类型
     * @param status 状态
     * @return 记录列表
     */
    List<Appeal> findByUserIdAndKindAndStatus(Long userId, AppealKind kind, String status);
}
