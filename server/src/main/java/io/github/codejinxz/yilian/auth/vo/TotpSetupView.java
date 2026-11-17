package io.github.codejinxz.yilian.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 两步验证第一步（setup）回执：Base32 密钥与 otpauth URI 文本各展示一次，密文已落库但 启用开关仍未打开；重复 setup 会作废旧密钥。
 *
 * <p>密钥明文只在本次响应出现一次，之后任何接口都拿不回；本批不渲染二维码（渲染需前端 新依赖），验证器 App 走「手动录入密钥」或复制 otpauth URI 全文。
 *
 * @param secret Base32 编码的 TOTP 密钥（32 字符）
 * @param otpauthUri otpauth:// 文本，验证器 App 可直接识别
 */
@Schema(description = "两步验证注册第一步回执：密钥与 otpauth URI 仅此一次展示。")
public record TotpSetupView(
        @Schema(
                        description = "Base32 密钥（32 字符），验证器 App 手动录入用",
                        example = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String secret,
        @Schema(
                        description = "otpauth://totp/Yilian:{username}?… 全文，复制到验证器 App 或后续渲染二维码用",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String otpauthUri) {}
