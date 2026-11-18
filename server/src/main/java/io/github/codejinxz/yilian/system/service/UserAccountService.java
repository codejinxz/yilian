package io.github.codejinxz.yilian.system.service;

import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.system.service.dto.RegisterCommand;
import io.github.codejinxz.yilian.system.service.dto.UserCredentials;
import io.github.codejinxz.yilian.system.service.dto.UserSettings;
import io.github.codejinxz.yilian.system.service.dto.UserSettingsPatch;
import io.github.codejinxz.yilian.system.service.vo.AdminPasswordResetView;
import io.github.codejinxz.yilian.system.service.vo.AdminUserView;
import io.github.codejinxz.yilian.system.service.vo.InviteCodeAdminView;
import io.github.codejinxz.yilian.system.service.vo.InviteCodeCreatedView;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 账号用例服务：认证读取、注册、改密、设置与管理端账号操作。 */
public interface UserAccountService {

    /**
     * 按用户名或邮箱读取认证视图，登录时使用。
     *
     * @param login 用户名或邮箱
     * @return 认证视图；不存在时为空
     */
    Optional<UserCredentials> findByLogin(String login);

    /**
     * 按用户 id 读取认证视图，刷新 token 时校验用户仍存在且启用。
     *
     * @param id 用户 id
     * @return 认证视图
     */
    Optional<UserCredentials> findById(long id);

    /**
     * 注册新用户；校验邮箱/用户名唯一、密码强度与邀请码。
     *
     * @param command 注册命令
     * @return 新用户认证视图
     */
    UserCredentials register(RegisterCommand command);

    /**
     * 记录一次成功登录。
     *
     * @param userId 用户 id
     */
    void recordLogin(long userId);

    /**
     * 更新密码并标记已改密；调用方负责吊销 refresh 会话。
     *
     * @param userId 用户 id
     * @param newPassword 新明文密码，只在服务内编码
     */
    void changePassword(long userId, String newPassword);

    /**
     * 读取用户设置。
     *
     * @param userId 用户 id
     * @return 设置视图
     */
    UserSettings getSettings(long userId);

    /**
     * 更新用户设置；至少一个字段非空。
     *
     * @param userId 用户 id
     * @param patch 待更新字段
     * @return 更新后的设置
     */
    UserSettings updateSettings(long userId, UserSettingsPatch patch);

    /**
     * 管理端用户检索。
     *
     * <p>角色用字符串承载，管理模块只依赖本接口、不接触 {@code entity} 包；解析失败按参数校验失败返回。
     *
     * @param keyword 用户名/邮箱关键字
     * @param role 角色过滤 USER/ADMIN，null 或空表示全部
     * @param status active/disabled/deleted/all
     * @param page 页码，从 1 开始
     * @param size 每页大小
     * @return 分页视图
     */
    PageResponse<AdminUserView> adminSearch(
            String keyword, String role, String status, int page, int size);

    /**
     * 启停账号；停用必填原因，且不能停用自己或最后一个管理员。
     *
     * @param actorId 操作者 id
     * @param targetId 目标用户 id
     * @param enabled true 恢复、false 停用
     * @param reason 审计原因
     * @return 更新后的视图
     */
    AdminUserView adminSetStatus(long actorId, long targetId, boolean enabled, String reason);

    /**
     * 变更角色；不能变更自己的角色，也不能降级最后一个管理员。
     *
     * <p>角色用字符串承载，理由同 {@link #adminSearch}。
     *
     * @param actorId 操作者 id
     * @param targetId 目标用户 id
     * @param role 新角色 USER/ADMIN
     * @param reason 审计原因
     * @return 更新后的视图
     */
    AdminUserView adminSetRole(long actorId, long targetId, String role, String reason);

    /**
     * 生成一次性邀请码：明文只随本响应返回一次，库里只存 SHA-256 与展示前缀。
     *
     * @param actorId 创建者管理员 id，写 {@code invite_codes.created_by}
     * @return 含一次性明文的视图
     */
    InviteCodeCreatedView adminCreateInvite(long actorId);

    /**
     * 管理端邀请码列表：按创建时间倒序，最多返回最近 50 条，不含已软删。
     *
     * @return 列表项视图
     */
    List<InviteCodeAdminView> adminListInvites();

    /**
     * 管理员重置目标用户密码：生成一次性临时口令、回到强制改密态并吊销全部会话。
     *
     * <p>临时口令只在本次响应返回一次；审计由控制器 {@code @OperationLog(action=RESET_PASSWORD)} 落库。
     *
     * @param actorId 操作管理员 id
     * @param targetId 目标用户 id
     * @param reason 审计原因，必填
     * @return 含一次性明文临时口令的回执
     */
    AdminPasswordResetView adminResetPassword(long actorId, long targetId, String reason);

    /**
     * 管理员重置目标用户的两步验证：清空 TOTP 注册（密文/开关/启用时刻）与全部恢复码， 并吊销该用户全部 refresh 会话。
     *
     * <p>公网锁死兜底：用户 TOTP 与恢复码全失时由管理员解救，解救后重新走 setup/activate。 允许管理员对自己执行（唯一管理员丢码时无人可救自己之外的人）；未注册 2FA
     * 的目标是 无状态变化的无害操作（幂等 200）；目标不存在或已删按 40400 收敛（与重置密码口径一致）。
     *
     * @param actorId 操作管理员 id
     * @param targetId 目标用户 id
     * @param reason 审计原因，必填
     */
    void adminReset2fa(long actorId, long targetId, String reason);

    /**
     * 管理员删除账号：软删置位并吊销全部会话；自己不能删自己，已删返回 40400。
     *
     * @param actorId 操作管理员 id
     * @param targetId 目标用户 id
     * @param reason 审计原因，必填
     * @return 软删后的视图
     */
    AdminUserView adminDeleteUser(long actorId, long targetId, String reason);

    /**
     * 用户自助删号：软删置位（deleted_by=本人，与管理员删同口径）、清 TOTP 注册与恢复码、 吊销全部 refresh 会话；学习记录 append-only 不动。
     *
     * <p>密码与动态码确认在 auth 层完成（BCrypt/TOTP 算法在 auth 与 common）；本方法只承接已通过
     * 确认的删号用例。无自助撤销：冷静期内管理员可通过既有管理面处置争议。
     *
     * @param userId 当前登录用户 id
     * @param now 删号时间
     * @return 软删完成时刻（deleted_at，UTC；匿名化时点=该值+30 天，由调用方换算）
     */
    Instant selfDelete(long userId, Instant now);

    /**
     * 匿名化单个到期账号（调度任务逐用户入口）：身份字段替换/清空、个人化设置回注册默认、 清 TOTP 注册与恢复码、按软删时的用户名吊销全部会话，最后落 {@code
     * anonymized_at}。
     *
     * <p>幂等：未删或已匿名的账号零变更直接返回 false；学习记录（attempts/attempt_gradings/ appeals/review_logs）append-only
     * 一律不动。匿名化不可逆，无管理员恢复入口。
     *
     * @param userId 账号 id
     * @param now 匿名化时间
     * @return 本次实际执行匿名化为 true；账号不满足到期条件（未删/已匿名化）为 false
     */
    boolean anonymizeUser(long userId, Instant now);
}
