package io.github.codejinxz.yilian.common.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;

/**
 * 无状态 JWT 请求的认证主体。
 *
 * <p>由 {@link JwtAuthenticationFilter} 校验 access token 后用其中的 {@code sub}/{@code username}/ {@code
 * role} 声明构造，只存活于本次请求的安全上下文，不写入 Spring Session，因此无需与数据库 实体保持同步；账号停用、改密等变更要等 refresh 换发新 token 才会生效。
 */
public final class YilianUserPrincipal implements UserDetails, Serializable {

    @Serial private static final long serialVersionUID = 1L;

    private final long id;
    private final String username;
    private final String role;
    private final boolean enabled;
    private final boolean passwordChanged;

    private YilianUserPrincipal(
            long id, String username, String role, boolean enabled, boolean passwordChanged) {
        this.id = id;
        this.username = username;
        this.role = role;
        this.enabled = enabled;
        this.passwordChanged = passwordChanged;
    }

    /**
     * 由认证服务用只读账号视图构造，security 模块不反向依赖 system 实体。
     *
     * @param id 用户 id，对应 JWT 的 {@code sub}
     * @param username 登录名
     * @param role 角色名，不带 {@code ROLE_} 前缀，授权时由 {@link #getAuthorities()} 补上
     * @param enabled 账号是否启用；停用账号不应签发新 token
     * @param passwordChanged 是否已改初始密码
     * @return 不可变 principal
     */
    public static YilianUserPrincipal of(
            long id, String username, String role, boolean enabled, boolean passwordChanged) {
        return new YilianUserPrincipal(id, username, role, enabled, passwordChanged);
    }

    /**
     * 兼容旧调用：缺少改密声明时按已完成处理，避免历史 token 被误判为首次登录。
     *
     * @param id 用户 id
     * @param username 登录名
     * @param role 角色名
     * @param enabled 是否启用
     * @return 不可变 principal
     */
    public static YilianUserPrincipal of(long id, String username, String role, boolean enabled) {
        return of(id, username, role, enabled, true);
    }

    /**
     * 是否已修改初始密码。
     *
     * @return false 时需要先完成改密
     */
    public boolean passwordChanged() {
        return passwordChanged;
    }

    /**
     * 用户 id，供审计与按用户吊销会话使用。
     *
     * @return 数据库主键
     */
    public long id() {
        return id;
    }

    /**
     * 原始角色名，不带 {@code ROLE_} 前缀，直接对应 JWT 的 {@code role} 声明。
     *
     * @return 角色名，如 {@code ADMIN}
     */
    public String role() {
        return role;
    }

    /**
     * 把角色映射为 Spring Security 的 {@code ROLE_} 形式权限。
     *
     * @return 单元素权限集合
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /**
     * 无状态认证不做表单登录比对，故不持有也不返回密码。
     *
     * @return 空串，仅满足 {@link UserDetails} 契约
     */
    @Override
    public String getPassword() {
        return "";
    }

    /**
     * 登录名。
     *
     * @return JWT 中的 {@code username} 声明
     */
    @Override
    public String getUsername() {
        return username;
    }

    /**
     * 账号是否启用；生命周期状态由服务端账号表管理，principal 自身不做过期判断。
     *
     * @return 恒为 true
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * 账号是否未锁定；锁定状态由数据库字段表达，本 principal 不重复建模。
     *
     * @return 恒为 true
     */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * 凭据是否未过期；access token 的有效期由 JWT 自身声明控制。
     *
     * @return 恒为 true
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * 账号是否启用。
     *
     * @return 构造时传入的启用状态
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
