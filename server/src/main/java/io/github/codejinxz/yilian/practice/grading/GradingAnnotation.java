package io.github.codejinxz.yilian.practice.grading;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 单条批注，结构与前端 {@code gradingAnnotationSchema} 逐字段一致（API §6.2）。
 *
 * @param key 批注键，填空为 {@code slot:{chunkIndex}}、整句为 {@code free:0}
 * @param chunkIndex 语块下标；整句批注恒为 0
 * @param tag 考点标签，缺失时为「未分类」
 * @param expected 可接受答案列表（保留原文，比较前另行归一化）
 * @param received 用户原始输入
 * @param correct 是否整块命中任一可接受答案
 * @param message 可解释文案；命中时为 null
 * @param minimalFix 最小修正建议（规范答案）；命中或无语块时为 null
 */
@Schema(description = "单条批注：key 定位批注对象，tag 是考点标签，message/minimalFix 面向用户")
public record GradingAnnotation(
        @Schema(description = "批注键：slot:{chunkIndex} 或 free:0") String key,
        @Schema(description = "语块下标，整句批注为 0") int chunkIndex,
        @Schema(description = "考点标签") String tag,
        @Schema(description = "可接受答案") List<String> expected,
        @Schema(description = "用户输入原文") String received,
        @Schema(description = "是否命中") boolean correct,
        @Schema(description = "批注文案，命中时为 null") String message,
        @Schema(description = "最小修正建议，命中时为 null") String minimalFix) {}
