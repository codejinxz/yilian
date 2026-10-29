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
 * <p>银行修订号按「每题递增」推进（P1-1）：同一批次内每行拿到独立 version，
 * 增量游标 {@code version > sinceVersion} 分页永不重版本分组。指纹为
 * {@code SHA-256(normalize(zh)|normalize(ref)|规范化 chunks JSON)}，批内与库内
 * 重复一律 {@link ApiCode#IMPORT_NOT_COMMITTABLE} 整批回滚；commit 阶段的并发漏检
 * 由唯一索引 {@code uk_sentence_bank_active_fingerprint} 兜底。
 */
@Service
public class ContentImportServiceImpl implements ContentImportService {

    private final BankDao bankDao;

    private final SentenceDao sentenceDao;

    private final ObjectMapper objectMapper;

    /**
     * 注入题库/题目 Dao 与序列化器。
     *
     * @param bankDao 题库 Dao
     * @param sentenceDao 题目与快照 Dao
     * @param objectMapper Jackson 映射器（chunks/blank/tags 序列化）
     */
    public ContentImportServiceImpl(
            BankDao bankDao, SentenceDao sentenceDao, ObjectMapper objectMapper) {
        this.bankDao = bankDao;
        this.sentenceDao = sentenceDao;
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
                bankDao
                        .lockById(bankId)
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
                            row.zh() + "|" + row.ref() + "|" + row.note() + "|"
                                    + chunksJson + "|" + blankJson + "|" + tagsJson);

            Sentence sentence =
                    Sentence.create(
                            IdUtils.newUlid(), bankId, row.externalKey(), fingerprint, actorId, now);
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
            sentenceDao.save(sentence);

            results.add(
                    new ImportedSentence(sentence.getId(), savedVersion.getId(), 1));
        }
        bankDao.save(bank);
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
