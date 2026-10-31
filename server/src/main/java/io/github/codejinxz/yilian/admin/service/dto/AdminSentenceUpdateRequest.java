package io.github.codejinxz.yilian.admin.service.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 管理端行内编辑题目请求体（API §8.8）。
 *
 * <p>字段缺省与显式 null 同义，均表示「不修改」。zh/level/tags/note 是<strong>版本字段</strong>（落在 {@code
 * sentence_versions} 不可变快照上，服务端新增版本行并回填指纹）；bankId/status 是元字段。 reason 可空，仅作审计（前端编辑弹窗固定传「编辑题目」）。
 *
 * @param zh 新中文，4–255 字符，null 表示不改
 * @param level 新难度，最长 32 字符，null 表示不改，空白清空
 * @param tags 新考点标签，null 表示不改，空白项剔除
 * @param note 新解析，最长 1000 字符，null 表示不改，空串清空
 * @param bankId 目标题库 id，数字字符串，null 表示不移动
 * @param status 新状态 DRAFT/PUBLISHED/DISABLED/RECYCLED/ARCHIVED，null 表示不改
 * @param reason 审计原因，最长 255 字符，可空
 */
public record AdminSentenceUpdateRequest(
        @Size(min = 4, max = 255, message = "中文需 4–255 个字符") String zh,
        @Size(max = 32, message = "难度最多 32 个字符") String level,
        List<String> tags,
        @Size(max = 1000, message = "解析最多 1000 个字符") String note,
        String bankId,
        String status,
        @Size(max = 255, message = "原因最多 255 字符") String reason) {}
