package io.github.codejinxz.yilian.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 单行校验失败摘要。
 *
 * <p>只保留行号与服务端生成的简短原因，不回传整行原文：导入内容可能包含未发布题目， 也避免把 5MB 文件内容写进日志或响应。
 *
 * @param lineNumber 文件内物理行号，从 1 开始
 * @param reason 失败原因，例如「空行」「行长度超过 4000 字符」「非法 JSON 行」
 */
@Schema(description = "单行校验失败摘要，不含原始行内容。")
public record ImportRowError(
        @Schema(description = "文件内物理行号，从 1 开始", example = "12") long lineNumber,
        @Schema(description = "失败原因", example = "非法 JSON 行") String reason) {}
