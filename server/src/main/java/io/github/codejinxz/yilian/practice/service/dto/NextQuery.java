package io.github.codejinxz.yilian.practice.service.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 取下一题的查询参数（API §6.1 D7 四参数 + 既有 bankId）。
 *
 * <p>归一化规则与本地引擎 handleLocalRequest / mock 逐值一致：非法或空白参数静默归一（不返回 400）， excludeIds 与旧的单题
 * excludeSentenceId 合并去重后统一按集合匹配。
 *
 * @param bankId 题库过滤，可空（DUE 召回不按题库过滤为既有服务端行为）
 * @param sentenceId 指定句重练，可空；命中且可练时优先返回该句
 * @param tag 考点筛选，可空
 * @param errors 只在错题里取下一题；仅 errors=1 为 true，其余取值一律 false
 * @param excludeIds 排除题目集合（合并 excludeSentenceId 后去重），恒非 null
 */
public record NextQuery(
        Long bankId, String sentenceId, String tag, boolean errors, List<String> excludeIds) {

    /**
     * 从原始查询串归一化：trim、去空、去重、errors 只认字面量 "1"。
     *
     * <p>与本地引擎保持一致的静默语义：脏值不报错，只是失效。excludeSentenceId 是旧的单题排除 参数，等价于 excludeIds 里的一个元素，这里合并进集合。
     *
     * @param bankId 题库 id，可空
     * @param sentenceId 指定句原始值，可空
     * @param tag 考点原始值，可空
     * @param errors 错题开关原始值（"1" 表示 true），可空
     * @param excludeSentenceId 旧单题排除原始值，可空
     * @param excludeIdsCsv 逗号分隔的多题排除原始值，可空
     * @return 归一化后的查询
     */
    public static NextQuery normalize(
            Long bankId,
            String sentenceId,
            String tag,
            String errors,
            String excludeSentenceId,
            String excludeIdsCsv) {
        Set<String> excluded = new LinkedHashSet<>();
        String trimmedExcludeSentence = trimToNull(excludeSentenceId);
        if (trimmedExcludeSentence != null) {
            excluded.add(trimmedExcludeSentence);
        }
        if (excludeIdsCsv != null) {
            for (String raw : excludeIdsCsv.split(",")) {
                String trimmed = raw.trim();
                if (!trimmed.isEmpty()) {
                    excluded.add(trimmed);
                }
            }
        }
        return new NextQuery(
                bankId,
                trimToNull(sentenceId),
                trimToNull(tag),
                // 与本地引擎 params.get("errors") === "1" 逐值一致：只认原始字面量，不做大小写/trim 变通。
                "1".equals(errors),
                List.copyOf(excluded));
    }

    /**
     * 判断题目是否被排除。
     *
     * @param sentenceId 题目 ULID
     * @return 在排除集合内返回 true
     */
    public boolean excludes(String sentenceId) {
        return excludeIds.contains(sentenceId);
    }

    /**
     * 排除题目数量（日志与测试辅助）。
     *
     * @return 数量
     */
    public int excludeCount() {
        return excludeIds.size();
    }

    /**
     * 供调试与结构化日志使用的紧凑描述；不携带题目内容。
     *
     * @return 单行描述
     */
    public String describe() {
        return "bankId="
                + bankId
                + " sentenceId="
                + (sentenceId == null ? "-" : sentenceId)
                + " tag="
                + (tag == null ? "-" : tag)
                + " errors="
                + errors
                + " excluded="
                + excludeCount();
    }

    /**
     * trim 后的字符串；空白视为未提供。
     *
     * @param raw 原始值，可空
     * @return 去除首尾空白后的值；无有效内容时为 null
     */
    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
