package io.github.codejinxz.yilian.upload.controller;

import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.upload.dto.ImportBatchAccepted;
import io.github.codejinxz.yilian.upload.dto.ImportBatchErrorsResponse;
import io.github.codejinxz.yilian.upload.dto.ImportBatchStatusResponse;
import io.github.codejinxz.yilian.upload.dto.ImportUploadCommand;
import io.github.codejinxz.yilian.upload.service.ImportBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.Principal;

/**
 * 管理端导入接口。
 *
 * <p>路径位于 {@code /api/admin/**} 下，由 {@code SecurityConfig} 统一要求 {@code ROLE_ADMIN}，
 * 本类不重复实现鉴权。Controller 只做 multipart 参数适配、调用 Service 与组装 {@link ApiResponse}；扩展名、MIME、大小、逐行处理全部在
 * Service/策略层完成。
 *
 * <p>上传返回 202 而不是 200：文件已落对象存储并建批次，但逐行校验在后台线程执行， 客户端应使用返回的 batchId 轮询状态。resume 与查询都是同步的内存/单表操作，返回
 * 200。
 */
@Tag(name = "管理端上传/导入", description = "管理员上传 CSV/JSON 文件、轮询进度、查询失败摘要与断点续跑。")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/uploads")
public class ImportBatchController {

    /** 与注解静态值保持一致，避免动态上下文和静态声明各写一份字面量后漂移。 */
    private static final String TARGET_TYPE_IMPORT_BATCH = "import-batch";

    private static final String UPLOAD_REASON = "管理员上传导入文件";

    private static final String RESUME_REASON = "管理员续跑导入批次";

    private final ImportBatchService importBatchService;

    /**
     * 只依赖 Service 接口，具体实现由 Spring 注入。
     *
     * @param importBatchService 导入批次服务
     */
    public ImportBatchController(ImportBatchService importBatchService) {
        this.importBatchService = importBatchService;
    }

    /**
     * 上传导入文件并创建批次。
     *
     * @param file multipart 文件字段，字段名固定为 {@code file}
     * @param principal 当前管理员；仅在测试或匿名场景下可能为 null
     * @return 202 + batchId 与受理状态
     * @throws IOException 读取 multipart 内容失败时抛出，由公共异常出口记录为 500
     */
    @Operation(
            summary = "上传导入文件",
            description =
                    "接收 multipart/form-data，单文件不超过 5MB，仅允许 .csv/.json 扩展名，"
                            + "MIME 仅允许 text/csv、application/json、text/plain。"
                            + "文件流式写入对象存储后创建批次并立即返回 202，"
                            + "逐行校验与失败计数在后台执行；用返回的 batchId 轮询状态。")
    @OperationLog(
            module = "import",
            action = "upload",
            targetType = TARGET_TYPE_IMPORT_BATCH,
            reason = UPLOAD_REASON)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImportBatchAccepted>> upload(
            @RequestPart("file") MultipartFile file, Principal principal) throws IOException {
        String createdBy = principal == null ? "unknown" : principal.getName();
        try (InputStream content = file.getInputStream()) {
            ImportBatchAccepted accepted =
                    importBatchService.upload(
                            new ImportUploadCommand(
                                    file.getOriginalFilename(),
                                    file.getContentType(),
                                    file.getSize(),
                                    content,
                                    createdBy));
            // 批次 id 只有 service 返回后才知道，注解写不了动态值，交给上下文覆盖。
            OperationLogContext.set(TARGET_TYPE_IMPORT_BATCH, accepted.batchId(), UPLOAD_REASON);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(accepted));
        }
    }

    /**
     * 查询批次状态与进度。
     *
     * @param id 批次 id
     * @return 状态、计数与错误摘要
     */
    @Operation(summary = "查询导入进度", description = "返回批次状态、已处理/成功/失败行数与最近一次错误摘要，用于前端轮询。")
    @GetMapping("/{id}")
    public ApiResponse<ImportBatchStatusResponse> status(@PathVariable String id) {
        return ApiResponse.success(importBatchService.status(id));
    }

    /**
     * 查询失败行摘要。
     *
     * @param id 批次 id
     * @return 失败计数与最多 20 条行级样例
     */
    @Operation(
            summary = "查询导入失败摘要",
            description = "返回失败行计数与最近 20 条行级样例；样例会提示行号和原因，但不回传原始行内容。" + "应用重启后行级样例丢失，计数仍从批次表读取。")
    @GetMapping("/{id}/errors")
    public ApiResponse<ImportBatchErrorsResponse> errors(@PathVariable String id) {
        return ApiResponse.success(importBatchService.errors(id));
    }

    /**
     * 从最近检查点续跑批次。
     *
     * @param id 批次 id
     * @return 续跑受理后的状态
     */
    @Operation(
            summary = "续跑导入批次",
            description =
                    "从数据库保存的 resume_offset 重新打开对象流并跳过已处理行。"
                            + "已 COMPLETED 的批次幂等返回，不会重复处理；正在处理的批次直接返回当前状态。")
    @OperationLog(
            module = "import",
            action = "resume",
            targetType = TARGET_TYPE_IMPORT_BATCH,
            reason = RESUME_REASON)
    @PostMapping("/{id}/resume")
    public ApiResponse<ImportBatchStatusResponse> resume(@PathVariable String id) {
        // 路径变量无法写进注解，先写入上下文，切面在 finally 中读取并清理。
        OperationLogContext.set(TARGET_TYPE_IMPORT_BATCH, id, RESUME_REASON);
        return ApiResponse.success(importBatchService.resume(id));
    }
}
