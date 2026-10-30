package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.AppealDao;
import io.github.codejinxz.yilian.practice.entity.Appeal;
import io.github.codejinxz.yilian.practice.entity.Appeal.AppealKind;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 申诉/不认同 Dao 的 JPA 实现。 */
@Repository
public class JpaAppealDao implements AppealDao {

    /** Spring Data 仓储（包内可见）。 */
    interface AppealJpaRepository extends JpaRepository<Appeal, Long> {

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

    private final AppealJpaRepository appeals;

    /**
     * @param appeals Spring Data 仓储
     */
    public JpaAppealDao(AppealJpaRepository appeals) {
        this.appeals = appeals;
    }

    @Override
    public Appeal save(Appeal appeal) {
        return appeals.save(appeal);
    }

    @Override
    public Optional<Appeal> findByAttemptAndKey(
            String attemptId, String annotationKey, AppealKind kind) {
        return appeals.findByAttemptIdAndAnnotationKeyAndKind(attemptId, annotationKey, kind);
    }

    @Override
    public List<Appeal> findDisputed(Long userId, Collection<String> sentenceIds) {
        return appeals.findByUserIdAndKindAndStatusAndSentenceIdIn(
                userId, AppealKind.DISPUTE, "ACCEPTED", sentenceIds);
    }

    @Override
    public Optional<Appeal> findById(Long id) {
        return appeals.findById(id);
    }
}
