package io.github.codejinxz.yilian.content.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.util.IdUtils;
import io.github.codejinxz.yilian.common.util.TextNormalizer;
import io.github.codejinxz.yilian.content.dao.BankDao;
import io.github.codejinxz.yilian.content.dao.SentenceDao;
import io.github.codejinxz.yilian.content.entity.ContentStatus;
import io.github.codejinxz.yilian.content.entity.Sentence;
import io.github.codejinxz.yilian.content.entity.SentenceBank;
import io.github.codejinxz.yilian.content.entity.SentenceVersion;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.ImportedSentence;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 导入落库实现：单事务逐行写「内容快照 → 题目身份 → 银行修订号」。
 *
 * <p>银行修订号按「每题递增」推进（P1-1）：同一批次内每行拿到独立 version， 增量游标 {@code version > sinceVersion} 分页永不重版本分组。指纹为
 * {@code SHA-256(normalize(zh)|normalize(ref)|规范化 chunks JSON)}，批内与库内 重复一律 {@link
 * ApiCode#IMPORT_NOT_COMMITTABLE} 整批回滚；commit 阶段的并发漏检 由唯一索引 {@code
 * uk_sentence_bank_active_fingerprint} 兜底。
 */
@Service
public class ContentImportServiceImpl implements ContentImportService {

    private final BankDao bankDao;

    private final SentenceDao sentenceDao;

    private final BankMetadataCache bankMetadataCache;

    private final ObjectMapper objectMapper;

