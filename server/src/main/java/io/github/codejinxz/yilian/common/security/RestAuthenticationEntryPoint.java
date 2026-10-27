package io.github.codejinxz.yilian.common.security;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 未登录访问受保护接口时返回 ApiResponse 信封 + 401，与 Controller 的错误结构保持一致。 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    /**
     * 注入与 MVC 共用的 JSON 序列化器，保证错误响应格式和正常响应一致。
     *
     * @param objectMapper Spring 容器中的 JSON 映射器
     */
    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 统一回 {@link ApiCode#AUTH_REQUIRED}，不区分「未带 token」与「token 已失效」， 避免向调用方泄漏校验细节；前端据此跳转登录。
     *
     * @param request 当前请求，错误响应不需要请求内容
     * @param response 当前响应，直接写入 JSON
     * @param authException 认证失败原因，不回显给客户端
     * @throws IOException 写响应体失败时抛出
     */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        ApiCode apiCode = ApiCode.AUTH_REQUIRED;
        response.setStatus(apiCode.httpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(apiCode));
    }
}
