package io.github.codejinxz.yilian.common.api;

/**
 * 预期内的业务失败。
 *
 * <p>由 {@link ApiExceptionHandler} 转成 {@link ApiResponse} 信封，堆栈只进服务端日志， 不返回客户端；需要给用户更具体提示时可使用带
 * message 的构造方法覆盖默认文案。
 */
public class BusinessException extends RuntimeException {

    private final ApiCode apiCode;

    /**
     * 直接使用错误码内置文案。
     *
     * @param apiCode 业务错误码，决定返回的 {@code code} 与 HTTP 状态
     */
    public BusinessException(ApiCode apiCode) {
        this(apiCode, apiCode.message());
    }

    /**
     * 覆盖默认文案，用于同一错误码下需要更具体说明的场景。
     *
     * @param apiCode 业务错误码，决定返回的 {@code code} 与 HTTP 状态
     * @param message 面向用户的替换文案，不得包含内部细节
     */
    public BusinessException(ApiCode apiCode, String message) {
        super(message);
        this.apiCode = apiCode;
    }

    /**
     * 业务错误码。
     *
     * @return 构造时传入的错误码
     */
    public ApiCode apiCode() {
        return apiCode;
    }
}
