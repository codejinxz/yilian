package io.github.codejinxz.yilian.system.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 账号实体，对应 {@code users} 表。
 *
 * <p>只存 BCrypt 密码哈希，永不保存明文；用户名与邮箱唯一。停用（{@code enabled=false}）与软删（{@code deleted_at}）是两件事：
 * 停用可恢复，软删进入匿名化倒计时。字段变更一律走显式方法，以便同步 {@code updated_at} 并让调用点可审计。
 */
@Entity
@Table(name = "users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 255)
    private String email;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "password_changed", nullable = false)
    private boolean passwordChanged;

    @Column(name = "password_updated_at")
    private Instant passwordUpdatedAt;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(name = "daily_goal", nullable = false)
    private short dailyGoal;

    @Column(name = "rating_standard", nullable = false, length = 16)
    private String ratingStandard;

    @Column(name = "desired_retention", nullable = false, precision = 4, scale = 3)
    private BigDecimal desiredRetention;

    @Column(nullable = false, length = 8)
    private String theme;

    @Column(name = "notifications_enabled", nullable = false)
    private boolean notificationsEnabled;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "disabled_reason", length = 255)
    private String disabledReason;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() {
        // 供 JPA 反射实例化，业务代码请用静态工厂
    }

    private AppUser(
            String email,
            String username,
            String passwordHash,
            UserRole role,
            boolean passwordChanged,
            Instant now) {
        this.email = email;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
        this.passwordChanged = passwordChanged;
        this.timezone = "Asia/Shanghai";
        this.dailyGoal = 3;
        this.ratingStandard = "TEM8";
        this.desiredRetention = new BigDecimal("0.900");
        this.theme = "paper";
        this.notificationsEnabled = false;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 创建普通注册用户；注册即完成登录，因此初始密码视为已设置。
     *
     * @param email 邮箱，可为 null
     * @param username 登录名，已通过格式与唯一性校验
     * @param passwordHash BCrypt 哈希
     * @param now 创建时间
     * @return 未持久化的用户实体
     */
    public static AppUser newUser(String email, String username, String passwordHash, Instant now) {
        return new AppUser(email, username, passwordHash, UserRole.USER, true, now);
    }

    /**
     * 创建启用的管理员账号；bootstrap 管理员首次登录需改密。
     *
     * @param username 登录名，唯一且已通过格式校验
     * @param passwordHash BCrypt 哈希
     * @param now 创建时间，由调用方统一取时钟便于测试
     * @return 未持久化的管理员实体
     */
    public static AppUser newAdmin(String username, String passwordHash, Instant now) {
        return new AppUser(null, username, passwordHash, UserRole.ADMIN, false, now);
    }

    /** 重新启用账号，用于 bootstrap 修复被误停用的管理员或管理员手动恢复。 */
    public void enable() {
        this.enabled = true;
        this.disabledReason = null;
        this.disabledAt = null;
        touch();
    }

    /**
     * 停用账号并记录原因；停用不会清空答题与审计数据。
     *
     * @param reason 管理员填写的原因，必填
     * @param now 停用时间
     */
    public void disable(String reason, Instant now) {
        this.enabled = false;
        this.disabledReason = reason;
        this.disabledAt = now;
        touch();
    }

    /**
     * 变更角色；调用方负责「不能降级最后一个管理员」等业务校验。
     *
     * @param role 新角色
     */
    public void changeRole(UserRole role) {
        this.role = role;
        touch();
    }

    /** 把角色提升为管理员，只升不降，避免自动化流程意外收回权限。 */
    public void promoteToAdmin() {
        changeRole(UserRole.ADMIN);
    }

    /**
     * 覆盖密码哈希并标记已改密；refresh 会话吊销由调用方负责。
     *
     * @param passwordHash 新的 BCrypt 哈希
     * @param now 改密时间
     */
    public void changePassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.passwordChanged = true;
        this.passwordUpdatedAt = now;
        touch();
    }

    /**
     * 一次性恢复开关使用：只覆盖密码哈希，不改 {@code password_changed}。
     *
     * @param passwordHash 新的 BCrypt 哈希
     * @param now 改密时间
     */
    public void resetPassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.passwordUpdatedAt = now;
        touch();
    }

    /**
     * 记录最近一次成功登录时间；失败登录不更新。
     *
     * @param now 登录时间
     */
    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
        touch();
    }

    /**
     * 软删账号；不删除答题与审计，30 天后由后续任务匿名化。
     *
     * @param operatorId 操作者 id
     * @param now 删除时间
     */
    public void softDelete(Long operatorId, Instant now) {
        this.deletedAt = now;
        this.deletedBy = operatorId;
        this.enabled = false;
        touch();
    }

    /**
     * 更新用户设置；null 表示本次不修改该字段。
     *
     * @param timezone IANA 时区
     * @param dailyGoal 每日目标 1/3/5
     * @param ratingStandard 评分标准
     * @param desiredRetention 期望保留率
     * @param theme 主题
     * @param notificationsEnabled 是否开启通知
     */
    public void updateSettings(
            String timezone,
            Integer dailyGoal,
            String ratingStandard,
            BigDecimal desiredRetention,
            String theme,
            Boolean notificationsEnabled) {
        if (timezone != null) {
            this.timezone = timezone;
        }
        if (dailyGoal != null) {
            this.dailyGoal = dailyGoal.shortValue();
        }
        if (ratingStandard != null) {
            this.ratingStandard = ratingStandard;
        }
        if (desiredRetention != null) {
            this.desiredRetention = desiredRetention;
        }
        if (theme != null) {
            this.theme = theme;
        }
        if (notificationsEnabled != null) {
            this.notificationsEnabled = notificationsEnabled;
        }
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * 主键。
     *
     * @return 自增 id，未持久化时为 null
     */
    public Long getId() {
        return id;
    }

    /**
     * 邮箱。
     *
     * @return 邮箱；未设置时为 null
     */
    public String getEmail() {
        return email;
    }

    /**
     * 登录名。
     *
     * @return 唯一用户名
     */
    public String getUsername() {
        return username;
    }

    /**
     * 密码哈希。
     *
     * @return BCrypt 哈希，不应出现在日志或响应中
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * 角色。
     *
     * @return 角色枚举
     */
    public UserRole getRole() {
        return role;
    }

    /**
     * 账号是否启用。
     *
     * @return false 时登录与刷新都被拒绝
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 是否已修改初始密码。
     *
     * @return false 时需要先完成改密
     */
    public boolean isPasswordChanged() {
        return passwordChanged;
    }

    /**
     * 最近改密时间。
     *
     * @return UTC 时间；从未改密时为 null
     */
    public Instant getPasswordUpdatedAt() {
        return passwordUpdatedAt;
    }

    /**
     * IANA 时区。
     *
     * @return 时区 id
     */
    public String getTimezone() {
        return timezone;
    }

    /**
     * 每日目标。
     *
     * @return 1/3/5
     */
    public short getDailyGoal() {
        return dailyGoal;
    }

    /**
     * 评分标准。
     *
     * @return TEM8/CATTI/IELTS
     */
    public String getRatingStandard() {
        return ratingStandard;
    }

    /**
     * FSRS 期望保留率。
     *
     * @return 0.800–0.970
     */
    public BigDecimal getDesiredRetention() {
        return desiredRetention;
    }

    /**
     * 主题。
     *
     * @return paper/night
     */
    public String getTheme() {
        return theme;
    }

    /**
     * 通知开关。
     *
     * @return 是否开启
     */
    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    /**
     * 最近登录时间。
     *
     * @return UTC 时间；从未登录时为 null
     */
    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    /**
     * 停用原因。
     *
     * @return 管理员填写的原因；未停用时为 null
     */
    public String getDisabledReason() {
        return disabledReason;
    }

    /**
     * 停用时间。
     *
     * @return UTC 时间；未停用时为 null
     */
    public Instant getDisabledAt() {
        return disabledAt;
    }

    /**
     * 软删时间。
     *
     * @return UTC 时间；未删除时为 null
     */
    public Instant getDeletedAt() {
        return deletedAt;
    }

    /**
     * 软删操作者。
     *
     * @return 用户 id；未删除时为 null
     */
    public Long getDeletedBy() {
        return deletedBy;
    }

    /**
     * 创建时间。
     *
     * @return UTC 时间戳
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * 最后修改时间。
     *
     * @return UTC 时间戳
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
