package io.github.codejinxz.yilian.admin.controller;

import io.github.codejinxz.yilian.admin.service.dto.AdminBankCreateRequest;
import io.github.codejinxz.yilian.admin.service.dto.AdminBankDeleteRequest;
import io.github.codejinxz.yilian.admin.service.dto.AdminBankUpdateRequest;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.content.service.ContentAdminService;
import io.github.codejinxz.yilian.content.service.vo.BankView;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;
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
 * 管理端题库域端点（/api/admin/banks，API §8.8）：检索分页、新建、详情、部分更新、软删与题库内题目列表。
 *
 * <p>读写全部走 content 模块 {@link ContentAdminService} 接口；admin 模块不接触 content 的 dao/entity。
 * 删除是状态流转（RECYCLED）而非物理删（软删）；写操作 reason 经 {@link OperationLogContext} 传入审计（注解只放静态元数据）， 通用更新可以不带
 * reason（前端 {@code updateAdminBank} 不发）。
 */
@RestController
@RequestMapping("/api/admin/banks")
@Tag(name = "admin/banks", description = "管理端题库管理")
public class AdminBankController {

    private final ContentAdminService contentAdminService;

    /**
     * @param contentAdminService 内容管理用例服务（content 模块接口）
     */
    public AdminBankController(ContentAdminService contentAdminService) {
        this.contentAdminService = contentAdminService;
    }

    /**
     * 题库检索分页：keyword 匹配名称/说明，visibility/status 精确过滤，含全部生命周期。
     *
     * @param page 页码，从 1 开始
     * @param size 每页大小，默认 20
     * @param keyword 关键字，可为空
     * @param visibility 可见性过滤 PUBLIC/PRIVATE，可为空
     * @param status 状态过滤，可为空
     * @return 分页题库视图
     */
    @Operation(summary = "管理端题库检索")
    @GetMapping
    public ApiResponse<PageResponse<BankView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(
                contentAdminService.adminListBanks(keyword, visibility, status, page, size));
    }

    /**
     * 新建题库：owner 为空（官方题库），初始 DRAFT。
     *
     * @param request 名称、说明与可见性
     * @param actor 当前管理员
     * @return 新题库视图
     */
    @Operation(summary = "新建题库")
    @OperationLog(module = "admin", action = "BANK_CREATE", targetType = "bank")
    @PostMapping
    public ApiResponse<BankView> create(
            @Valid @RequestBody AdminBankCreateRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        BankView created =
                contentAdminService.adminCreateBank(
                        actor.id(), request.name(), request.description(), request.visibility());
        // 新建不强制填原因（与前端 createAdminBank 一致）；对象 id 动态写上下文。
        OperationLogContext.set("bank", created.id(), null);
        return ApiResponse.success(created);
    }

    /**
     * 题库详情：任何状态可见（含回收站）；不存在回 40401 信封。
     *
     * @param bankId 题库 id
     * @return 题库视图
     */
    @Operation(summary = "管理端题库详情")
    @GetMapping("/{bankId}")
    public ApiResponse<BankView> get(@PathVariable long bankId) {
        return ApiResponse.success(contentAdminService.adminGetBank(bankId));
    }

    /**
     * 部分更新题库：name/description/visibility/status 任选；status 变更含恢复语义。
     *
     * @param bankId 题库 id
     * @param request 待更新字段与可选审计原因
     * @param actor 当前管理员
     * @return 更新后的题库视图
     */
    @Operation(summary = "更新题库")
    @OperationLog(module = "admin", action = "BANK_UPDATE", targetType = "bank")
    @PatchMapping("/{bankId}")
    public ApiResponse<BankView> update(
            @PathVariable long bankId,
            @Valid @RequestBody AdminBankUpdateRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        // reason 可空：updateAdminBankStatus 带原因，通用更新（改名/改说明）不强求。
        OperationLogContext.set("bank", Long.toString(bankId), request.reason());
        return ApiResponse.success(
                contentAdminService.adminUpdateBank(
                        actor.id(),
                        bankId,
                        request.name(),
                        request.description(),
                        request.visibility(),
                        request.status()));
    }

    /**
     * 软删题库：状态流转到 RECYCLED，不物理删除。
     *
     * @param bankId 题库 id
     * @param request 删除原因，必填
     * @return 空数据的成功信封（HTTP 200，删除为状态流转，无负载）
     */
    @Operation(summary = "软删题库", description = "状态流转到 RECYCLED，不物理删除题目。")
    @OperationLog(module = "admin", action = "BANK_DELETE", targetType = "bank")
    @DeleteMapping("/{bankId}")
    public ApiResponse<Void> delete(
            @PathVariable long bankId,
            @Valid @RequestBody AdminBankDeleteRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        OperationLogContext.set("bank", Long.toString(bankId), request.reason());
        contentAdminService.adminDeleteBank(actor.id(), bankId, request.reason());
        return ApiResponse.success();
    }

    /**
     * 题库内管理端题目列表：含 DRAFT/RECYCLED 全量生命周期。
     *
     * @param bankId 题库 id
     * @param page 页码，从 1 开始
     * @param size 每页大小，默认 20
     * @param keyword 关键字，匹配中文/参考译文，可为空
     * @param status 状态过滤，可为空
     * @return 分页题目视图
     */
    @Operation(summary = "题库内管理端题目列表")
    @GetMapping("/{bankId}/sentences")
    public ApiResponse<PageResponse<SentenceDetail>> listSentences(
            @PathVariable long bankId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(
                contentAdminService.adminListBankSentences(bankId, keyword, status, page, size));
    }
}
