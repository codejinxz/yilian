package io.github.codejinxz.yilian.admin.controller;

import io.github.codejinxz.yilian.admin.service.dto.AdminUserRoleRequest;
import io.github.codejinxz.yilian.admin.service.dto.AdminUserStatusRequest;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.vo.AdminUserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户域三端点（/api/admin/users，API §8.1）：检索分页、启停账号、变更角色。
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
}
