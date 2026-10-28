package io.github.codejinxz.yilian.content.service.dto;

import java.util.List;

/**
 * 题目视图，对应 API §1.6 / §5.3。
 *
 * @param id ULID
 * @param bankId 题库 id
 * @param version 银行级修订号
 * @param sentenceVersionId 当前内容快照 id，字符串化
 * @param questionVersion 单题内容版本号
 * @param status 生命周期
 * @param deleted 是否 tombstone
 * @param zh 中文原句；tombstone 时为 null
 * @param ref 参考译文；tombstone 时为 null
 * @param note 解析；tombstone 时为 null
 * @param chunks 语块；tombstone 时为 null
 * @param blank 挖空下标；tombstone 时为 null
 * @param tags 考点标签；tombstone 时为 null
 * @param level 等级；tombstone 时为 null
 */
public record SentenceDetail(
        String id,
        String bankId,
        long version,
        String sentenceVersionId,
        Integer questionVersion,
        String status,
        boolean deleted,
        String zh,
        String ref,
        String note,
        List<SentenceChunk> chunks,
        List<Integer> blank,
        List<String> tags,
        String level) {

    /**
     * 构造删除/停用 tombstone，只带 id/version/status。
     *
     * @param id 题目 id
     * @param bankId 题库 id
     * @param version 银行级修订号
     * @param status 当前状态
     * @return tombstone 视图
     */
    public static SentenceDetail tombstone(String id, long bankId, long version, String status) {
        return new SentenceDetail(
                id,
                Long.toString(bankId),
                version,
                null,
                null,
                status,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * 是否可用于练习：已发布且未删。
     *
     * @return 可练时为 true
     */
    public boolean practiceable() {
        return !deleted && "PUBLISHED".equals(status) && zh != null;
    }
}
