package io.github.codejinxz.yilian.admin.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端软删题库请求体（API §8.8）：删除=状态流转进回收站，必须留痕。
 *
 * @param reason 删除原因，必填 1–255 字符
 */
public record AdminBankDeleteRequest(
        @NotBlank(message = "删除题库必须填写原因") @Size(max = 255, message = "原因最多 255 字符")
                String reason) {}
