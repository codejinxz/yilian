package io.github.codejinxz.yilian.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 读取本次请求安全上下文中的登录主体。
 *
 * <p>过滤器、业务服务与日志组件都要判断「是否已登录、登录的是谁」；把判空与 principal 类型 判断集中在这里，避免各调用方重复写 instanceof，也避免 principal
 * 的具体实现散落各处。 未认证与匿名认证一律视为未登录。
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    /**
     * 取当前请求的认证主体。
     *
     * <p>只有 principal 确实是 {@link YilianUserPrincipal} 时才返回；匿名认证、未建立认证 以及使用其他认证方式（如表单登录）的场景都不算登录用户。
     *
     * @return 当前登录用户，未登录时为空
     */
    public static Optional<YilianUserPrincipal> currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof YilianUserPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    /**
     * 取当前登录用户的用户名，供按用户名吊销 refresh 会话等场景使用。
     *
     * @return 登录名，未登录时为空
     */
    public static Optional<String> currentUsername() {
        return currentPrincipal().map(YilianUserPrincipal::getUsername);
    }

    /**
     * 取当前登录用户的 id，供审计与日志脱敏使用。
     *
     * @return 用户主键，未登录时为空
     */
    public static Optional<Long> currentUserId() {
        return currentPrincipal().map(principal -> principal.id());
    }
}
