package io.github.codejinxz.yilian.common.security;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 首次登录强制改密拦截器：access token 中 {@code pwdChanged=false} 时，除认证白名单外一律返回 40303。
 *
 * <p>放在 MVC 拦截器而不是安全过滤器，是为了复用统一的 {@code ApiResponse} 异常出口，避免过滤器里手写 JSON。 只拦截 {@code
 * /api/**}；actuator 与接口文档不受影响。
 */
@Component
public class PasswordChangeRequiredInterceptor implements HandlerInterceptor {

    /** 首次登录仍可调用的认证端点；与 API §1.4 保持一致。 */
    private static final Set<String> ALLOWED_PATHS =
            Set.of("/api/auth/me", "/api/auth/password", "/api/auth/logout", "/api/auth/csrf");

    /**
     * 在进入 Controller 前检查改密标记。
     *
     * @param request 当前请求
     * @param response 当前响应，本拦截器不直接写响应
     * @param handler 目标处理器
     * @return 允许继续时返回 true
     * @throws BusinessException 未改初始密码且访问非白名单接口时抛出 40303
     */
    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || ALLOWED_PATHS.contains(path)) {
            return true;
        }
        if (SecurityUtils.currentPrincipal()
                .filter(principal -> !principal.passwordChanged())
                .isPresent()) {
            throw new BusinessException(ApiCode.PASSWORD_CHANGE_REQUIRED);
        }
        return true;
    }
}
