package io.github.codejinxz.yilian.system.service.vo;

import java.time.Instant;

/**
 * 管理端邀请码列表项（API §8.1）。
 *
 * <p>哈希不可逆，列表只给展示前缀不给明文；使用者按需回查用户列表，此处只回填用户名。
 *
 * @param codePrefix 展示前缀，如 {@code YL-AB2C}
 * @param used 是否已被注册核销（一码一人）
 * @param usedBy 使用者用户名；未使用或使用者已不存在时为 null
 * @param createdAt 创建时间（UTC）
 */
public record InviteCodeAdminView(
        String codePrefix, boolean used, String usedBy, Instant createdAt) {}
