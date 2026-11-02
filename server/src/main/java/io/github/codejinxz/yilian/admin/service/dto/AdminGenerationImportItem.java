package io.github.codejinxz.yilian.admin.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 出题导入的单行请求；形状与前端「粘贴 AI 返回的 JSON」校验通过行一致。
 *
 * @param zh 中文原句
 * @param ref 参考译文
 * @param note 解析
 * @param level 难度标识，可空
 * @param tags 考点标签 1–5 个
 * @param chunks 语块序列
 * @param blank 挖空语块下标
 */
@Schema(description = "出题导入行")
public record AdminGenerationImportItem(
        @Schema(description = "中文原句") String zh,
        @Schema(description = "参考译文") String ref,
        @Schema(description = "解析") String note,
        @Schema(description = "难度标识，可空") String level,
        @Schema(description = "考点标签") List<String> tags,
        @Schema(description = "语块序列") List<Chunk> chunks,
        @Schema(description = "挖空语块下标") List<Integer> blank) {

    /**
     * 语块请求形状；index 缺省时服务端按数组位置补齐。
     *
     * @param index 0 基下标，可空
     * @param zh 语块中文
     * @param answers 可接受译法，answers[0] 为规范答案
     * @param tag 考点标签，可空
     * @param note 语块解析，可空
     */
    @Schema(description = "语块")
    public record Chunk(
            @Schema(description = "0 基下标，可空") Integer index,
            @Schema(description = "语块中文") String zh,
            @Schema(description = "可接受译法") List<String> answers,
            @Schema(description = "考点标签，可空") String tag,
            @Schema(description = "语块解析，可空") String note) {}
}
