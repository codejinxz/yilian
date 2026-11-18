package io.github.codejinxz.yilian.practice.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端更新 provider 请求体（API §8.15.3）：id 走路径、不可改；元数据字段语义同 {@link LlmProviderCreateRequest}。apiKey
 * 是<strong>写-only 字段</strong>：空/缺省=不变更（保留已入库 密钥与 env 态），非空白=加密 upsert 覆盖（明文永不入库/入日志/入审计）；响应侧永不回显明文。
 *
 * @param name 展示名
 * @param protocol 对话协议 wire 值
 * @param baseUrl 协议端点前缀
 * @param model 模型名
 * @param apiKey 可选 API 密钥（写-only）：空/缺省=不变更，非空白=加密 upsert 覆盖
 */
@Schema(description = "更新 LLM provider：id 走路径不可改；apiKey 留空=不变更，填入=加密覆盖")
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
                String model,
        @Schema(description = "可选 API 密钥（写-only）：空/缺省=不变更，非空白=加密 upsert 覆盖；不回显", nullable = true)
                @Size(max = 2048, message = "apiKey 最长 2048 字符")
                String apiKey) {

    /**
     * apiKey 是否要变更（写-only 口径：非空白才算提交了新密钥；空/缺省=保留现状）。
     *
     * @return 提交了非空白密钥返回 true
     */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
