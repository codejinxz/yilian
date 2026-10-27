package io.github.codejinxz.yilian.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一 API 响应信封：所有 REST 接口（含安全过滤器链抛出的 401/403）都返回该结构。
 *
 * <p>前端只解析 {@code code} / {@code message} / {@code data} 三个字段，因此字段名与 工厂方法签名属于对外契约，改动需同步前端。成功统一走
 * {@link #success(Object)} 系列， 失败统一走 {@link #fail(ApiCode)} 系列，避免调用方自行拼装响应。
 *
 * @param <T> 业务数据类型；无数据时使用 {@link Void}
 */
public final class ApiResponse<T> {

    @Schema(
            description = "业务码：0 表示成功，其余为 ApiCode 中定义的失败码",
            example = "0",
            requiredMode = Schema.RequiredMode.REQUIRED,
            accessMode = Schema.AccessMode.READ_ONLY)
    private final int code;

    @Schema(
            description = "面向用户的提示文案：成功时为 success 或调用方自定义文案，失败时为错误说明",
            example = "success",
            requiredMode = Schema.RequiredMode.REQUIRED,
            accessMode = Schema.AccessMode.READ_ONLY)
    private final String message;

    @Schema(
            description = "业务数据；成功时按接口不同为对应对象，无数据或失败时为 null，参数校验失败时为 {\"errors\": [...]}",
            accessMode = Schema.AccessMode.READ_ONLY)
    private final T data;

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功且无数据。
     *
     * @return {@code code=0}、{@code data=null} 的成功响应
     */
    public static ApiResponse<Void> success() {
        return new ApiResponse<>(ApiCode.SUCCESS.code(), ApiCode.SUCCESS.message(), null);
    }

    /**
     * 成功并携带数据。
     *
     * @param data 业务数据；调用方需保证非空，需要「只有提示文案」时用 {@link #successMessage(String)}
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ApiCode.SUCCESS.code(), ApiCode.SUCCESS.message(), data);
    }

    /**
     * 成功，使用调用方给的文案（如「已退出登录」）而非默认的 success。
     *
     * @param message 面向用户的中文提示
     * @param data 业务数据，可为 null
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(ApiCode.SUCCESS.code(), message, data);
    }

    /**
     * 成功但只需返回一句提示，data 为 null。
     *
     * @param message 面向用户的中文提示
     * @return 无数据的成功响应
     */
    public static ApiResponse<Void> successMessage(String message) {
        return new ApiResponse<>(ApiCode.SUCCESS.code(), message, null);
    }

    /**
     * 按错误码返回失败，文案取枚举内置文案。
     *
     * @param apiCode 业务错误码，同时决定 HTTP 状态
     * @return 失败响应，{@code data=null}
     */
    public static <T> ApiResponse<T> fail(ApiCode apiCode) {
        return new ApiResponse<>(apiCode.code(), apiCode.message(), null);
    }

    /**
     * 自定义文案的失败，错误码仍须来自 {@link ApiCode}。
     *
     * @param apiCode 业务错误码，必须复用 {@link ApiCode} 中已发布的号段
     * @param message 覆盖枚举默认文案的中文提示
     * @return 失败响应，{@code data=null}
     */
    public static <T> ApiResponse<T> fail(ApiCode apiCode, String message) {
        return new ApiResponse<>(apiCode.code(), message, null);
    }

    /**
     * 仅用于校验错误等需要附带明细的场景，data 承载字段错误列表。
     *
     * @param apiCode 业务错误码
     * @param message 面向用户的概要提示
     * @param data 明细数据，如 {@code {"errors": [...]}}
     * @return 携带明细的失败响应
     */
    public static <T> ApiResponse<T> fail(ApiCode apiCode, String message, T data) {
        return new ApiResponse<>(apiCode.code(), message, data);
    }

    /**
     * 业务错误码。
     *
     * @return 0 表示成功，其余为 {@link ApiCode} 定义的失败码
     */
    public int getCode() {
        return code;
    }

    /**
     * 面向用户的提示文案。
     *
     * @return 成功时为 success 或调用方自定义文案，失败时为错误说明
     */
    public String getMessage() {
        return message;
    }

    /**
     * 业务数据。
     *
     * @return 数据；无数据或失败时为 null
     */
    public T getData() {
        return data;
    }
}
