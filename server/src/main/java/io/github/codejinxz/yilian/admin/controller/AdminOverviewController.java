package io.github.codejinxz.yilian.admin.controller;

import io.github.codejinxz.yilian.admin.service.AdminOverviewService;
import io.github.codejinxz.yilian.admin.service.vo.AdminOverviewView;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端概览（/api/admin/overview）：指标、近 7 天活跃、待办引导与最近管理活动。
 *
 * <p>管理员权限由 /api/admin/** 路径矩阵（SecurityConfig hasRole("ADMIN")）服务端保证。
 */
@RestController
@RequestMapping("/api/admin/overview")
@Tag(name = "admin/overview", description = "管理端概览聚合")
public class AdminOverviewController {

    private final AdminOverviewService adminOverviewService;

    /**
     * @param adminOverviewService 概览聚合服务
     */
    public AdminOverviewController(AdminOverviewService adminOverviewService) {
        this.adminOverviewService = adminOverviewService;
    }

    /**
     * 概览聚合：纯读路径，无参数。
     *
     * @return 概览视图
     */
    @Operation(summary = "管理端概览聚合")
    @GetMapping
    public ApiResponse<AdminOverviewView> overview() {
        return ApiResponse.success(adminOverviewService.overview());
    }
}
