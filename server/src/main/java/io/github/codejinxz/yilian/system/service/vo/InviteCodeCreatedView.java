package io.github.codejinxz.yilian.system.service.vo;

import java.time.Instant;

/**
 * 管理端新生成的邀请码视图（API §8.1）。
 *
 * <p>明文 {@code code} 只在本响应中出现一次，库里只存 SHA-256 与前缀；前端负责一次性展示， 之后任何接口都拿不回明文。
 *
 * @param code 一次性明文邀请码，如 {@code YL-AB2C-DE3F}
 * @param codePrefix 展示前缀，与 {@code invite_codes.code_prefix} 一致，如 {@code YL-AB2C}
 * @param createdAt 创建时间（UTC）
 */
public record InviteCodeCreatedView(String code, String codePrefix, Instant createdAt) {}
