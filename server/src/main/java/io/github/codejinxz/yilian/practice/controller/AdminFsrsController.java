package io.github.codejinxz.yilian.practice.controller;

import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.FsrsAdminService;
import io.github.codejinxz.yilian.practice.service.dto.FsrsOptimizeRequest;
import io.github.codejinxz.yilian.practice.service.vo.FsrsParameterVersionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 FSRS 权重版本接口（/api/admin/fsrs）：优化产出候选、版本列表、启用。
 *
 * <p>fsrs 属 practice 域（AGENTS 模块内聚），控制器放 practice/controller；权限由 SecurityConfig 对 {@code
 * /api/admin/**} 的服务端 ROLE_ADMIN 规则统一把关，前端隐藏不算权限。
 */
@Tag(name = "admin/fsrs", description = "FSRS 参数版本：SGD 优化产出候选、版本与训练摘要、启用状态机")
@RestController
@RequestMapping("/api/admin/fsrs")
public class AdminFsrsController {

    private final FsrsAdminService fsrsAdminService;

    /**
     * @param fsrsAdminService FSRS 参数管理用例
     */
    public AdminFsrsController(FsrsAdminService fsrsAdminService) {
        this.fsrsAdminService = fsrsAdminService;
    }

    /**
     * 触发优化：读指定用户的 review_logs 训练候选权重；样本不足 40000 拒绝。
     *
     * @param request 目标用户（必填，单人产品语境：管理员 ≠ 学习者，不隐式绑定当前操作者）
     * @param actor 当前管理员
     * @return 新候选版本视图
     */
    @Operation(
            summary = "触发 FSRS 优化",
            description =
                    "读 userId 的 review_logs（≥50 条）训练 21 维候选权重，写 GLOBAL CANDIDATE 行；"
                            + "样本不足按 40000 拒绝，不伪造已优化。训练摘要（logLoss/rmse/迭代数）落 metrics。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "训练完成，返回 CANDIDATE 视图"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "userId 缺失（40000）或复习样本不足（40000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "500",
                description = "未预期异常（50000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @OperationLog(
            module = "practice",
            action = "FSRS_OPTIMIZE",
            targetType = "fsrs_parameter_version")
    @PostMapping(value = "/optimize", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<FsrsParameterVersionView> optimize(
            @Valid @RequestBody FsrsOptimizeRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        return ApiResponse.success(fsrsAdminService.optimize(request.userId(), actor.id()));
    }

    /**
     * 参数版本列表：权重、训练摘要与状态，创建时间倒序。
     *
     * @param actor 当前管理员
     * @return 版本视图列表
     */
    @Operation(
            summary = "FSRS 参数版本列表",
            description = "创建时间倒序；含 weights（21 维）与 metrics 训练摘要，便于人工核对后再启用。")
    @GetMapping("/parameters")
    public ApiResponse<List<FsrsParameterVersionView>> parameters(
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        return ApiResponse.success(fsrsAdminService.listParameters());
    }

    /**
     * 启用候选版本：仅 CANDIDATE；事务内旧 GLOBAL ACTIVE → RETIRED。
     *
     * @param versionId 候选版本 id
     * @param actor 当前管理员
     * @return 启用后的版本视图
     */
    @Operation(
            summary = "启用 FSRS 候选权重",
            description =
                    "仅 CANDIDATE 可启用（其余状态 40000）；同一事务内旧 GLOBAL ACTIVE 置 RETIRED，"
                            + "调度随下次排程消费新权重。动作写操作日志（FSRS_ENABLE）。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "启用成功，返回 ACTIVE 视图"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "版本不是 CANDIDATE（40000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "版本不存在（40400）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "500",
                description = "未预期异常（50000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @OperationLog(
            module = "practice",
            action = "FSRS_ENABLE",
            targetType = "fsrs_parameter_version")
    @PostMapping("/parameters/{versionId}/enable")
    public ApiResponse<FsrsParameterVersionView> enable(
            @PathVariable Long versionId, @AuthenticationPrincipal YilianUserPrincipal actor) {
        return ApiResponse.success(fsrsAdminService.enable(versionId, actor.id()));
    }
}
