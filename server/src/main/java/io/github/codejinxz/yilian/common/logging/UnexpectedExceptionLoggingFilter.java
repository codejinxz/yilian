package io.github.codejinxz.yilian.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 最外层异常兜底：捕获过滤链/容器层未处理的异常并写 {@code error_log}。
 *
 * <p>MVC 内部的未知异常由 {@link io.github.codejinxz.yilian.common.api.ApiExceptionHandler}
 * 处理并记录；本过滤器只覆盖请求尚未进入 MVC 或异常逃逸出整个链路的场景，避免重复写。 已认证失败、CSRF、业务异常仍由各自安全/业务入口处理，不应到达这里。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UnexpectedExceptionLoggingFilter extends OncePerRequestFilter {

    private final ErrorLogRecorder errorLogRecorder;

    /**
     * 注入异常记录器。
     *
     * @param errorLogRecorder 未预期异常落库入口
     */
    public UnexpectedExceptionLoggingFilter(ErrorLogRecorder errorLogRecorder) {
        this.errorLogRecorder = errorLogRecorder;
    }

    /**
     * 捕获未处理异常后原样抛出，让容器保持原有错误语义。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param filterChain 后续过滤器链
     * @throws ServletException 后续过滤器异常
     * @throws IOException 读写异常
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            errorLogRecorder.record(exception, request);
            throw exception;
        }
    }
}
