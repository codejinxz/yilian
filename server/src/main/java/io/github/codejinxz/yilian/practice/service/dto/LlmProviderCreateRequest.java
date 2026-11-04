package io.github.codejinxz.yilian.practice.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理端新增 provider 请求体（API §8.15.2）：只收元数据，<strong>不含 apiKey</strong>——密钥按 id 走环境变量 {@code
 * YILIAN_LLM_KEY_<大写ID>} 索引（密钥不入库）。
 *
 * @param id 目录内唯一标识；小写字母/数字/连字符（env 变量名按连字符→下划线大写映射），创建后不可改
 * @param name 展示名
 * @param protocol 对话协议 wire 值：anthropic-messages | openai-chat | openai-responses
 * @param baseUrl 协议端点前缀（各协议拼接口径见 API §6.12）
 * @param model 模型名
 */
@Schema(description = "新增 LLM provider：只收元数据，apiKey 走 YILIAN_LLM_KEY_<大写ID> 环境变量")
public record LlmProviderCreateRequest(
        @Schema(
                        description = "目录内唯一 id（小写字母/数字/连字符），创建后不可改",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "id 不能为空")
                @Pattern(regexp = "[a-z0-9-]+", message = "id 只允许小写字母、数字与连字符")
                @Size(max = 64, message = "id 最长 64 字符")
                String id,
        @Schema(description = "展示名", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "name 不能为空")
                @Size(max = 128, message = "name 最长 128 字符")
                String name,
        @Schema(
                        description = "协议：anthropic-messages | openai-chat | openai-responses",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "protocol 不能为空")
                String protocol,
        @Schema(description = "协议端点前缀", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "baseUrl 不能为空")
                @Size(max = 512, message = "baseUrl 最长 512 字符")
                String baseUrl,
        @Schema(description = "模型名", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "model 不能为空")
                @Size(max = 128, message = "model 最长 128 字符")
                String model) {}
