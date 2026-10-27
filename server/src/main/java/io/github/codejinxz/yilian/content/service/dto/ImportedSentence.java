package io.github.codejinxz.yilian.content.service.dto;

/**
 * 题目写入结果，供导入批次行回填。
 *
 * @param sentenceId 题目 ULID
 * @param sentenceVersionId 内容快照 id
 * @param versionNo 单题版本号
 */
public record ImportedSentence(String sentenceId, long sentenceVersionId, int versionNo) {}
