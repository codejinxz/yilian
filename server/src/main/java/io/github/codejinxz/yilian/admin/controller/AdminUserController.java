package io.github.codejinxz.yilian.admin.controller;

import io.github.codejinxz.yilian.admin.service.dto.AdminUserDeleteRequest;
import io.github.codejinxz.yilian.admin.service.dto.AdminUserReset2faRequest;
import io.github.codejinxz.yilian.admin.service.dto.AdminUserResetPasswordRequest;
import io.github.codejinxz.yilian.admin.service.dto.AdminUserRoleRequest;
import io.github.codejinxz.yilian.admin.service.dto.AdminUserStatusRequest;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.vo.AdminPasswordResetView;
import io.github.codejinxz.yilian.system.service.vo.AdminUserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户域端点（/api/admin/users，API §8.1–8.2）：检索分页、启停账号、变更角色、 重置密码与删除账号。
 *
 * <p>用户读写全部走 system 模块 {@link UserAccountService} 接口；admin 模块不接触 system 的 dao/entity。 启停与改角色的
 * reason 经 {@link OperationLogContext} 传入审计（注解只放静态元数据）。
 */
@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "admin/users", description = "管理端用户管理")
public class AdminUserController {

    private final UserAccountService userAccountService;

    /**
     * @param userAccountService 账号用例服务（system 模块接口）
     */
    public AdminUserController(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    /**
     * 用户检索分页：keyword 模糊（用户名/邮箱）、role/status 精确过滤。
     *
     * @param page 页码，从 1 开始
     * @param size 每页大小，默认 20
     * @param keyword 关键字，可为空
     * @param role 角色过滤 USER/ADMIN，可为空
     * @param status 状态过滤 active/disabled/deleted/all，可为空
     * @return 分页用户视图
     */
    @Operation(summary = "管理端用户检索")
    @GetMapping
    public ApiResponse<PageResponse<AdminUserView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(
                userAccountService.adminSearch(keyword, role, status, page, size));
    }

    /**
     * 启停账号：停用必填原因，不能停用自己（服务端校验并返回业务失败）。
     *
     * @param userId 目标用户 id
     * @param request enabled 与审计原因
     * @param actor 当前管理员
     * @return 更新后的用户视图
     */
    @Operation(summary = "停用/恢复账号")
    @OperationLog(module = "admin", action = "USER_STATUS_CHANGE", targetType = "user")
    @PatchMapping("/{userId}/status")
    public ApiResponse<AdminUserView> setStatus(
            @PathVariable long userId,
            @Valid @RequestBody AdminUserStatusRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        // 注解属性只能是常量，动态对象 id 与管理员填写的原因写上下文，由切面覆盖落库。
        OperationLogContext.set("user", Long.toString(userId), request.reason());
        return ApiResponse.success(
                userAccountService.adminSetStatus(
                        actor.id(), userId, request.enabled(), request.reason()));
    }

    /**
     * 变更角色：不能变更自己的角色，不能降级最后一个管理员（服务端校验）。
     *
     * @param userId 目标用户 id
     * @param request 目标角色与审计原因
     * @param actor 当前管理员
     * @return 更新后的用户视图
     */
    @Operation(summary = "变更用户角色")
    @OperationLog(module = "admin", action = "USER_ROLE_CHANGE", targetType = "user")
    @PatchMapping("/{userId}/role")
    public ApiResponse<AdminUserView> setRole(
            @PathVariable long userId,
            @Valid @RequestBody AdminUserRoleRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        OperationLogContext.set("user", Long.toString(userId), request.reason());
        return ApiResponse.success(
                userAccountService.adminSetRole(
                        actor.id(), userId, request.role(), request.reason()));
    }

    /**
     * 重置用户密码：生成一次性临时口令随响应返回一次，目标用户回到强制改密态并全部下线。
     *
     * <p>审计用真实动作 {@code RESET_PASSWORD}（此前经 /api/admin/audit-events 演示入口，本端点上线后 从白名单移除），原因随 {@link
     * OperationLogContext} 落库。明文口令不进日志：切面不读参数与请求体， 响应体也只回给当前管理员。
     *
     * @param userId 目标用户 id
     * @param request 审计原因
     * @param actor 当前管理员
     * @return 一次性临时口令回执
     */
    @Operation(summary = "重置用户密码")
    @OperationLog(module = "admin", action = "RESET_PASSWORD", targetType = "user")
    @PostMapping("/{userId}/reset-password")
    public ApiResponse<AdminPasswordResetView> resetPassword(
            @PathVariable long userId,
            @Valid @RequestBody AdminUserResetPasswordRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        OperationLogContext.set("user", Long.toString(userId), request.reason());
        return ApiResponse.success(
                userAccountService.adminResetPassword(actor.id(), userId, request.reason()));
    }

    /**
     * 重置用户两步验证：清空 TOTP 注册与全部恢复码并吊销全部会话，目标用户重新走注册流程。
     *
     * <p>公网锁死兜底：TOTP 与恢复码全失时管理员解救。允许对自己执行（唯一管理员丢码时 没有他人可救）；未注册 2FA 的目标为无状态变化的幂等成功；已删目标按 40400 收敛 （与
     * reset-password 口径一致）。
     *
     * @param userId 目标用户 id
     * @param request 审计原因
     * @param actor 当前管理员
     * @return 成功提示
     */
    @Operation(summary = "重置用户两步验证")
    @OperationLog(module = "admin", action = "RESET_2FA", targetType = "user")
    @PostMapping("/{userId}/reset-2fa")
    public ApiResponse<Void> reset2fa(
            @PathVariable long userId,
            @Valid @RequestBody AdminUserReset2faRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        OperationLogContext.set("user", Long.toString(userId), request.reason());
        userAccountService.adminReset2fa(actor.id(), userId, request.reason());
        return ApiResponse.successMessage("已重置该用户的两步验证");
    }

    /**
     * 删除账号：软删置位（30 天冷静期）并吊销全部会话；自己不能删自己，已删返回 40400。
     *
     * @param userId 目标用户 id
     * @param request 审计原因
     * @param actor 当前管理员
     * @return 软删后的用户视图
     */
    @Operation(summary = "删除账号（软删）")
    @OperationLog(module = "admin", action = "USER_DELETE", targetType = "user")
    @DeleteMapping("/{userId}")
    public ApiResponse<AdminUserView> deleteUser(
            @PathVariable long userId,
            @Valid @RequestBody AdminUserDeleteRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        OperationLogContext.set("user", Long.toString(userId), request.reason());
        return ApiResponse.success(
                userAccountService.adminDeleteUser(actor.id(), userId, request.reason()));
    }
}
