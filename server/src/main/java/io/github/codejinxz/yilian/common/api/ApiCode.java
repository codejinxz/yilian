package io.github.codejinxz.yilian.common.api;

import org.springframework.http.HttpStatus;

/**
 * 业务响应码。
 *
 * <p>{@code code} 是稳定契约：发布后不得改号或复用，前端按它做分支判断； {@code httpStatus} 只表达 HTTP 语义（未登录 401、越权 403、参数错误
 * 400、未知异常 500）， 不能替代 {@code code}。
 */
public enum ApiCode {
    /** 成功；文案固定为 {@code success}，前端以此为唯一成功判据。 */
    SUCCESS(0, "success", HttpStatus.OK),

    /** Bean Validation 失败，{@code data.errors} 中带回字段级明细。 */
    VALIDATION_FAILED(40000, "请求参数不合法", HttpStatus.BAD_REQUEST),
    /** 请求体不是合法 JSON 或字段类型不匹配。 */
    MALFORMED_REQUEST(40001, "请求体格式不正确", HttpStatus.BAD_REQUEST),
    /** 邀请码不存在、已过期、已停用或已被使用。 */
    INVALID_INVITE_CODE(40002, "邀请码无效或已过期", HttpStatus.BAD_REQUEST),
    /** 密码长度或复杂度不满足要求。 */
    WEAK_PASSWORD(40003, "密码强度不足", HttpStatus.BAD_REQUEST),
    /** page/size/limit 越界或排序字段不在白名单。 */
    INVALID_PAGINATION(40004, "分页参数不合法", HttpStatus.BAD_REQUEST),
    /** 增量拉取的 sinceVersion 为负或超过题库当前版本。 */
    INVALID_SINCE_VERSION(40005, "版本号不合法", HttpStatus.BAD_REQUEST),

    /** 未携带有效 access token，或 refresh 会话已过期/被吊销。 */
    AUTH_REQUIRED(40100, "请先登录", HttpStatus.UNAUTHORIZED),
    /** 用户名不存在、账号已停用、密码错误或密码超长，统一给出同一文案以免枚举账号。 */
    AUTH_INVALID_CREDENTIALS(40101, "用户名或密码错误", HttpStatus.UNAUTHORIZED),

    /** 已认证但角色不足，例如普通用户访问 {@code /api/admin/**}。 */
    ACCESS_DENIED(40300, "没有访问权限", HttpStatus.FORBIDDEN),
    /** CSRF cookie 与请求头不匹配或缺失；前端应重新拉取 {@code /api/auth/csrf}。 */
    CSRF_INVALID(40301, "CSRF 令牌无效，请刷新页面后重试", HttpStatus.FORBIDDEN),
    /** 当前实例关闭了邀请注册。 */
    REGISTRATION_CLOSED(40302, "当前未开放注册", HttpStatus.FORBIDDEN),
    /** 首次登录未改初始密码，业务接口一律拒绝。 */
    PASSWORD_CHANGE_REQUIRED(40303, "请先修改初始密码", HttpStatus.FORBIDDEN),

    /** 路由或静态资源不存在。 */
    NOT_FOUND(40400, "请求的资源不存在", HttpStatus.NOT_FOUND),
    /** 指定题库不存在或不可见。 */
    BANK_NOT_FOUND(40401, "题库不存在", HttpStatus.NOT_FOUND),
    /** 指定题目不存在或不可见。 */
    SENTENCE_NOT_FOUND(40402, "题目不存在", HttpStatus.NOT_FOUND),
    /** 指定答题记录不存在或不属于当前用户。 */
    ATTEMPT_NOT_FOUND(40403, "答题记录不存在", HttpStatus.NOT_FOUND),
    /** 指定复习卡不存在或不属于当前用户。 */
    REVIEW_NOT_FOUND(40404, "复习记录不存在", HttpStatus.NOT_FOUND),
    /** 指定导入批次不存在。 */
    IMPORT_BATCH_NOT_FOUND(40405, "导入批次不存在", HttpStatus.NOT_FOUND),

    /** 接口存在但不支持该 HTTP 方法。 */
    METHOD_NOT_ALLOWED(40500, "请求方法不被支持", HttpStatus.METHOD_NOT_ALLOWED),

    /** 幂等键已存在：同一请求正在处理或已处理，客户端不应自动重试。 */
    IDEMPOTENT_CONFLICT(40900, "请求正在处理或已处理，请勿重复提交", HttpStatus.CONFLICT),
    /** 注册邮箱已被占用。 */
    EMAIL_ALREADY_EXISTS(40901, "邮箱已被使用", HttpStatus.CONFLICT),
    /** 注册用户名已被占用。 */
    USERNAME_ALREADY_EXISTS(40902, "用户名已被使用", HttpStatus.CONFLICT),
    /** 同一 attemptId 被不同用户或不同载荷复用。 */
    ATTEMPT_ID_REUSED(40903, "该答题 id 已被其他内容使用", HttpStatus.CONFLICT),
    /** 导入批次当前状态不允许提交或回滚。 */
    IMPORT_NOT_COMMITTABLE(40904, "当前批次不可提交", HttpStatus.CONFLICT),
    /** 邀请码已被使用（一码一人）。 */
    INVITE_ALREADY_USED(40905, "邀请码已被使用", HttpStatus.CONFLICT),

    /** 导入文件超过 5MB 上限。 */
    FILE_TOO_LARGE(41300, "文件超过 5MB 上限", HttpStatus.PAYLOAD_TOO_LARGE),
    /** 请求的 Content-Type 不被接口接受，例如向 JSON 接口发送 text/plain。 */
    UNSUPPORTED_MEDIA_TYPE(41500, "请求的媒体类型不被支持", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    /** 导入批次存在非法行，整批拒绝提交。 */
    IMPORT_VALIDATION_FAILED(42200, "导入校验未通过", HttpStatus.UNPROCESSABLE_ENTITY),

    /** 触发限流；响应头 {@code Retry-After} 给出建议等待秒数。 */
    RATE_LIMITED(42900, "操作过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),
    /** 未预期异常；对外只给通用文案，堆栈仅进服务端日志。 */
    INTERNAL_ERROR(50000, "服务器暂时无法处理该请求，请稍后再试", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ApiCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /**
     * 业务码。
     *
     * @return 稳定数字码，0 表示成功
     */
    public int code() {
        return code;
    }

    /**
     * 默认中文文案。
     *
     * @return 直接可返回给用户的提示
     */
    public String message() {
        return message;
    }

    /**
     * 对应的 HTTP 状态。
     *
     * @return 仅表达成功/失败类别的状态码，细分支仍看 {@link #code()}
     */
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
