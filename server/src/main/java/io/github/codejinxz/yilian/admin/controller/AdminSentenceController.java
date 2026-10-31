package io.github.codejinxz.yilian.admin.controller;

import io.github.codejinxz.yilian.admin.service.dto.AdminSentenceBulkMoveRequest;
import io.github.codejinxz.yilian.admin.service.dto.AdminSentenceBulkStatusRequest;
import io.github.codejinxz.yilian.admin.service.vo.AdminSentenceBulkMoveResult;
import io.github.codejinxz.yilian.admin.service.vo.AdminSentenceBulkStatusResult;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.content.service.ContentAdminService;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端题目域端点（/api/admin/sentences，API §8.8）：跨题库检索分页与批量状态/移动。
 *
 * <p>读写全部走 content 模块 {@link ContentAdminService} 接口；admin 模块不接触 content 的 dao/entity。
 * 批量操作单事务且实际处理数随响应暴露 （不存在的 id 跳过不报错）；一次批量写一条审计， 批量目标 id 以「updated=N / moved=N」摘要落审计，避免 500 个 ULID
 * 撑爆 target_id 列。
 */
@RestController
@RequestMapping("/api/admin/sentences")
@Tag(name = "admin/sentences", description = "管理端题目检索与批量操作")
public class AdminSentenceController {

    private final ContentAdminService contentAdminService;

    /**
     * @param contentAdminService 内容管理用例服务（content 模块接口）
     */
    public AdminSentenceController(ContentAdminService contentAdminService) {
        this.contentAdminService = contentAdminService;
    }

    /**
     * 跨题库题目检索：keyword 匹配中文/参考译文，bankId/level 精确过滤；缺省隐藏回收站， 显式传 status（含 RECYCLED）时按精确状态过滤。
     *
     * @param page 页码，从 1 开始
     * @param size 每页大小，默认 20
     * @param keyword 关键字，可为空
     * @param bankId 题库过滤，可为空
     * @param level 难度过滤，可为空
     * @param status 状态过滤，可为空
     * @return 分页题目视图（含归属题库 bankId）
     */
    @Operation(summary = "跨题库题目检索", description = "不传 status 时隐藏回收站；回收站要显式按状态查。")
    @GetMapping
    public ApiResponse<PageResponse<SentenceDetail>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String bankId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(
                contentAdminService.adminListSentences(keyword, bankId, level, status, page, size));
    }

    /**
     * 批量变更题目状态（停用/启用/回收/恢复）；单事务，实际变更数随响应暴露。
     *
     * @param request ids、目标状态与审计原因
     * @param actor 当前管理员
     * @return 实际变更的题目数
     */
    @Operation(summary = "批量变更题目状态")
    @OperationLog(module = "admin", action = "SENTENCE_BULK_STATUS", targetType = "sentence")
    @PostMapping("/bulk-status")
    public ApiResponse<AdminSentenceBulkStatusResult> bulkStatus(
            @Valid @RequestBody AdminSentenceBulkStatusRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        int updated =
                contentAdminService.adminBulkChangeStatus(
                        actor.id(), request.ids(), request.status(), request.reason());
        // 批量没有单一对象 id，审计 target_id 落处理数摘要；原因照抄管理员输入。
        OperationLogContext.set("sentence", "updated=" + updated, request.reason());
        return ApiResponse.success(new AdminSentenceBulkStatusResult(updated));
    }

    /**
     * 批量移动题目到另一题库；单事务，实际移动数随响应暴露。
     *
     * @param request ids、目标题库与审计原因
     * @param actor 当前管理员
     * @return 实际移动的题目数
     */
    @Operation(summary = "批量移动题目")
    @OperationLog(module = "admin", action = "SENTENCE_BULK_MOVE", targetType = "sentence")
    @PostMapping("/bulk-move")
    public ApiResponse<AdminSentenceBulkMoveResult> bulkMove(
            @Valid @RequestBody AdminSentenceBulkMoveRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        int moved =
                contentAdminService.adminBulkMove(
                        actor.id(), request.ids(), request.bankId(), request.reason());
        OperationLogContext.set("sentence", "moved=" + moved, request.reason());
        return ApiResponse.success(new AdminSentenceBulkMoveResult(moved));
    }
}
