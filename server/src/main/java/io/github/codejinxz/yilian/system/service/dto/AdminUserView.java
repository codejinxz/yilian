package io.github.codejinxz.yilian.system.service.dto;

import io.github.codejinxz.yilian.system.entity.AppUser;

import java.time.Instant;

/**
 * 管理端用户视图，对应 API §8.1。
 *
 * @param id 用户 id（字符串，避免前端精度丢失）
 * @param username 登录名
 * @param email 邮箱
 * @param role 角色
 * @param enabled 是否启用
 * @param deleted 是否软删
 * @param createdAt 创建时间
 * @param lastLoginAt 最近登录时间
 * @param disabledReason 停用原因
 */
public record AdminUserView(
        String id,
        String username,
        String email,
        String role,
        boolean enabled,
        boolean deleted,
        Instant createdAt,
        Instant lastLoginAt,
        String disabledReason) {

    /**
     * 从实体转视图。
     *
     * @param user 账号实体
     * @return 管理端视图
     */
    public static AdminUserView from(AppUser user) {
        return new AdminUserView(
                Long.toString(user.getId()),
                user.getUsername(),
                user.getEmail(),
                user.getRole().name(),
                user.isEnabled(),
                user.getDeletedAt() != null,
                user.getCreatedAt(),
                user.getLastLoginAt(),
                user.getDisabledReason());
    }
}
