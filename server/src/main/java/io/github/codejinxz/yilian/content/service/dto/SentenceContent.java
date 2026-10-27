package io.github.codejinxz.yilian.content.service.dto;

import java.util.List;

/**
 * 导入/改题时的题目内容，尚未绑定题目身份与版本号。
 *
 * @param externalKey 导入源 id，可为 null
 * @param zh 中文原句
 * @param ref 参考译文
 * @param note 解析
 * @param chunks 语块
 * @param blank 挖空下标
 * @param tags 考点标签
 * @param level 等级
 * @param source 来源
 * @param license 许可
 */
public record SentenceContent(
        String externalKey,
        String zh,
        String ref,
        String note,
        List<SentenceChunk> chunks,
        List<Integer> blank,
        List<String> tags,
        String level,
        String source,
        String license) {}
