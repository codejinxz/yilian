package io.github.codejinxz.yilian.admin.controller;

import io.github.codejinxz.yilian.admin.service.AdminLogService;
import io.github.codejinxz.yilian.admin.service.vo.AdminAuditLogView;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端操作日志检索（/api/admin/audit-logs，API §8.4）：只读倒序，不做状态流转。
 *
 * <p>审计表只增不改，本端点不标注 {@code @OperationLog}（读接口默认不标，AGENTS §3.6）。 管理员权限由 /api/admin/**
 * 路径矩阵（SecurityConfig hasRole("ADMIN")）服务端保证。
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@Tag(name = "admin/audit-logs", description = "管理端操作日志检索")
public class AdminAuditLogController {

    private final AdminLogService adminLogService;

    /**
     * @param adminLogService 日志检索服务
     */
    public AdminAuditLogController(AdminLogService adminLogService) {
        this.adminLogService = adminLogService;
    }

    /**
     * 操作日志检索：keyword 对动作/操作者/对象/原因拼串 contains，module/action/actorId 精确过滤； from/to 缺省兜底近 30
     * 天（分区表查询纪律）。
     *
     * @param page 页码，从 1 开始
     * @param size 每页大小，默认 20、上限 100（越界归 100）
     * @param keyword 关键字，可为空
     * @param module 模块过滤，可为空
     * @param action 动作过滤，可为空
     * @param actorId 操作者 id 过滤，可为空
     * @param from 窗口起点（YYYY-MM-DD），可为空
     * @param to 窗口终点（YYYY-MM-DD，含当日），可为空
     * @return 分页操作日志（created_at 倒序）
     */
    @Operation(
            summary = "操作日志检索",
            description =
                    "keyword 对 action/actorName/targetType/targetId/reason 拼串 contains；from/to 缺省为近 30 天。")
    @GetMapping
    public ApiResponse<PageResponse<AdminAuditLogView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(
                adminLogService.searchAuditLogs(
                        keyword, module, action, actorId, from, to, page, size));
    }
}
