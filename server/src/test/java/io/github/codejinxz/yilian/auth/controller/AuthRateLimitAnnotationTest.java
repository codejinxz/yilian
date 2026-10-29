package io.github.codejinxz.yilian.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.common.ratelimit.RateLimit;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

/**
 * 认证端点限流注解契约测试 [AC-2]：refresh 与 password 端点必须挂 @RateLimit 且参数符合 防爆破口径（refresh 是 cookie 盲试面按 IP
 * 限；password 是凭据修改面按账号限）。
 *
 * <p>用反射断言注解存在性与参数，防止后续重构「顺手」摘掉限流；429 行为由 RateLimitService 集成路径覆盖（AuthFlow 关闭限流，此处锁声明式契约）。
 */
class AuthRateLimitAnnotationTest {

    /** [AC-2] refresh 端点：IP 维度 30 次/60 秒。 */
    @Test
    @DisplayName("[AC-2] refresh 端点限流契约（IP 30/60s）")
    void ac2_refreshHasIpRateLimit() throws Exception {
        RateLimit rateLimit = rateLimitOf("refresh");

        assertThat(rateLimit).isNotNull();
        assertThat(rateLimit.scene()).isEqualTo("refresh");
        assertThat(rateLimit.dimensions()).containsExactly(RateLimitDimension.IP);
        assertThat(rateLimit.limit()).isEqualTo(30);
        assertThat(rateLimit.windowSeconds()).isEqualTo(60);
    }

    /** [AC-2] password 端点：账号维度 5 次/60 秒，账号取自当前主体。 */
    @Test
    @DisplayName("[AC-2] password 端点限流契约（ACCOUNT 5/60s）")
    void ac2_changePasswordHasAccountRateLimit() throws Exception {
        RateLimit rateLimit = rateLimitOf("changePassword");

        assertThat(rateLimit).isNotNull();
        assertThat(rateLimit.scene()).isEqualTo("password-change");
        assertThat(rateLimit.dimensions()).containsExactly(RateLimitDimension.ACCOUNT);
        assertThat(rateLimit.limit()).isEqualTo(5);
        assertThat(rateLimit.windowSeconds()).isEqualTo(60);
        assertThat(rateLimit.accountExpression()).isEqualTo("#principal.id()");
    }

    /**
     * 取指定方法的 @RateLimit 注解。
     *
     * @param methodName AuthController 方法名
     * @return 注解；方法不存在或未标注时为 null
     * @throws AssertionError 方法不存在时失败
     */
    private static RateLimit rateLimitOf(String methodName) throws Exception {
        Method method =
                AuthController.class.getDeclaredMethod(methodName, resolveParams(methodName));
        return method.getAnnotation(RateLimit.class);
    }

    /**
     * 按方法名解析参数类型（两个目标方法参数不同）。
     *
     * @param methodName 方法名
     * @return 参数类型数组
     */
    private static Class<?>[] resolveParams(String methodName) {
        return switch (methodName) {
            case "refresh" ->
                    new Class<?>[] {
                        jakarta.servlet.http.HttpServletRequest.class,
                        jakarta.servlet.http.HttpServletResponse.class
                    };
            case "changePassword" ->
                    new Class<?>[] {
                        io.github.codejinxz.yilian.auth.dto.ChangePasswordRequest.class,
                        io.github.codejinxz.yilian.common.security.YilianUserPrincipal.class,
                        jakarta.servlet.http.HttpServletRequest.class
                    };
            default -> throw new IllegalArgumentException("未知方法: " + methodName);
        };
    }
}
