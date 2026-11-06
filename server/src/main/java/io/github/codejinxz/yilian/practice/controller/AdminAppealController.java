package io.github.codejinxz.yilian.practice.controller;

import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.AdminAppealService;
import io.github.codejinxz.yilian.practice.service.dto.AdminAppealDecisionRequest;
import io.github.codejinxz.yilian.practice.service.vo.AdminAppealView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端申诉终审端点（/api/admin/appeals，API §8.16）：分页检索与人工终审。
 *
 * <p>裁决链路（DATA-MODEL §5.10）：用户申诉提交时 LLM 初裁落「AI 意见」（decision JSON， status 保持
 * PENDING），本端点是人工终审的必经关口——终审才落 ACCEPTED/REJECTED， ACCEPTED 经判分 overlay 参与后续判分。管理员权限由 /api/admin/**
 * 路径矩阵 （SecurityConfig hasRole("ADMIN")）服务端保证。
 */
@RestController
@RequestMapping("/api/admin/appeals")
@Tag(name = "admin/appeals", description = "管理端申诉终审")
public class AdminAppealController {

    private final AdminAppealService adminAppealService;

    /**
     * @param adminAppealService 申诉终审用例服务
     */
    public AdminAppealController(AdminAppealService adminAppealService) {
        this.adminAppealService = adminAppealService;
    }

    /**
     * 申诉分页检索：status/kind 精确过滤，created_at 倒序。
     *
     * @param page 页码，从 1 起
     * @param size 每页大小，默认 20、上限 100（越界归 100）
     * @param status 状态过滤（PENDING/ACCEPTED/REJECTED/WITHDRAWN），可为空
     * @param kind 类型过滤（APPEAL/DISPUTE），可为空
     * @return 分页申诉行（含 AI 意见、题目摘要与用户主张）
     */
    @Operation(
            summary = "申诉分页检索",
            description = "status/kind 精确过滤；行内含 AI 初裁意见（decisionVerdict/decisionReason）、题目摘要与用户主张。")
    @GetMapping
    public ApiResponse<PageResponse<AdminAppealView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind) {
        return ApiResponse.success(adminAppealService.list(status, kind, page, size));
    }

    /**
     * 申诉终审：仅 PENDING 可裁决（否则 40900）；落 status/终审理由/decided_by=ADMIN/decided_at， 初裁「AI
     * 意见」保留。终审理由必填（裁决可解释、可申诉红线）。
     *
     * @param appealId 申诉行 id
     * @param request 终审结论与理由
     * @param actor 当前管理员
     * @return 终审后的申诉行
     */
    @Operation(summary = "申诉终审", description = "ACCEPTED 进判分 overlay；REJECTED 不回写题目；仅 PENDING 可裁决。")
    @OperationLog(module = "admin", action = "APPEAL_DECIDE", targetType = "appeal")
    @PostMapping("/{appealId}/decision")
    public ApiResponse<AdminAppealView> decide(
            @PathVariable long appealId,
            @Valid @RequestBody AdminAppealDecisionRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        // 注解属性只能是常量，动态对象 id 与管理员填写的终审理由写上下文，由切面覆盖落库。
        OperationLogContext.set("appeal", Long.toString(appealId), request.reason());
        return ApiResponse.success(
                adminAppealService.decide(
                        appealId, request.status(), request.reason(), actor.id()));
    }
}
