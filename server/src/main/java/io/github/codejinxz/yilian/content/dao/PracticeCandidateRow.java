package io.github.codejinxz.yilian.content.dao;

/**
 * 练习候选查询行（只读 DTO，MyBatis JOIN 直出）。
 *
 * <p>列名经 {@code map-underscore-to-camel-case} 映射到本记录组件；{@code chunks/blank/tags} 保持 JSON
 * 文本原样返回，由服务层统一解析，避免在 Dao 层引入 Jackson 依赖。
 *
 * @param sentenceId 题目身份 ULID
 * @param bankId 所属题库 id
 * @param version 银行级修订号
 * @param versionId 当前内容快照行 id
 * @param versionNo 单题版本号
 * @param status 题目状态（JOIN 条件保证为 PUBLISHED）
 * @param zh 中文原句
 * @param ref 参考译文
 * @param note 解析
 * @param chunks 语块 JSON 文本
 * @param blank 挖空下标 JSON 文本
 * @param tags 考点标签 JSON 文本
 * @param level 等级，可为 null
 */
public record PracticeCandidateRow(
        String sentenceId,
        long bankId,
        long version,
        Long versionId,
        int versionNo,
        String status,
        String zh,
        String ref,
        String note,
        String chunks,
        String blank,
        String tags,
        String level) {}
