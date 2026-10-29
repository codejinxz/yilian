package io.github.codejinxz.yilian.upload.controller;

import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.upload.dto.ImportBatchDetailResponse;
import io.github.codejinxz.yilian.upload.dto.ImportBatchViewResponse;
import io.github.codejinxz.yilian.upload.dto.ImportPreviewResponse;
import io.github.codejinxz.yilian.upload.dto.RollbackImportRequest;
import io.github.codejinxz.yilian.upload.service.ImportBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 导入批次五端点（/api/admin/uploads，切片设计 §4.1）： preview（multipart 同步校验）→ commit（单事务入库）→ rollback（软删 +
 * tombstone）， 外加批次列表与详情分页。管理员权限由 /api/admin/** 路径矩阵保证。
 */
@RestController
@RequestMapping("/api/admin/uploads")
@Tag(name = "admin/uploads", description = "题库导入批次管理")
public class ImportBatchController {

    private final ImportBatchService importBatchService;

    /**
     * @param importBatchService 导入批次服务
     */
    public ImportBatchController(ImportBatchService importBatchService) {
        this.importBatchService = importBatchService;
    }

    /**
     * 同步预览：解析 + 逐行校验 + 指纹去重，落 PREVIEWED 批次与行级结果，不写题目。
     *
     * @param file 导入文件（.json / .csv）
     * @param bankId 目标题库 id
     * @param format 声明格式 JSON/CSV
     * @param actor 当前管理员
     * @return 预览结果与行级错误
     */
    @Operation(summary = "预览导入文件")
    @OperationLog(module = "content", action = "IMPORT_PREVIEW")
    @PostMapping("/preview")
    public ApiResponse<ImportPreviewResponse> preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam("bankId") Long bankId,
            @RequestParam("format") String format,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        return ApiResponse.success(importBatchService.preview(file, bankId, format, actor));
    }

    /**
     * 提交批次：单事务把 VALID 行写为题目；重复提交幂等返回。
     *
     * @param batchId 批次 id
     * @param actor 当前管理员
     * @return 提交后的批次视图
     */
    @Operation(summary = "提交导入批次")
    @OperationLog(module = "content", action = "IMPORT_COMMIT")
    @PostMapping("/{batchId}/commit")
    public ApiResponse<ImportBatchViewResponse> commit(
            @PathVariable String batchId, @AuthenticationPrincipal YilianUserPrincipal actor) {
        return ApiResponse.success(importBatchService.commit(batchId, actor));
    }

    /**
     * 回滚已提交批次：题目 RECYCLED 软删 + 银行修订号递增下发 tombstone。
     *
     * @param batchId 批次 id
     * @param request 回滚原因
     * @param actor 当前管理员
     * @return 回滚后的批次视图
     */
    @Operation(summary = "回滚导入批次")
    @OperationLog(module = "content", action = "IMPORT_ROLLBACK")
    @PostMapping("/{batchId}/rollback")
    public ApiResponse<ImportBatchViewResponse> rollback(
            @PathVariable String batchId,
            @org.springframework.validation.annotation.Validated @RequestBody
                    RollbackImportRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        return ApiResponse.success(importBatchService.rollback(batchId, request.reason(), actor));
    }

    /**
     * 批次列表：题库/状态/时间范围动态筛选。
     *
     * @param page 页码
     * @param size 每页条数
     * @param bankId 题库 id
     * @param status 批次状态
     * @param from 创建日期下界（含）
     * @param to 创建日期上界（含）
     * @return 分页批次
     */
    @Operation(summary = "导入批次列表")
    @GetMapping
    public ApiResponse<PageResponse<ImportBatchViewResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long bankId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(importBatchService.list(page, size, bankId, status, from, to));
    }

    /**
     * 批次详情 + 行分页。
     *
     * @param batchId 批次 id
     * @param page 页码
     * @param size 每页条数
     * @param status 行状态过滤
     * @return 批次与行分页
     */
    @Operation(summary = "导入批次详情")
    @GetMapping("/{batchId}")
    public ApiResponse<ImportBatchDetailResponse> detail(
            @PathVariable String batchId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(importBatchService.detail(batchId, page, size, status));
    }
}
