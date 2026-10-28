package io.github.codejinxz.yilian.content.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 语块，对应 DATA-MODEL §6.1。
 *
 * @param index 0 基稳定下标
 * @param zh 语块中文
 * @param answers 可接受译法，{@code answers[0]} 为规范答案
 * @param tag 考点标签，可空
 * @param note 语块解析，可空
 */
@Schema(description = "语块：index 稳定且不可变，answers[0] 是规范答案。")
public record SentenceChunk(
        @Schema(description = "0 基稳定下标") int index,
        @Schema(description = "语块中文") String zh,
        @Schema(description = "可接受译法，至少一个") List<String> answers,
        @Schema(description = "考点标签，可空") String tag,
        @Schema(description = "语块解析，可空") String note) {}
