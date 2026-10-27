package io.github.codejinxz.yilian.common.api;

import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitExceededException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全局异常出口：所有异常都转成 {@link ApiResponse} 信封，HTTP 状态取自 {@link ApiCode#httpStatus()}。
 *
 * <p>这样前端既能用 HTTP 状态做粗分类，也能用 {@code code} 做细分支；未知异常只回通用文案， 堆栈仅落服务端日志，避免泄漏内部细节。
 *
 * <p>常见请求侧 4xx 必须在这里显式收敛为业务码，不能落到 {@code Exception} 兜底分支： 否则客户端拿到的会是 500，既误导用户，也让监控把正常的参数错误当成服务故障。
 * 但越权（{@link AccessDeniedException}）与 CSRF 仍走原有安全链路口径，不在此处改写。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ErrorLogRecorder errorLogRecorder;

    /**
     * 注入未知异常记录器；已知业务异常不会走该记录器。
     *
     * @param errorLogRecorder 未预期异常落库入口
     */
    public ApiExceptionHandler(ErrorLogRecorder errorLogRecorder) {
        this.errorLogRecorder = errorLogRecorder;
    }

    /**
     * 限流是预期内的业务失败：除 429 与业务码外，还要把等待秒数写进 {@code Retry-After} 响应头， 前端和网关才能按同一节奏退避。
     *
     * @param exception 限流异常
     * @return 429 + {@code RATE_LIMITED}，并携带 {@code Retry-After}
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimited(
            RateLimitExceededException exception) {
        ApiCode apiCode = exception.apiCode();
        return ResponseEntity.status(apiCode.httpStatus())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(ApiResponse.fail(apiCode, exception.getMessage()));
    }

    /**
     * 预期内的业务失败按错误码原样返回，文案优先取异常自带的覆盖值。
     *
     * <p>{@code IDEMPOTENT_CONFLICT} 的 409 也走这里：错误码自带 HTTP 状态，无需单独分支。
     *
     * @param exception 业务异常
     * @return 对应错误码与 HTTP 状态的 {@link ApiResponse} 信封
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        ApiCode apiCode = exception.apiCode();
        return respond(apiCode, ApiResponse.<Void>fail(apiCode, exception.getMessage()));
    }

    /**
     * 参数校验失败要带回字段级明细，因此 data 里放 errors 列表而不是 null。
     *
     * @param exception 方法参数校验异常
     * @return 400 + {@code VALIDATION_FAILED}，{@code data.errors} 为字段与提示的列表
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleValidation(
            MethodArgumentNotValidException exception) {
        List<Map<String, String>> errors =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> error(error.getField(), error.getDefaultMessage()))
                        .toList();
        return validationFailed(ApiCode.VALIDATION_FAILED.message(), errors);
    }

    /**
     * Spring 6.1+/Boot 4 的参数级校验（如 {@code @RequestParam @Min(1)}）抛的是 {@link
     * HandlerMethodValidationException}，与 {@link MethodArgumentNotValidException} 不同， 需要单独收敛成
     * 400；这里尽量保留参数名与约束提示，方便前端直接展示。
     *
     * @param exception 参数级校验异常
     * @return 400 + {@code VALIDATION_FAILED}，{@code data.errors} 为参数或字段级明细
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleHandlerMethodValidation(
            HandlerMethodValidationException exception) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            String parameterName = parameterName(result.getMethodParameter());
            if (result instanceof ParameterErrors parameterErrors) {
                for (FieldError fieldError : parameterErrors.getFieldErrors()) {
                    errors.add(
                            error(
                                    parameterName + "." + fieldError.getField(),
                                    fieldError.getDefaultMessage()));
                }
                for (ObjectError globalError : parameterErrors.getGlobalErrors()) {
                    errors.add(error(parameterName, globalError.getDefaultMessage()));
                }
            } else {
                for (MessageSourceResolvable resolvable : result.getResolvableErrors()) {
                    errors.add(error(parameterName, resolvable.getDefaultMessage()));
                }
            }
        }
        for (MessageSourceResolvable resolvable : exception.getCrossParameterValidationResults()) {
            errors.add(error("请求参数", resolvable.getDefaultMessage()));
        }
        return validationFailed(ApiCode.VALIDATION_FAILED.message(), errors);
    }

    /**
     * 路径变量或查询参数类型不匹配（如 {@code /sentences/abc} 期望 {@code long}）属于客户端错误， 返回 400 并复用 {@code
     * VALIDATION_FAILED}，避免被兜底分支误报为 500。
     *
     * @param exception 参数类型不匹配异常
     * @return 400 + {@code VALIDATION_FAILED}，{@code data.errors} 说明参数与期望类型
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        Class<?> requiredType = exception.getRequiredType();
        String message =
                requiredType == null ? "参数类型不正确" : "参数类型不正确，应为 " + requiredType.getSimpleName();
        return validationFailed(
                ApiCode.VALIDATION_FAILED.message(), List.of(error(exception.getName(), message)));
    }

    /**
     * 缺少必填的查询参数或表单参数时回 400，并在 data 中指明缺失的参数名。
     *
     * @param exception 缺少请求参数异常
     * @return 400 + {@code VALIDATION_FAILED}，{@code data.errors} 带上缺失参数名
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleMissingParameter(
            MissingServletRequestParameterException exception) {
        return validationFailed(
                ApiCode.VALIDATION_FAILED.message(),
                List.of(error(exception.getParameterName(), "缺少必填参数")));
    }

    /**
     * 方法级 Bean Validation（非 Controller 入参绑定，例如 Service 上的 {@code @Validated}） 抛出的是 jakarta 的 {@link
     * ConstraintViolationException}；它同样是客户端输入问题，归 400。
     *
     * @param exception 约束违反异常
     * @return 400 + {@code VALIDATION_FAILED}，{@code data.errors} 为字段与约束提示
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleConstraintViolation(
            ConstraintViolationException exception) {
        List<Map<String, String>> errors =
                exception.getConstraintViolations().stream()
                        .map(
                                violation ->
                                        error(
                                                fieldName(violation.getPropertyPath()),
                                                violation.getMessage()))
                        .toList();
        return validationFailed(ApiCode.VALIDATION_FAILED.message(), errors);
    }

    /**
     * 请求体无法反序列化时统一按格式错误处理，不向客户端回显解析器细节。
     *
     * @param exception 请求体解析异常
     * @return 400 + {@code MALFORMED_REQUEST}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedBody(
            HttpMessageNotReadableException exception) {
        return respond(ApiCode.MALFORMED_REQUEST);
    }

    /**
     * Content-Type 不被接口接受时回 415；这不是服务端故障，不能落入 500 兜底。
     *
     * @param exception 媒体类型不支持异常
     * @return 415 + {@code UNSUPPORTED_MEDIA_TYPE}
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception) {
        return respond(ApiCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * 未知路由与静态资源缺失走同一出口，避免返回默认 HTML 错误页。
     *
     * @param exception 资源未找到异常
     * @return 404 + {@code NOT_FOUND}
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException exception) {
        return respond(ApiCode.NOT_FOUND);
    }

    /**
     * HTTP 方法不被支持时保留 405 语义。
     *
     * @param exception 方法不支持异常
     * @return 405 + {@code METHOD_NOT_ALLOWED}
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception) {
        return respond(ApiCode.METHOD_NOT_ALLOWED);
    }

    /**
     * 方法级安全与 CSRF 之外的越权统一回 403。
     *
     * @param exception 访问被拒绝异常
     * @return 403 + {@code ACCESS_DENIED}
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException exception) {
        return respond(ApiCode.ACCESS_DENIED);
    }

    /**
     * 兜底分支：记录异常与请求路径便于排查，对外只回通用 500 文案。
     *
     * @param exception 未预期异常
     * @param request 当前请求，仅用于日志中的 URI
     * @return 500 + {@code INTERNAL_ERROR}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        errorLogRecorder.record(exception, request);
        log.error("unhandled exception on {}", request.getRequestURI(), exception);
        return respond(ApiCode.INTERNAL_ERROR);
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> validationFailed(
            String message, List<Map<String, String>> errors) {
        List<Map<String, String>> details =
                errors.isEmpty()
                        ? List.<Map<String, String>>of(
                                error("参数", ApiCode.VALIDATION_FAILED.message()))
                        : errors;
        ApiCode apiCode = ApiCode.VALIDATION_FAILED;
        return respond(
                apiCode,
                ApiResponse.<Map<String, Object>>fail(
                        apiCode, message, Map.<String, Object>of("errors", details)));
    }

    private static Map<String, String> error(String field, String message) {
        return Map.of(
                "field", field == null || field.isBlank() ? "参数" : field,
                "message", message == null || message.isBlank() ? "参数不合法" : message);
    }

    private static String parameterName(MethodParameter parameter) {
        if (parameter == null) {
            return "参数";
        }
        String name = parameter.getParameterName();
        return name == null || name.isBlank() ? "参数" + (parameter.getParameterIndex() + 1) : name;
    }

    private static String fieldName(Path path) {
        String field = null;
        for (Path.Node node : path) {
            if (node.getName() != null && !node.getName().isBlank()) {
                field = node.getName();
            }
        }
        return field == null ? "参数" : field;
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ApiCode apiCode) {
        return respond(apiCode, ApiResponse.<Void>fail(apiCode));
    }

    private static <T> ResponseEntity<ApiResponse<T>> respond(
            ApiCode apiCode, ApiResponse<T> body) {
        return ResponseEntity.status(apiCode.httpStatus()).body(body);
    }
}
