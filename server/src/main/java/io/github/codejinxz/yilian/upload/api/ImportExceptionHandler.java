package io.github.codejinxz.yilian.upload.api;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.upload.exception.ImportFileTooLargeException;
import io.github.codejinxz.yilian.upload.support.ImportFilePolicy;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 导入接口的请求侧异常出口。
 *
 * <p>只处理 multipart 解析阶段的异常，且优先级高于公共 {@code ApiExceptionHandler}：
 *
 * <ul>
 *   <li>文件超过 5MB → HTTP 413；公共错误码没有 413 号段，业务码复用 {@link ApiCode#VALIDATION_FAILED}，
 *       避免为一次导入扩大对外错误码契约；
 *   <li>缺少 {@code file} 字段 → 400，防止落到公共兜底分支被误报为 500；
 *   <li>multipart 报文损坏 → 400。
 * </ul>
 *
 * <p>扩展名与 MIME 不受支持仍由 Service 抛 {@code BusinessException(UNSUPPORTED_MEDIA_TYPE)}， 走公共出口返回
 * 415，保持与其他接口一致。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ImportExceptionHandler {

    /**
     * 容器层拒绝超大文件时转 413；此时请求可能还没进入 Controller。
     *
     * @param exception 容器抛出的超限异常
     * @return 413 + 统一信封
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(
            MaxUploadSizeExceededException exception) {
        return tooLarge();
    }

    /**
     * 服务层再次校验文件大小时抛出的超限异常。
     *
     * @param exception 超限异常
     * @return 413 + 统一信封
     */
    @ExceptionHandler(ImportFileTooLargeException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooLarge(ImportFileTooLargeException exception) {
        return tooLarge();
    }

    /**
     * 缺少 {@code file} 表单字段属于客户端参数错误，不能落到 500 兜底。
     *
     * @param exception 缺少 part 异常
     * @return 400 + 参数错误信封
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(
            MissingServletRequestPartException exception) {
        return ResponseEntity.status(ApiCode.VALIDATION_FAILED.httpStatus())
                .body(ApiResponse.<Void>fail(ApiCode.VALIDATION_FAILED, "缺少文件字段 file"));
    }

    /**
     * 其余 multipart 解析失败按请求体格式错误处理。
     *
     * @param exception multipart 异常
     * @return 400 + 格式错误信封
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException exception) {
        return ResponseEntity.status(ApiCode.MALFORMED_REQUEST.httpStatus())
                .body(ApiResponse.<Void>fail(ApiCode.MALFORMED_REQUEST, "导入文件上传格式不正确"));
    }

    /**
     * 统一组装 413 响应，文案只暴露上限，不回显实际大小或内部路径。
     *
     * @return 413 + 统一信封
     */
    private static ResponseEntity<ApiResponse<Void>> tooLarge() {
        long megabytes = ImportFilePolicy.MAX_FILE_SIZE_BYTES / (1024L * 1024);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(
                        ApiResponse.<Void>fail(
                                ApiCode.VALIDATION_FAILED, "导入文件不能超过 " + megabytes + "MB"));
    }
}
