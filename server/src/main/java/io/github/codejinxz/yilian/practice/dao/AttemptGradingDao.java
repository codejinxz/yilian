package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.AttemptGrading;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 判分记录 Dao：最新序号查询与计数（wrongCount 派生）。 */
public interface AttemptGradingDao {

    /**
     * 保存判分记录。
     *
     * @param grading 判分记录
     * @return 持久化后的记录
     */
    AttemptGrading save(AttemptGrading grading);

    /**
     * 某提交的判分记录，序号倒序（最新在前）。
     *
     * @param attemptId 提交 id（UUID）
     * @return 判分记录
     */
    List<AttemptGrading> findByAttemptId(String attemptId);

    /**
     * 某提交的最新判分。
     *
     * @param attemptId 提交 id
     * @return 最新判分
     */
    Optional<AttemptGrading> findLatestByAttemptId(String attemptId);

    /**
     * 某提交的首判（sequence=1 的本地判分行）。
     *
     * <p>错题池等「首判口径」消费方必须读本行而不是最新行：模型复核会追加更高 sequence 的行， 若错题判定跟着复核分走，复核一次就会把题目移出/移入错题队列，
     * 与「复核不影响复习计划」的口径冲突。
     *
     * @param attemptId 提交 id
     * @return 首判记录
     */
    Optional<AttemptGrading> findFirstByAttemptId(String attemptId);

    /**
     * 批量取多个提交的最新判分（一条 IN 查询，analytics 装配用，替代逐条 N+1）。
     *
     * @param attemptIds 提交 id 集合
     * @return 每个提交的最新判分；无判分的提交不出现在结果里
     */
    List<AttemptGrading> findLatestByAttemptIds(Collection<String> attemptIds);

    /**
     * 用户在某题 score<100 的判分数（wrongCount 派生，排除 REVEAL null 分）。
     *
     * @param userId 用户 id
     * @param sentenceId 题目 ULID
     * @return 未全对次数
     */
    long countWrongByUserAndSentence(Long userId, String sentenceId);

    /**
     * 某提交是否存在判分。
     *
     * @param attemptId 提交 id
     * @return 存在返回 true
     */
    boolean existsByAttemptId(String attemptId);
}
