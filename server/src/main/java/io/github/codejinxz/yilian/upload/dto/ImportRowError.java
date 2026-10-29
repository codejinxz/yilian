package io.github.codejinxz.yilian.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 导入行级错误（DATA-MODEL §6.7 结构），同时用于落库 {@code error_codes} 与响应。
 *
 * @param code 稳定英文枚举
 * @param field 出错字段，可空
 * @param message 中文可展示原因
 */
@Schema(description = "导入行级错误")
public record ImportRowError(
        @Schema(description = "稳定英文错误码") String code,
        @Schema(description = "出错字段，可空") String field,
        @Schema(description = "中文可展示原因") String message) {}
