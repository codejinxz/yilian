package io.github.codejinxz.yilian.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.api.ApiCode;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

/** {@link RateLimitAspect} 的账号表达式、IP 提取、放行与超限异常。 */
class RateLimitAspectTest {

    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final RateLimitAspect aspect = new RateLimitAspect(rateLimitService);

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 未超限时应继续执行，并把用户名作为账号维度传给限流器。 */
    @Test
    void allowedRequestPassesThroughWithAccount() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("login", new Credentials("alice", "secret"));
        when(joinPoint.proceed()).thenReturn("ok");
        when(rateLimitService.acquire(
                        eq("login"), anyList(), eq(10), eq(60), eq("203.0.113.7"), eq("alice")))
                .thenReturn(RateLimitDecision.permit());

        Object result = aspect.around(joinPoint, annotation("login", Credentials.class));

        assertThat(result).isEqualTo("ok");
        verify(joinPoint).proceed();
    }

    /** 超限时应抛出带等待秒数的业务异常，且不再执行原方法。 */
    @Test
    void overLimitThrowsBeforeProceeding() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("login", new Credentials("alice", "secret"));
        when(rateLimitService.acquire(
                        anyString(), anyList(), anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn(RateLimitDecision.reject(42));

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation("login", Credentials.class)))
                .isInstanceOfSatisfying(
                        RateLimitExceededException.class,
                        exception -> {
                            assertThat(exception.apiCode()).isEqualTo(ApiCode.RATE_LIMITED);
                            assertThat(exception.retryAfterSeconds()).isEqualTo(42);
                        });

        verify(joinPoint, never()).proceed();
    }

    /** 深度批改只按账号计数，不把匿名请求误算进同一个 IP 桶。 */
    @Test
    void accountOnlySceneSkipsIp() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("deepGrade", null);
        when(joinPoint.proceed()).thenReturn("ok");
        when(rateLimitService.acquire(
                        eq("deep-grade"),
                        eq(List.of(RateLimitDimension.ACCOUNT)),
                        eq(20),
                        eq(3600),
                        anyString(),
                        any()))
                .thenReturn(RateLimitDecision.permit());

        assertThat(aspect.around(joinPoint, annotation("deepGrade", null))).isEqualTo("ok");
    }

    private static ProceedingJoinPoint joinPoint(String methodName, Object argument)
            throws NoSuchMethodException {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method =
                argument == null
                        ? Sample.class.getDeclaredMethod(methodName)
                        : Sample.class.getDeclaredMethod(methodName, argument.getClass());
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs())
                .thenReturn(argument == null ? new Object[0] : new Object[] {argument});
        return joinPoint;
    }

    private static RateLimit annotation(String methodName, Class<?> parameterType)
            throws NoSuchMethodException {
        Method method =
                parameterType == null
                        ? Sample.class.getDeclaredMethod(methodName)
                        : Sample.class.getDeclaredMethod(methodName, parameterType);
        return method.getAnnotation(RateLimit.class);
    }

    /** 登录凭据测试载体；密码只参与对象创建，不进入任何断言或日志。 */
    record Credentials(String username, String password) {}

    /** 供反射读取注解的测试载体。 */
    static class Sample {

        @RateLimit(
                scene = "login",
                dimensions = {RateLimitDimension.IP, RateLimitDimension.ACCOUNT},
                limit = 10,
                windowSeconds = 60,
                accountExpression = "#request.username()")
        void login(Credentials request) {
            // 仅用于测试注解元数据。
        }

        @RateLimit(
                scene = "deep-grade",
                dimensions = RateLimitDimension.ACCOUNT,
                limit = 20,
                windowSeconds = 3600)
        void deepGrade() {
            // 仅用于测试注解元数据。
        }
    }
}
