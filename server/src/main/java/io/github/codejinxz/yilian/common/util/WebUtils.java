package io.github.codejinxz.yilian.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Web 层公共工具，目前只负责提取客户端 IP，供审计日志与限流使用。
 *
 * <p>客户端 IP 只信任容器解析后的 {@link HttpServletRequest#getRemoteAddr()}， 不直接解析可被伪造的转发头。本类无状态、线程安全，禁止实例化。
 */
public final class WebUtils {

    /** 请求缺失或地址不可得时的占位值，保证日志字段始终非空。 */
    private static final String UNKNOWN = "unknown";

    private WebUtils() {}

    /**
     * 提取客户端 IP：始终以 {@link HttpServletRequest#getRemoteAddr()} 为准，并去除首尾空白。
     *
     * <p>不直接解析 {@code X-Forwarded-For}：该头由客户端自行填写、可被任意伪造， 直接采信会让审计、限流与封禁被绕过。若应用位于可信反向代理之后，应启用
     * Spring 的 forwarded-header 支持（例如 {@code server.forward-headers-strategy=native}）或由容器、
     * 网关提前处理，此时 remoteAddr 已被框架改写为真实客户端地址。
     *
     * @param request 当前 HTTP 请求，允许为 null
     * @return 客户端 IP；请求为 null 或 remoteAddr 为空白时返回 {@code unknown}
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        String remoteAddr = request.getRemoteAddr();
        return StringUtils.isBlank(remoteAddr) ? UNKNOWN : remoteAddr.trim();
    }
}
