package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.AttemptGrading;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 判分记录 Spring Data 仓库，只允许同包 JpaAttemptGradingDao 使用。 */
public interface AttemptGradingJpaRepository extends JpaRepository<AttemptGrading, Long> {

    /**
     * 某提交的判分记录（序号倒序）。
     *
     * @param attemptId 提交 id
     * @return 判分记录
     */
    List<AttemptGrading> findByAttemptIdOrderBySequenceDesc(String attemptId);

    /**
     * 最新判分。
     *
     * @param attemptId 提交 id
     * @return 判分记录
     */
    Optional<AttemptGrading> findFirstByAttemptIdOrderBySequenceDesc(String attemptId);

    /**
     * 首判（sequence 最小的本地判分行，错题池「首判口径」用）。
     *
     * @param attemptId 提交 id
     * @return 首判记录
     */
    Optional<AttemptGrading> findFirstByAttemptIdOrderBySequenceAsc(String attemptId);

    /**
     * 批量取多个提交的全部判分（最新行由 Dao 侧按 sequence 归并）。
     *
     * @param attemptIds 提交 id 集合
     * @return 判分记录
     */
    List<AttemptGrading> findByAttemptIdIn(java.util.Collection<String> attemptIds);

    /**
     * score&lt;100 的首判数（跨聚合 JPQL 联接，wrongCount 派生）。
     *
     * <p>限定 sequence=1（首判）：模型复核行不参与计数——错题口径跟随复核分会让 wrongCount 随一次复核凭空 +1。
     *
     * @param userId 用户 id
     * @param sentenceId 题目 ULID
     * @return 未全对次数
     */
    @Query(
            "select count(g) from AttemptGrading g join Attempt a on a.id = g.attemptId"
                    + " where a.userId = :userId and a.sentenceId = :sentenceId"
                    + " and g.sequence = 1 and g.score is not null and g.score < 100")
    long countWrong(@Param("userId") Long userId, @Param("sentenceId") String sentenceId);

    /**
     * 是否存在判分。
     *
     * @param attemptId 提交 id
     * @return 存在返回 true
     */
    boolean existsByAttemptId(String attemptId);
}
