package io.github.codejinxz.yilian.practice.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/**
 * 管理端 LLM 配置更新请求体（017）。
 *
 * <p>选型说明（AGENTS §3.4 密钥不入库）：{@code enabled} 与 baseUrl/model 覆盖值存 {@code app_settings} 键 {@code
 * llm.tutor}；{@code apiKey} 只能经环境变量 {@code YILIAN_LLM_API_KEY} 配置，本请求体虽然声明该字段（保持 017
 * 计划的请求形状），但传非空值一律 40000 拒绝，绝不落库。
 *
 * @param enabled 是否开放答疑；true 且凭证未配置时拒绝
 * @param baseUrl 端点覆盖值；null/空白表示清除覆盖、回退环境变量
 * @param model 模型名覆盖值；null/空白表示清除覆盖、回退环境变量
 * @param apiKey 仅作形状兼容；非空即 40000，服务端不存储
 */
@Schema(description = "LLM 答疑配置更新请求")
public record LlmConfigUpdateRequest(
        @Schema(description = "是否开放答疑", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull(message = "enabled 不能为空")
                Boolean enabled,
        @Schema(description = "端点覆盖值；空表示回退环境变量 YILIAN_LLM_BASE_URL") String baseUrl,
        @Schema(description = "模型名覆盖值；空表示回退环境变量 YILIAN_LLM_MODEL") String model,
        @Schema(description = "不受理：API Key 只能经环境变量 YILIAN_LLM_API_KEY 配置") String apiKey) {}
