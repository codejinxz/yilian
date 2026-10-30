package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.ReviewDao;
import io.github.codejinxz.yilian.practice.entity.Review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 复习卡 Dao 的 JPA 实现。 */
@Repository
public class JpaReviewDao implements ReviewDao {

    /** Spring Data 仓储（包内可见）。 */
    interface ReviewJpaRepository extends JpaRepository<Review, Long> {

        /**
         * 用户在某题的卡。
         *
         * @param userId 用户 id
         * @param sentenceId 题目 ULID
         * @return 卡
         */
        Optional<Review> findByUserIdAndSentenceId(Long userId, String sentenceId);

        /**
         * 到期队列。
         *
         * @param userId 用户 id
         * @param state 状态
         * @param now 当前时间
         * @return 到期卡
         */
        List<Review> findByUserIdAndStateAndDueAtLessThanEqualOrderByDueAtAsc(
                Long userId, Review.ReviewState state, Instant now);

        /**
         * 用户全部 LEARNING 卡。
         *
         * @param userId 用户 id
         * @param state 状态
         * @return 学习中卡
         */
        List<Review> findByUserIdAndStateOrderByDueAtAsc(Long userId, Review.ReviewState state);

        /**
         * 到期计数。
         *
         * @param userId 用户 id
         * @param state 状态
         * @param now 当前时间
         * @return 数量
         */
        long countByUserIdAndStateAndDueAtLessThanEqual(
                Long userId, Review.ReviewState state, Instant now);

        /**
         * 用户全部卡。
         *
         * @param userId 用户 id
         * @return 全部卡
         */
        List<Review> findByUserId(Long userId);
    }

    private final ReviewJpaRepository reviews;

    /**
     * @param reviews Spring Data 仓储
     */
    public JpaReviewDao(ReviewJpaRepository reviews) {
        this.reviews = reviews;
    }

    @Override
    public Optional<Review> findByUserIdAndSentenceId(Long userId, String sentenceId) {
        return reviews.findByUserIdAndSentenceId(userId, sentenceId);
    }

    @Override
    public Optional<Review> findById(Long id) {
        return reviews.findById(id);
    }

    @Override
    public Review save(Review review) {
        return reviews.save(review);
    }

    @Override
    public List<Review> findDue(Long userId, Instant now) {
        return reviews.findByUserIdAndStateAndDueAtLessThanEqualOrderByDueAtAsc(
                userId, Review.ReviewState.LEARNING, now);
    }

    @Override
    public List<Review> findLearning(Long userId) {
        return reviews.findByUserIdAndStateOrderByDueAtAsc(userId, Review.ReviewState.LEARNING);
    }

    @Override
    public long countDue(Long userId, Instant now) {
        return reviews.countByUserIdAndStateAndDueAtLessThanEqual(
                userId, Review.ReviewState.LEARNING, now);
    }

    @Override
    public List<Review> findAllByUser(Long userId) {
        return reviews.findByUserId(userId);
    }
}
