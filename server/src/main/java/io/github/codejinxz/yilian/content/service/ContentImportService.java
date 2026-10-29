package io.github.codejinxz.yilian.content.service;

import io.github.codejinxz.yilian.content.service.dto.ImportedSentence;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 导入落库服务（content 模块对 upload 模块的唯一接缝，AGENTS §3.2 跨模块只依赖 service 接口）。
 *
 * <p>commit 在单事务内逐行写内容快照与题目身份，并按「每题递增」推进银行级修订号
 * （切片设计 §4.5 P1-1：同批次每行拿独立 version，增量游标永不重版本分组）。
 */
public interface ContentImportService {

    /**
     * 把合法导入行落库为 PUBLISHED 题目；指纹批内或库内重复时抛业务异常，整批回滚。
     *
     * @param bankId 目标题库
     * @param importBatchId 来源批次 id，写入内容快照
     * @param rows 已通过业务校验的行
     * @param actorId 操作管理员 id
     * @param now 统一时钟
     * @return 逐行落库结果（题目 id、快照 id、版本号恒为 1）
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 题库不可用或指纹重复（IMPORT_NOT_COMMITTABLE）
     */
    List<ImportedSentence> importSentences(
            long bankId, String importBatchId, List<SentenceContent> rows, Long actorId, Instant now);

    /**
     * 批量查询题库内已存在的活跃指纹（preview 去重用，只读）。
     *
     * @param bankId 题库 id
     * @param fingerprints 候选指纹集合
     * @return 命中的指纹子集
     */
    Set<String> findExistingFingerprints(long bankId, Collection<String> fingerprints);

    /**
     * 计算行指纹（与落库时完全一致的纯函数，preview 去重用）。
     *
     * @param rows 导入行内容
     * @return 按入参顺序的指纹列表
     */
    List<String> fingerprintsOf(List<SentenceContent> rows);

    /**
     * 回滚已导入题目：逐题置 RECYCLED（deleted_at/by 回写）并每题递增银行修订号，
     * 使增量流下发 tombstone；单事务。题目不存在时跳过（幂等）。
     *
     * @param bankId 题库 id
     * @param sentenceIds 待回收的题目 id
     * @param actorId 操作管理员 id
     * @param now 统一时钟
     * @return 实际回收的题目数
     */
    int recycleImportedSentences(long bankId, Collection<String> sentenceIds, Long actorId, Instant now);

    /**
     * 校验题库可导入（存在、未软删、状态为 DRAFT/PUBLISHED）。
     *
     * @param bankId 题库 id
     * @return 可导入返回 true
     */
    boolean isImportableBank(long bankId);
}
