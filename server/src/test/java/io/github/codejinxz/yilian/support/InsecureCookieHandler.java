package io.github.codejinxz.yilian.support;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用 CookieHandler：无视 Cookie 的 Secure 属性，把全部 Cookie 回传给明文 http 目标。
 *
 * <p>为什么存在（016 P0 测试配置入库）：集成测试统一以 {@code test} profile 启动（不再依赖 .gitignore 的 local 配置），而
 * SecurityConfig 只对 local profile 关闭 CSRF Cookie 的 Secure 标记——{@code XSRF-TOKEN} 带 Secure 下发后，JDK
 * 内置 CookieManager 不会把 Secure Cookie 回传给 http:// 请求，CSRF 对比失败，所有受保护的 POST 收到 40301。等价于模拟「浏览器在
 * 本地开发里拿到非 Secure Cookie」的握手行为，只在客户端测试桩内生效。
 *
 * <p>实现刻意极简：单测试类只访问一个 base URL，按 Cookie 名覆盖存储、全部回传，不做 域/路径/Secure 匹配。
 */
public final class InsecureCookieHandler extends CookieHandler {

    private final Map<String, HttpCookie> jar = new ConcurrentHashMap<>();

    /**
     * 返回当前全部 Cookie 的 Cookie 头；jar 为空时返回空 Map（不发头）。
     *
     * @param uri 请求目标（忽略，测试只打单一 host）
     * @param requestHeaders 请求头（忽略）
     * @return Cookie 头
     */
    @Override
    public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
        List<String> values = new ArrayList<>();
        jar.values().forEach(cookie -> values.add(cookie.getName() + "=" + cookie.getValue()));
        return values.isEmpty() ? Map.of() : Map.of("Cookie", List.of(String.join("; ", values)));
    }

    /**
     * 接收响应的 Set-Cookie（大小写不敏感），按名覆盖旧值；无视 Secure/HttpOnly 等属性。
     *
     * @param uri 响应来源（忽略）
     * @param responseHeaders 响应头
     */
    @Override
    public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
        if (responseHeaders == null) {
            return;
        }
        responseHeaders.forEach(
                (key, values) -> {
                    if (key != null && "Set-Cookie".equalsIgnoreCase(key)) {
                        values.forEach(
                                value ->
                                        HttpCookie.parse(value)
                                                .forEach(
                                                        cookie ->
                                                                jar.put(cookie.getName(), cookie)));
                    }
                });
    }
}
