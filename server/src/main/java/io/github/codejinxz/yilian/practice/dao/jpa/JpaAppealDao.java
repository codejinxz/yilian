package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.AppealDao;

import io.github.codejinxz.yilian.practice.entity.Appeal;

import io.github.codejinxz.yilian.practice.entity.Appeal.AppealKind;

import org.springframework.stereotype.Repository;

import java.util.Collection;

import java.util.List;

import java.util.Optional;

/** 申诉/不认同 Dao 的 JPA 实现。 */
@Repository
public class JpaAppealDao implements AppealDao {

    private final AppealJpaRepository appeals;

    /**
     * @param appeals 申诉 Spring Data 仓库
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
