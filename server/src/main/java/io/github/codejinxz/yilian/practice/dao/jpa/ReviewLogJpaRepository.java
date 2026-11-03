package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.ReviewLog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** FSRS 排程日志 Spring Data 仓库，只允许同包 JpaReviewLogDao 使用。 */
public interface ReviewLogJpaRepository extends JpaRepository<ReviewLog, Long> {

    /**
     * 按提交取日志。
     *
     * @param attemptId 提交 id
     * @return 日志
     */
    Optional<ReviewLog> findByAttemptId(String attemptId);

    /**
     * 用户全部日志（优化器训练集），id 升序 = 插入序。
     *
     * @param userId 用户 id
     * @return 日志列表
     */
    List<ReviewLog> findByUserIdOrderByIdAsc(Long userId);

    /**
     * 用户最近日志（id 倒序分页取前 N 条，训练样本上限用）。
     *
     * @param userId 用户 id
     * @param pageable 分页（size 即样本上限）
     * @return 日志列表（id 倒序）
     */
    List<ReviewLog> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
