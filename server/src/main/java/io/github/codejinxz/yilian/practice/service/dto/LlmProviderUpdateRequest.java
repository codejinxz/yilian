package io.github.codejinxz.yilian.practice.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端更新 provider 请求体（022，API §8.15.3）：id 走路径、不可改；字段语义同 {@link LlmProviderCreateRequest}， 密钥同样不入请求体。
 *
 * @param name 展示名
 * @param protocol 对话协议 wire 值
 * @param baseUrl 协议端点前缀
 * @param model 模型名
 */
@Schema(description = "更新 LLM provider：id 走路径不可改；apiKey 走 YILIAN_LLM_KEY_<大写ID> 环境变量")
public record LlmProviderUpdateRequest(
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
