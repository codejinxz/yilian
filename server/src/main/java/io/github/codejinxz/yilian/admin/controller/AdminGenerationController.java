package io.github.codejinxz.yilian.admin.controller;

import io.github.codejinxz.yilian.admin.service.AdminGenerationService;
import io.github.codejinxz.yilian.admin.service.dto.AdminGenerationImportRequest;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.upload.dto.ImportBatchViewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端出题导入（/api/admin/generation/import，API §8.11）：AI 生成句子整批入库。
 *
 * <p>写路径走 {@link AdminGenerationService}（内部复用 content 导入接缝）；admin 模块不接触 content 的 dao/entity。审计
 * action=GENERATION_IMPORT，对象是合成的批次 id（upload 侧无批次记录，仅作溯源分组）。
 */
@RestController
@RequestMapping("/api/admin/generation")
@Tag(name = "admin/generation", description = "管理端出题导入")
public class AdminGenerationController {

    /** 缺省审计原因；前端固定发同值，服务端兜底保持同一口径。 */
    static final String DEFAULT_REASON = "AI 生成导入";

    private final AdminGenerationService adminGenerationService;

    /**
     * @param adminGenerationService 出题导入服务
     */
    public AdminGenerationController(AdminGenerationService adminGenerationService) {
        this.adminGenerationService = adminGenerationService;
    }

    /**
     * 导入 AI 生成的句子：任一行校验失败或指纹重复都整批拒绝。
     *
     * @param request 目标题库、句子行与可选审计原因
     * @param actor 当前管理员
     * @return 批次视图（status=COMMITTED；前端据此刷新「最近导入」）
     */
    @Operation(summary = "导入 AI 生成的句子", description = "指纹去重语义与文件导入一致；批次 id 仅用于溯源，upload 侧无批次记录。")
    @OperationLog(module = "admin", action = "GENERATION_IMPORT", targetType = "import-batch")
    @PostMapping("/import")
    public ApiResponse<ImportBatchViewResponse> importGenerated(
            @RequestBody AdminGenerationImportRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        ImportBatchViewResponse batch =
                adminGenerationService.importGenerated(
                        actor.id(), request.bankId(), request.items());
        String reason =
                request.reason() == null || request.reason().isBlank()
                        ? DEFAULT_REASON
                        : request.reason().trim();
        OperationLogContext.set("import-batch", batch.batchId(), reason);
        return ApiResponse.success(batch);
    }
}
