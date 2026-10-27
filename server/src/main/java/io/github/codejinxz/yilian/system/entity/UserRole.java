package io.github.codejinxz.yilian.system.entity;

/**
 * 账号角色。
 *
 * <p>以字符串形式落库，新增角色只追加不改名；映射为 Spring Security 权限时会补上 {@code ROLE_} 前缀，因此 {@code ADMIN} 对应 {@code
 * ROLE_ADMIN}。
 */
public enum UserRole {
    /** 管理员：可访问 {@code /api/admin/**} 与后台审计功能。 */
    ADMIN,
    /** 普通用户：只能访问自己的练习与复习数据。 */
    USER
}
