package io.github.codejinxz.yilian.system.service.dto;

import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.entity.UserRole;

import java.math.BigDecimal;

/**
 * 认证与练习所需的账号只读视图。
 *
 * <p>其他模块只依赖该 DTO，不直接引用 system 的持久化实体；密码哈希仅在认证服务内部用于比对，不得到达控制器。
 *
 * @param id 用户 id
 * @param email 邮箱，可为 null
 * @param username 登录名
 * @param passwordHash BCrypt 哈希
 * @param role 角色
 * @param enabled 账号是否启用
 * @param passwordChanged 是否已改初始密码
 * @param timezone IANA 时区，FSRS 日界与 today 使用
 * @param dailyGoal 每日目标
 * @param desiredRetention FSRS 期望保留率
 * @param deleted 是否已软删
 */
public record UserCredentials(
        long id,
        String email,
        String username,
        String passwordHash,
        UserRole role,
        boolean enabled,
        boolean passwordChanged,
        String timezone,
        int dailyGoal,
        BigDecimal desiredRetention,
        boolean deleted) {

    /**
     * 从实体抽取认证所需字段，避免把 JPA 实体带出 system 模块。
     *
     * @param user 账号实体
     * @return 只读认证视图
     */
    public static UserCredentials from(AppUser user) {
        return new UserCredentials(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getRole(),
                user.isEnabled(),
                user.isPasswordChanged(),
                user.getTimezone(),
                user.getDailyGoal(),
                user.getDesiredRetention(),
                user.getDeletedAt() != null);
    }
}
