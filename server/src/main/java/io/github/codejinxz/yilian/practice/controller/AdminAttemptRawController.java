package io.github.codejinxz.yilian.practice.controller;

import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.AdminAttemptRawService;
import io.github.codejinxz.yilian.practice.service.dto.AdminAttemptRawRequest;
import io.github.codejinxz.yilian.practice.service.vo.AdminAttemptRawView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端查看答题原文入口（/api/admin/attempts/{attemptId}/raw，API §8.7）。
 *
 * <p>本端点刻意不标 {@code @OperationLog}：审计行（action=VIEW_ATTEMPT_RAW）在服务事务内 先行落库，auditId
 * 要随响应返回给调用方；再挂切面会落一条重复的查看痕迹。
 */
@RestController
@RequestMapping("/api/admin/attempts")
@Tag(name = "admin/attempts", description = "管理端答题原文查看（必填原因 + 审计）")
public class AdminAttemptRawController {

    private final AdminAttemptRawService adminAttemptRawService;

    /**
     * @param adminAttemptRawService 查看原文服务
     */
    public AdminAttemptRawController(AdminAttemptRawService adminAttemptRawService) {
        this.adminAttemptRawService = adminAttemptRawService;
    }

    /**
     * 查看一次答题原文；原因必填并写审计，单条读取，无批量形态。
     *
     * @param attemptId 答题提交 UUID
     * @param request 查看原因
     * @param actor 当前管理员
     * @return 原文视图（answer + 最新 grading + auditId）
     */
    @Operation(summary = "查看答题原文", description = "reason 必填并落审计；返回原文 JSON 与最新判分")
    @PostMapping("/{attemptId}/raw")
    public ApiResponse<AdminAttemptRawView> viewRaw(
            @PathVariable String attemptId,
            @RequestBody AdminAttemptRawRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        return ApiResponse.success(
                adminAttemptRawService.viewRaw(
                        attemptId,
                        request.reason(),
                        actor.id(),
                        actor.getUsername(),
                        actor.role()));
    }
}