    /**
     * 注入题库/题目 Dao、缓存失效装配与序列化器。
     *
     * @param bankDao 题库 Dao
     * @param sentenceDao 题目与快照 Dao
     * @param bankMetadataCache 匿名公开读缓存（导入改变句数视图，提交后失效）
     * @param objectMapper Jackson 映射器（chunks/blank/tags 序列化）
     */
    public ContentImportServiceImpl(
            BankDao bankDao,
            SentenceDao sentenceDao,
            BankMetadataCache bankMetadataCache,
            ObjectMapper objectMapper) {
        this.bankDao = bankDao;
        this.sentenceDao = sentenceDao;
        this.bankMetadataCache = bankMetadataCache;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public List<ImportedSentence> importSentences(
            long bankId,
            String importBatchId,
            List<SentenceContent> rows,
            Long actorId,
            Instant now) {
        if (rows.isEmpty()) {
            return List.of();
        }
        SentenceBank bank =
                bankDao.lockById(bankId)
                        .orElseThrow(() -> new BusinessException(ApiCode.BANK_NOT_FOUND));
        if (bank.getDeletedAt() != null
                || (bank.getStatus() != ContentStatus.DRAFT
                        && bank.getStatus() != ContentStatus.PUBLISHED)) {
            throw new BusinessException(ApiCode.BANK_NOT_FOUND);
        }

        List<ImportedSentence> results = new ArrayList<>(rows.size());
        Set<String> seenFingerprints = new HashSet<>(rows.size());
        for (SentenceContent row : rows) {
            String fingerprint = fingerprintOf(row);
            if (!seenFingerprints.add(fingerprint)) {
                throw new BusinessException(
                        ApiCode.IMPORT_NOT_COMMITTABLE, "批次内存在重复题目（指纹 " + fingerprint + "）");
            }
            if (sentenceDao.findActiveByFingerprint(bankId, fingerprint).isPresent()) {
                throw new BusinessException(
                        ApiCode.IMPORT_NOT_COMMITTABLE, "题库已存在相同题目（指纹 " + fingerprint + "）");
            }

            String chunksJson = writeJson(row.chunks());
            String blankJson = writeJson(row.blank());
            String tagsJson = writeJson(row.tags());
            String contentHash =
                    TextNormalizer.sha256(
                            row.zh()
                                    + "|"
                                    + row.ref()
                                    + "|"
                                    + row.note()
                                    + "|"
                                    + chunksJson
                                    + "|"
                                    + blankJson
                                    + "|"
                                    + tagsJson);

            Sentence sentence =
                    Sentence.create(
                            IdUtils.newUlid(),
                            bankId,
                            row.externalKey(),
                            fingerprint,
                            actorId,
                            now);
            sentenceDao.save(sentence);

            SentenceVersion version =
                    SentenceVersion.create(
                            sentence.getId(),
                            bankId,
                            1,
                            row.zh(),
                            row.ref(),
                            row.note(),
                            chunksJson,
                            blankJson,
                            tagsJson,
                            null,
                            null,
                            null,
                            contentHash,
                            importBatchId,
                            actorId,
                            now);
            SentenceVersion savedVersion = sentenceDao.saveVersion(version);

            bank.advanceVersion(bank.getVersion() + 1, actorId, now);
            sentence.pointToVersion(
                    savedVersion.getId(), 1, bank.getVersion(), fingerprint, actorId);
            // 导入落库的题目随批次提交直接进入可练态：显式发布（016 P2·02#03 之后 pointToVersion
            // 不再隐式把 DRAFT 翻成 PUBLISHED，发布是导入语义的一部分而非编辑副作用）。
            sentence.changeStatus(ContentStatus.PUBLISHED, bank.getVersion(), actorId, now);
            sentenceDao.save(sentence);

            results.add(new ImportedSentence(sentence.getId(), savedVersion.getId(), 1));
        }
        bankDao.save(bank);
        // 导入改变「已发布且未删」句数视图：提交后失效该库详情与列表缓存（013 AC-2 写后删）。
        bankMetadataCache.evictWrittenBanks(List.of(bankId));
        return results;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> findExistingFingerprints(long bankId, Collection<String> fingerprints) {
        if (fingerprints.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(sentenceDao.findActiveFingerprints(bankId, fingerprints));
    }

    @Override
    public List<String> fingerprintsOf(List<SentenceContent> rows) {
        return rows.stream().map(this::fingerprintOf).toList();
    }

    @Override
    @Transactional
    public int recycleImportedSentences(
            long bankId, Collection<String> sentenceIds, Long actorId, Instant now) {
        if (sentenceIds.isEmpty()) {
            return 0;
        }
        SentenceBank bank =
                bankDao.lockById(bankId)
                        .orElseThrow(() -> new BusinessException(ApiCode.BANK_NOT_FOUND));
        java.util.concurrent.atomic.AtomicInteger recycled =
                new java.util.concurrent.atomic.AtomicInteger();
        for (String sentenceId : sentenceIds) {
            sentenceDao
                    .findById(sentenceId)
                    // 归属与状态防御（016 P2·02#06）：本方法对 upload 模块开放且入参含 bankId 与
                    // sentenceIds 两组独立数据，V10 后批次行与题目间只剩逻辑外键——批次行错乱时
                    // 不校验归属就会用本库版本号改写他库题目并推进他库修订号；已软删（已回收）的题
                    // 跳过，避免二次回收重复推进版本号。越界/重复 id 静默忽略，回收数只统计实际发生者。
                    .filter(sentence -> sentence.getBankId() == bankId)
                    .filter(sentence -> sentence.getDeletedAt() == null)
                    .ifPresent(
                            sentence -> {
                                sentence.changeStatus(
                                        ContentStatus.RECYCLED,
                                        bank.getVersion() + 1,
                                        actorId,
                                        now);
                                bank.advanceVersion(bank.getVersion() + 1, actorId, now);
                                sentenceDao.save(sentence);
                                recycled.incrementAndGet();
                            });
        }
        bankDao.save(bank);
        // 回收改变句数视图：提交后失效该库缓存（与导入同口径）。
        bankMetadataCache.evictWrittenBanks(List.of(bankId));
        return recycled.get();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isImportableBank(long bankId) {
        return bankDao.findById(bankId)
                .filter(bank -> bank.getDeletedAt() == null)
                .filter(
                        bank ->
                                bank.getStatus() == ContentStatus.DRAFT
                                        || bank.getStatus() == ContentStatus.PUBLISHED)
                .isPresent();
    }

    /**
     * 计算行指纹：规范化 zh/ref 与 chunks 内文本后序列化，再取 SHA-256。
     *
     * @param row 导入行内容
     * @return 64 位十六进制指纹
     */
    private String fingerprintOf(SentenceContent row) {
        List<SentenceChunk> normalized =
                row.chunks().stream()
                        .map(
                                chunk ->
                                        new SentenceChunk(
                                                chunk.index(),
                                                TextNormalizer.normalizeChinese(chunk.zh()),
                                                chunk.answers().stream()
                                                        .map(TextNormalizer::normalizeAnswer)
                                                        .toList(),
                                                chunk.tag(),
                                                chunk.note()))
                        .toList();
        java.util.Map<String, Object> canonical = new java.util.LinkedHashMap<>();
        canonical.put("zh", TextNormalizer.normalizeChinese(row.zh()));
        canonical.put("ref", TextNormalizer.normalizeAnswer(row.ref()));
        canonical.put("chunks", normalized);
        String canonicalChunks = writeJson(canonical);
        return TextNormalizer.sha256(
                TextNormalizer.normalizeChinese(row.zh())
                        + "|"
                        + TextNormalizer.normalizeAnswer(row.ref())
                        + "|"
                        + canonicalChunks);
    }

    /**
     * 序列化为 JSON 文本；空集合写 null 以对齐列可空性。
     *
     * @param value 待序列化对象
     * @return JSON 文本；入参 null 时返回 null
     */
    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        return objectMapper.writeValueAsString(value);
    }
}
