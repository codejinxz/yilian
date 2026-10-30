package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 游客升级结果（API §4.8）。
 *
 * @param imported 新迁入条数
 * @param skipped 同 id 同载荷跳过条数
 * @param conflicts 冲突条数；任一冲突整体回滚并以 40903 返回，成功响应恒为 0
 */
@Schema(description = "游客升级结果：conflicts>0 不会出现在成功响应中")
public record GuestUpgradeView(
        @Schema(description = "迁入条数") int imported,
        @Schema(description = "跳过条数") int skipped,
        @Schema(description = "冲突条数") int conflicts) {}
