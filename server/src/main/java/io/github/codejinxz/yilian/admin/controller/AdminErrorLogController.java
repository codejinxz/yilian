package io.github.codejinxz.yilian.admin.controller;

import io.github.codejinxz.yilian.admin.service.AdminLogService;
import io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogDetailView;
import io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogView;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端异常日志检索（/api/admin/error-logs，API §8.5–§8.6）：列表剥离堆栈，详情含堆栈。
 *
 * <p>只读、不标注 {@code @OperationLog}；管理员权限由 /api/admin/** 路径矩阵服务端保证。
 */
@RestController
@RequestMapping("/api/admin/error-logs")
@Tag(name = "admin/error-logs", description = "管理端异常日志检索与详情")
public class AdminErrorLogController {

    private final AdminLogService adminLogService;

    /**
     * @param adminLogService 日志检索服务
     */
    public AdminErrorLogController(AdminLogService adminLogService) {
        this.adminLogService = adminLogService;
    }

    /**
     * 异常日志检索：keyword 对异常类/消息/traceId/操作者拼串 contains，module 精确过滤； from/to 缺省兜底近 30 天（分区表查询纪律）。
     *
     * @param page 页码，从 1 开始
     * @param size 每页大小，默认 20、上限 100（越界归 100）
     * @param keyword 关键字，可为空
     * @param module 模块过滤，可为空
     * @param from 窗口起点（YYYY-MM-DD），可为空
     * @param to 窗口终点（YYYY-MM-DD，含当日），可为空
     * @return 分页异常日志（不含 stackTrace，created_at 倒序）
     */
    @Operation(
            summary = "异常日志检索",
            description =
                    "列表不含 stackTrace；keyword 对 exceptionClass/message/traceId/operator 拼串 contains。")
    @GetMapping
    public ApiResponse<PageResponse<AdminErrorLogView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(
                adminLogService.searchErrorLogs(keyword, module, from, to, page, size));
    }

    /**
     * 异常详情：堆栈只在详情出现（AGENTS §3.6）；缺失回 40400。
     *
     * @param id 异常行 id
     * @return 含堆栈的详情视图
     */
    @Operation(summary = "异常详情（含堆栈）", description = "stackTrace 只在详情返回，列表页不取。")
    @GetMapping("/{id}")
    public ApiResponse<AdminErrorLogDetailView> detail(@PathVariable long id) {
        return ApiResponse.success(adminLogService.errorLogDetail(id));
    }
}
