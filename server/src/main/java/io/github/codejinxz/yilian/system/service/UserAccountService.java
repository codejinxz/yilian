package io.github.codejinxz.yilian.system.service;

import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.system.entity.UserRole;
import io.github.codejinxz.yilian.system.service.dto.AdminUserView;
import io.github.codejinxz.yilian.system.service.dto.RegisterCommand;
import io.github.codejinxz.yilian.system.service.dto.UserCredentials;
import io.github.codejinxz.yilian.system.service.dto.UserSettings;
import io.github.codejinxz.yilian.system.service.dto.UserSettingsPatch;

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
     * @param keyword 用户名/邮箱关键字
     * @param role 角色过滤，可为 null
     * @param status active/disabled/deleted/all
     * @param page 页码，从 1 开始
     * @param size 每页大小
     * @return 分页视图
     */
    PageResponse<AdminUserView> adminSearch(
            String keyword, UserRole role, String status, int page, int size);

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
     * 变更角色；不能降级自己或最后一个管理员。
     *
     * @param actorId 操作者 id
     * @param targetId 目标用户 id
     * @param role 新角色
     * @param reason 审计原因
     * @return 更新后的视图
     */
    AdminUserView adminSetRole(long actorId, long targetId, UserRole role, String reason);
}
