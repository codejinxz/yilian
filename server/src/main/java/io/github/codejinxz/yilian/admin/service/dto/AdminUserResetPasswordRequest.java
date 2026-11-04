package io.github.codejinxz.yilian.admin.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端重置用户密码请求体（API §8.2b）。
 *
 * <p>重置是敏感动作，原因必填并随 {@code @OperationLog} 落审计；临时口令由服务端生成， 请求体不带任何口令字段。
 *
 * @param reason 审计原因，必填 1–255 字符
 */
public record AdminUserResetPasswordRequest(
        @NotBlank(message = "请填写原因") @Size(max = 255, message = "原因最多 255 字符") String reason) {}
