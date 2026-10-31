package io.github.codejinxz.yilian.content.dao;

import java.time.Instant;

/**
 * 管理端题目列表行（只读 DTO，MyBatis JOIN 直出）。
 *
 * <p>管理端看全量生命周期（含 DRAFT/RECYCLED），且回收站行也要带完整快照供恢复预览， 因此不走练习端的 tombstone 裁剪；列别名经驼峰映射对齐构造参数 （arg-name
 * 自动映射）。
 *
 * @param sentenceId 题目 ULID
 * @param bankId 所属题库 id（sentences.bank_id 为归属真值；快照行的 bank_id 是历史冗余）
 * @param version 银行级修订号
 * @param versionId 当前内容快照行 id
 * @param versionNo 单题内容版本号
 * @param status 生命周期
 * @param deletedAt 软删时间，null 表示未删
 * @param zh 中文原句
 * @param ref 参考译文
 * @param note 解析
 * @param chunks 语块 JSON 文本
 * @param blank 挖空下标 JSON 文本
 * @param tags 考点标签 JSON 文本
 * @param level 难度，可空
 */
public record AdminSentenceRow(
        String sentenceId,
        long bankId,
        long version,
        Long versionId,
        Integer versionNo,
        String status,
        Instant deletedAt,
        String zh,
        String ref,
        String note,
        String chunks,
        String blank,
        String tags,
        String level) {}
