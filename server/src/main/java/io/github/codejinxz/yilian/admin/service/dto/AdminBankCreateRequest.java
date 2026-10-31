package io.github.codejinxz.yilian.admin.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端新建题库请求体（API §8.8）。
 *
 * @param name 名称，1–128 字符
 * @param description 说明，最长 500 字符，可空
 * @param visibility 可见性 PUBLIC/PRIVATE
 */
public record AdminBankCreateRequest(
        @NotBlank(message = "请填写题库名称") @Size(max = 128, message = "题库名称最多 128 个字符") String name,
        @Size(max = 500, message = "说明最多 500 个字符") String description,
        @NotBlank(message = "visibility 必填") String visibility) {}
