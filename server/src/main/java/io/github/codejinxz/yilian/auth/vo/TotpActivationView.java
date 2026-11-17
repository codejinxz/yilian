package io.github.codejinxz.yilian.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 两步验证第二步（activate）回执：10 个一次性恢复码明文只随本响应返回一次， 库内只存 BCrypt 哈希。
 *
 * @param recoveryCodes 一次性恢复码明文（10 个 8 位数字）
 */
@Schema(description = "两步验证激活回执：恢复码明文仅此一次展示，必须提示用户抄写保存。")
public record TotpActivationView(
        @Schema(
                        description = "一次性恢复码明文，共 10 个 8 位数字；仅本次响应可见",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                List<String> recoveryCodes) {}
