package io.github.codejinxz.yilian.admin.service.dto;

import jakarta.validation.constraints.Size;

/**
 * 管理端部分更新题库请求体（API §8.8）。
 *
 * <p>字段缺省与显式 null 同义，均表示「不修改」（record 反序列化无法区分两者）；需要清空说明时传空串。 reason 供审计：{@code
 * updateAdminBankStatus} 带 reason，通用更新可以不带。
 *
 * @param name 新名称，1–128 字符，null 表示不改
 * @param description 新说明，最长 500 字符，null 表示不改，空串清空
 * @param visibility 新可见性 PUBLIC/PRIVATE，null 表示不改
 * @param status 新状态 DRAFT/PUBLISHED/DISABLED/RECYCLED/ARCHIVED，null 表示不改
 * @param reason 审计原因，最长 255 字符，可空
 */
public record AdminBankUpdateRequest(
        @Size(min = 1, max = 128, message = "题库名称需 1–128 个字符") String name,
        @Size(max = 500, message = "说明最多 500 个字符") String description,
        String visibility,
        String status,
        @Size(max = 255, message = "原因最多 255 字符") String reason) {}
