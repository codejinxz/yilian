package io.github.codejinxz.yilian.practice.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理端 LLM 配置更新请求体（028 起 legacy 形态全字段可编辑）。
 *
 * <p>字段语义：<strong>baseUrl/model 是状态型字段</strong>——非空白（trim 后）=落覆盖（存 app_settings 键 {@code
 * llm.tutor}），空串/缺省=清除该项覆盖（生效值回退 {@code YILIAN_LLM_*} env）；客户端应总是携带表单当前值， 只传 {@code enabled}
 * 的调用会同时清除既有覆盖。<strong>apiKey 是写-only 字段</strong>——非空白=AES-256-GCM 加密 upsert 到 llm_provider_keys 预留
 * id=tutor（028 复用 027B 基建），空/缺省=不变更；清除密钥用 {@code clearKey=true}（删该行）。{@code enabled} 语义不变：true
 * 且无可用客户端时 40000 拒绝（本次全部变更回滚）。
 *
 * @param enabled 是否开放答疑；true 且无可用客户端（凭证不齐/目录未激活/legacy 未配置）时 40000 拒绝
 * @param baseUrl 端点覆盖值：非空白=落覆盖（需 http(s):// 前缀、trim 后 ≤500 字符），空/缺省=清除覆盖
 * @param model 模型名覆盖值：非空白=落覆盖（trim 后 ≤2048 字符），空/缺省=清除覆盖
 * @param apiKey 可选 API 密钥（写-only）：非空白=加密 upsert（明文不回读/不落日志），空/缺省=不变更
 * @param clearKey 清除 tutor 密钥行：true=删除 llm_provider_keys 预留 id=tutor 的行（无行幂等）
 */
@Schema(
        description =
                "LLM 答疑配置更新请求：baseUrl/model 非空白=落覆盖、空/缺省=清除覆盖；apiKey 写-only（非空白=加密入库，"
                        + "空/缺省=不变更）；clearKey=true 删除 tutor 密钥行")
public record LlmConfigUpdateRequest(
        @Schema(description = "是否开放答疑", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull(message = "enabled 不能为空")
                Boolean enabled,
        @Schema(description = "端点覆盖值：非空白=落覆盖（http(s):// 前缀、≤500 字符），空/缺省=清除覆盖", nullable = true)
                @Size(max = 500, message = "baseUrl 最长 500 字符")
                String baseUrl,
        @Schema(description = "模型名覆盖值：非空白=落覆盖（≤2048 字符），空/缺省=清除覆盖", nullable = true)
                @Size(max = 2048, message = "model 最长 2048 字符")
                String model,
        @Schema(
                        description = "可选 API 密钥（写-only）：非空白=加密 upsert 到 tutor 预留行；空/缺省=不变更；不回显",
                        nullable = true)
                @Size(max = 2048, message = "apiKey 最长 2048 字符")
                String apiKey,
        @Schema(description = "清除 tutor 密钥行：true=删除（无行幂等）", nullable = true) Boolean clearKey) {

    /**
     * apiKey 是否要录入（写-only 口径：非空白才算提交了新密钥；空/缺省=保留现状）。
     *
     * @return 提交了非空白密钥返回 true
     */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
