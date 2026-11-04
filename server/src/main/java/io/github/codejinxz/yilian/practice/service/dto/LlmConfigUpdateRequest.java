package io.github.codejinxz.yilian.practice.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/**
 * 管理端 LLM 配置更新请求体。
 *
 * <p>实际行为：本接口只受理 {@code enabled}；baseUrl/model/apiKey 三个字段仅为 请求形状兼容保留——<strong>传非空值一律 40000
 * 拒绝</strong>，端点与凭据只走环境变量 {@code YILIAN_LLM_*}（ 密钥不入库），服务端不接受在线修改，也不存在「覆盖值」。
 *
 * @param enabled 是否开放答疑；true 且环境变量凭证未配置完整时 40000 拒绝
 * @param baseUrl 不受理：非空即 40000，端点只能经 YILIAN_LLM_BASE_URL 配置
 * @param model 不受理：非空即 40000，模型名只能经 YILIAN_LLM_MODEL 配置
 * @param apiKey 不受理：非空即 40000，API Key 只能经环境变量 YILIAN_LLM_API_KEY 配置
 */
@Schema(description = "LLM 答疑配置更新请求：只受理 enabled；baseUrl/model/apiKey 非空即 40000（凭据与端点只走环境变量）")
public record LlmConfigUpdateRequest(
        @Schema(description = "是否开放答疑", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull(message = "enabled 不能为空")
                Boolean enabled,
        @Schema(description = "不受理：端点只能经环境变量 YILIAN_LLM_BASE_URL 配置，非空即 40000") String baseUrl,
        @Schema(description = "不受理：模型名只能经环境变量 YILIAN_LLM_MODEL 配置，非空即 40000") String model,
        @Schema(description = "不受理：API Key 只能经环境变量 YILIAN_LLM_API_KEY 配置，非空即 40000")
                String apiKey) {}
