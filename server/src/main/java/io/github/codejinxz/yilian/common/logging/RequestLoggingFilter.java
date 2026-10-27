package io.github.codejinxz.yilian.common.logging;

import io.github.codejinxz.yilian.common.security.SecurityUtils;
import io.github.codejinxz.yilian.common.util.IdUtils;
import io.github.codejinxz.yilian.common.util.WebUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 补齐 AGENTS.md 要求的结构化日志字段：traceId、脱敏 userId、clientIp、module、action 与 durationMs。 请求体（因此也包括密码）永不写日志。
 *
 * <p>该过滤器只处理自己写入 MDC/日志的内容，并在写入前调用 {@link LogMasking#sanitize(String)} 做兜底遮盖；这只是最后一道防线，
 * 调用方仍不得主动把密码、token、Authorization 头等敏感字段写进日志。
 *
 * <p>该过滤器注册在 Spring Security 过滤器链内、{@code SecurityContextHolderFilter} 之后， 这样写收尾日志时仍能拿到已认证的
 * principal。
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    /** 允许调用方透传链路 id 的请求头；回显同名响应头，便于前端把错误与后端日志对上。 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 未命中任何模块映射时的兜底模块名。 */
    private static final String DEFAULT_MODULE = "system";

    /**
     * URI 前缀到模块名的固定映射，按声明顺序匹配；新增模块时只改这一处。
     *
     * <p>前缀按路径段边界比较，避免 {@code /api/auth} 误吞 {@code /api/authentication}。
     */
    private static final List<Map.Entry<String, String>> MODULE_PREFIXES =
            List.of(
                    Map.entry("/api/auth", "account"),
                    Map.entry("/api/me", "account"),
                    Map.entry("/api/banks", "content"),
                    Map.entry("/api/admin", "admin"),
                    Map.entry("/api/content", "content"),
                    Map.entry("/api/practice", "practice"),
                    Map.entry("/api/ai", "ai"),
                    Map.entry("/api/analytics", "analytics"),
                    Map.entry("/actuator", "system"));

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    /**
     * 为每次请求建立 MDC 上下文，并在响应结束后输出一条汇总日志。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param filterChain 后续过滤器链
     * @throws ServletException 后续过滤器抛出时传递
     * @throws IOException 读写请求/响应失败时传递
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        long startedAt = System.nanoTime();
        String path = request.getRequestURI();
        MDC.put("traceId", traceId);
        MDC.put("module", moduleOf(path));
        MDC.put("action", LogMasking.sanitize(request.getMethod() + " " + path));
        MDC.put("clientIp", WebUtils.clientIp(request));
        response.setHeader(REQUEST_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.put("durationMs", Long.toString((System.nanoTime() - startedAt) / 1_000_000));
            MDC.put("userId", currentUserId());
            MDC.put("status", Integer.toString(response.getStatus()));
            log.info("request completed");
            MDC.clear();
        }
    }

    /**
     * 复用调用方链路 id，但只接受白名单字符与长度，防止日志注入。
     *
     * @param request 当前请求
     * @return 合法的请求头值，否则新生成随机 traceId
     */
    private static String resolveTraceId(HttpServletRequest request) {
        String candidate = request.getHeader(REQUEST_ID_HEADER);
        if (candidate != null && TRACE_ID_PATTERN.matcher(candidate).matches()) {
            return candidate;
        }
        return IdUtils.newCompactId();
    }

    /**
     * 按 URI 前缀归入粗粒度模块，供日志检索与错误表分模块统计。
     *
     * <p>前缀按路径段边界匹配：{@code /api/auth} 与 {@code /api/auth/**} 命中 auth， 而 {@code /api/authentication}
     * 不算命中。未命中任何映射时归为 {@code system}。
     *
     * @param path 请求路径，允许为 null
     * @return 模块名；path 为 null 或未命中时返回 {@code system}
     */
    static String moduleOf(String path) {
        if (path == null) {
            return DEFAULT_MODULE;
        }
        for (Map.Entry<String, String> entry : MODULE_PREFIXES) {
            String prefix = entry.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return entry.getValue();
            }
        }
        return DEFAULT_MODULE;
    }

    /**
     * 从当前安全上下文取用户 id 并脱敏；未认证时记录 anonymous。
     *
     * @return 脱敏后的用户标识
     */
    private static String currentUserId() {
        return LogMasking.maskUserId(SecurityUtils.currentUserId().orElse(null));
    }
}
