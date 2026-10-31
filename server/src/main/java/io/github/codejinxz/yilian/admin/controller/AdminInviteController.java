package io.github.codejinxz.yilian.admin.controller;

import io.github.codejinxz.yilian.admin.service.vo.AdminInviteListView;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.vo.InviteCodeAdminView;
import io.github.codejinxz.yilian.system.service.vo.InviteCodeCreatedView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端邀请码两端点（/api/admin/invites，API §8.1）：生成与列表。
 *
 * <p>明文只随 POST 响应返回一次，库中只存 SHA-256 与展示前缀（hash 算法复用 auth 注册核销链路）； 生成与列表走 system 模块 {@link
 * UserAccountService} 接口，admin 不接触其 dao/entity。
 */
@RestController
@RequestMapping("/api/admin/invites")
@Tag(name = "admin/invites", description = "管理端邀请码管理")
public class AdminInviteController {

    private final UserAccountService userAccountService;

    /**
     * @param userAccountService 账号用例服务（system 模块接口）
     */
    public AdminInviteController(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    /**
     * 生成一个一次性邀请码；明文仅本次可见。
     *
     * @param actor 当前管理员
     * @return 含一次性明文与展示前缀的视图
     */
    @Operation(summary = "生成邀请码")
    @OperationLog(module = "admin", action = "INVITE_CREATE", targetType = "invite")
    @PostMapping
    public ApiResponse<InviteCodeCreatedView> create(
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        InviteCodeCreatedView created = userAccountService.adminCreateInvite(actor.id());
        // 审计只记前缀：明文邀请码不进日志（AGENTS §3.6 密钥与敏感值不入日志）。
        OperationLogContext.set("invite", created.codePrefix(), null);
        return ApiResponse.success(created);
    }

    /**
     * 邀请码列表：创建时间倒序、最多 50 条，只给展示前缀不给明文。
     *
     * @return 非分页列表
     */
    @Operation(summary = "邀请码列表")
    @GetMapping
    public ApiResponse<AdminInviteListView> list() {
        List<InviteCodeAdminView> items = userAccountService.adminListInvites();
        return ApiResponse.success(new AdminInviteListView(items));
    }
}
