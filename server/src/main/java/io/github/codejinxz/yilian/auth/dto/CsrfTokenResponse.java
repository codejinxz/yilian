package io.github.codejinxz.yilian.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * CSRF 令牌信息，供前端在首次写请求前回填请求头。
 *
 * <p>令牌本身同时写入可读 cookie（{@code XSRF-TOKEN}），这里返回明文是为了让 SPA 在 cookie 尚未生效或不方便读取时也能直接使用。
 *
 * @param token 令牌原始值
 * @param headerName 服务端期望的请求头名
 * @param parameterName 服务端期望的表单参数名
 */
@Schema(
        description =
                "CSRF 令牌信息；同一 token 会写入可读 Cookie XSRF-TOKEN，" + "前端在写请求时把它回填到 X-XSRF-TOKEN 请求头。")
public record CsrfTokenResponse(
        @Schema(
                        description = "CSRF token 原始值，仅本次会话使用，不要写入日志或长期存储",
                        example = "<csrf-token>",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String token,
        @Schema(
                        description = "服务端期望的请求头名，固定为 X-XSRF-TOKEN",
                        example = "X-XSRF-TOKEN",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String headerName,
        @Schema(
                        description = "服务端期望的表单参数名；JSON 请求应使用请求头而不是该参数",
                        example = "csrf",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String parameterName) {}
