package io.github.codejinxz.yilian.content.service.dto;

import java.util.List;

/**
 * 增量拉取响应，对应 API §1.6。
 *
 * @param version 题库当前游标
 * @param items 本页题目
 * @param nextSinceVersion 下一页游标
 * @param hasMore 是否还有下一页
 */
public record SentencePage(
        long version, List<SentenceDetail> items, long nextSinceVersion, boolean hasMore) {}
