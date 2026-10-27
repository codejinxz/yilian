package io.github.codejinxz.yilian.content.dao;

import io.github.codejinxz.yilian.content.entity.Sentence;
import io.github.codejinxz.yilian.content.entity.SentenceVersion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 题目与内容快照 Dao；快照只新增，不修改。 */
public interface SentenceDao {

    /**
     * 保存题目身份。
     *
     * @param sentence 题目
     * @return 持久化后的实体
     */
    Sentence save(Sentence sentence);

    /**
     * 按 id 查题目。
     *
     * @param id ULID
     * @return 题目
     */
    Optional<Sentence> findById(String id);

    /**
     * 按题库与外部键查题目。
     *
     * @param bankId 题库 id
     * @param externalKey 外部键
     * @return 题目
     */
    Optional<Sentence> findByExternalKey(long bankId, String externalKey);

    /**
     * 按指纹查未删题目。
     *
     * @param bankId 题库 id
     * @param fingerprint 指纹
     * @return 题目
     */
    Optional<Sentence> findActiveByFingerprint(long bankId, String fingerprint);

    /**
     * 增量拉取。
     *
     * @param bankId 题库 id
     * @param sinceVersion 起始版本
     * @param limit 最多条数
     * @return 题目列表
     */
    List<Sentence> findChangedSince(long bankId, long sinceVersion, int limit);

    /**
     * 管理端题目分页。
     *
     * @param bankId 题库 id
     * @param pageable 分页
     * @return 分页结果
     */
    Page<Sentence> findPageByBank(long bankId, Pageable pageable);

    /**
     * 练习候选。
     *
     * @param bankId 题库 id，可为 null
     * @param excludeSentenceId 排除题目
     * @param limit 最多条数
     * @return 候选题目
     */
    List<Sentence> findPracticeCandidates(Long bankId, String excludeSentenceId, int limit);

    /**
     * 保存内容快照。
     *
     * @param version 快照
     * @return 持久化后的实体
     */
    SentenceVersion saveVersion(SentenceVersion version);

    /**
     * 按 id 批量取快照。
     *
     * @param ids 版本行 id
     * @return 快照列表
     */
    List<SentenceVersion> findVersionsByIds(Collection<Long> ids);

    /**
     * 取单题最新版本号。
     *
     * @param sentenceId 题目 id
     * @return 最新快照
     */
    Optional<SentenceVersion> findLatestVersion(String sentenceId);
}
