package io.github.codejinxz.yilian.content.dao.jpa;

import io.github.codejinxz.yilian.content.entity.Sentence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 题目身份 Spring Data 仓库。 */
public interface SentenceJpaRepository extends JpaRepository<Sentence, String> {

    /**
     * 按题库与导入源 id 查题目。
     *
     * @param bankId 题库 id
     * @param externalKey 导入源 id
     * @return 题目
     */
    Optional<Sentence> findByBankIdAndExternalKey(long bankId, String externalKey);

    /**
     * 按题库与内容指纹查未删题目，用于精确重复拦截。
     *
     * @param bankId 题库 id
     * @param fingerprint 指纹
     * @return 题目
     */
    @Query(
            "select s from Sentence s where s.bankId = :bankId and s.fingerprint = :fingerprint"
                    + " and s.deletedAt is null")
    Optional<Sentence> findActiveByFingerprint(
            @Param("bankId") long bankId, @Param("fingerprint") String fingerprint);

    /**
     * 增量拉取：按 version 升序，包含 tombstone。
     *
     * @param bankId 题库 id
     * @param sinceVersion 起始版本，不含
     * @param pageable 分页（limit）
     * @return 题目列表
     */
    @Query(
            "select s from Sentence s where s.bankId = :bankId and s.version > :sinceVersion"
                    + " order by s.version asc, s.id asc")
    List<Sentence> findChangedSince(
            @Param("bankId") long bankId,
            @Param("sinceVersion") long sinceVersion,
            Pageable pageable);

    /**
     * 统计题库内已发布且未删的题目数。
     *
     * @param bankId 题库 id
     * @return 数量
     */
    long countByBankIdAndStatusAndDeletedAtIsNull(
            long bankId, io.github.codejinxz.yilian.content.entity.ContentStatus status);
}
