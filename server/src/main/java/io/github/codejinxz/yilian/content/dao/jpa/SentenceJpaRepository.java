package io.github.codejinxz.yilian.content.dao.jpa;

import io.github.codejinxz.yilian.content.entity.Sentence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     * <p>命中生成列 {@code active_fingerprint}（未删时 = fingerprint，已删恒为 NULL）而非基础列， 让导入去重探测走上唯一索引 {@code
     * uk_sentence_bank_active_fingerprint}（db-review P1）； 生成列等值匹配已隐含 {@code deleted_at IS
     * NULL}，无需再过滤。
     *
     * @param bankId 题库 id
     * @param fingerprint 指纹
     * @return 题目
     */
    @Query(
            value =
                    "select s.* from sentences s where s.bank_id = :bankId"
                            + " and s.active_fingerprint = :fingerprint",
            nativeQuery = true)
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
     * 批量查询题库内未删题目命中的活跃指纹。
     *
     * <p>同 {@link #findActiveByFingerprint}：IN 匹配生成列 {@code active_fingerprint}， 走 {@code
     * uk_sentence_bank_active_fingerprint} 的 (bank_id, active_fingerprint) 范围扫描。
     *
     * @param bankId 题库 id
     * @param fingerprints 候选指纹集合
     * @return 命中的指纹
     */
    @Query(
            value =
                    "select s.fingerprint from sentences s where s.bank_id = :bankId"
                            + " and s.active_fingerprint in (:fingerprints)",
            nativeQuery = true)
    List<String> findActiveFingerprints(
            @Param("bankId") long bankId, @Param("fingerprints") Collection<String> fingerprints);

    /**
     * 统计题库内已发布且未删的题目数。
     *
     * @param bankId 题库 id
     * @return 数量
     */
    long countByBankIdAndStatusAndDeletedAtIsNull(
            long bankId, io.github.codejinxz.yilian.content.entity.ContentStatus status);
}
