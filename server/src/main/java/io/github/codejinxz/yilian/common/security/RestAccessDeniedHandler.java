package io.github.codejinxz.yilian.common.security;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 已登录但权限不足时返回 ApiResponse 信封 + 403，避免前端解析到 HTML 错误页。 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * 注入与 MVC 共用的 JSON 序列化器，保证错误响应格式和正常响应一致。
     *
     * @param objectMapper Spring 容器中的 JSON 映射器
     */
    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 输出 403 信封；CSRF 校验失败单独使用 {@link ApiCode#CSRF_INVALID}， 以便前端区分「刷新页面重取 token」与「角色不足」两种处理。
     *
     * @param request 当前请求，错误响应不需要请求内容
     * @param response 当前响应，直接写入 JSON
     * @param accessDeniedException 拒绝原因，仅用于判断是否为 CSRF 失败
     * @throws IOException 写响应体失败时抛出
     */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        ApiCode apiCode =
                accessDeniedException instanceof CsrfException
                        ? ApiCode.CSRF_INVALID
                        : ApiCode.ACCESS_DENIED;
        response.setStatus(apiCode.httpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(apiCode));
    }
}
