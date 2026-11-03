package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.ReviewDao;
import io.github.codejinxz.yilian.practice.entity.Review;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 复习卡 Dao 的 JPA 实现。 */
@Repository
public class JpaReviewDao implements ReviewDao {

    private final ReviewJpaRepository reviews;

    /**
     * @param reviews 复习卡 Spring Data 仓库
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
    public Review saveAndFlush(Review review) {
        return reviews.saveAndFlush(review);
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
