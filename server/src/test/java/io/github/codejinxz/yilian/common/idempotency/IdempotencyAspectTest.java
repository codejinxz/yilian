package io.github.codejinxz.yilian.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/** {@link IdempotencyAspect} 的归属者解析、首次占用、重复返回 409、失败释放与缺键校验。 */
class IdempotencyAspectTest {

    private static final String CLIENT_IP = "203.0.113.7";

    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final IdempotencyAspect aspect = new IdempotencyAspect(idempotencyService);

    /** 每个用例都从匿名、带客户端 IP 的干净请求上下文开始，避免测试间互相污染。 */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        setClientIp(CLIENT_IP);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    /** 首次占用成功时原样返回，并把键与摘要交给服务。 */
    @Test
    void firstAcquirePassesThroughAndRecordsHash() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint(new AttemptRequest("attempt-1", "answer"));
        when(idempotencyService.tryAcquire(any())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.around(joinPoint, annotation());

        assertThat(result).isEqualTo("ok");
        ArgumentCaptor<IdempotencyRequest> captor =
                ArgumentCaptor.forClass(IdempotencyRequest.class);
        verify(idempotencyService).tryAcquire(captor.capture());
        assertThat(captor.getValue().scope()).isEqualTo("attempt-submit");
        assertThat(captor.getValue().key()).isEqualTo("attempt-1");
        assertThat(captor.getValue().requestHash()).hasSize(64);
        verify(joinPoint).proceed();
    }

    /** 已登录请求以用户 id 作为归属者，避免与其他用户互相占用。 */
    @Test
    void ownerIsCurrentUserIdWhenAuthenticated() throws Throwable {
        authenticate(42L);
        ProceedingJoinPoint joinPoint = joinPoint(new AttemptRequest("attempt-1", "answer"));
        when(idempotencyService.tryAcquire(any())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation());

        assertThat(capturedRequest().owner()).isEqualTo("42");
    }

    /** 未登录请求以客户端 IP 作为归属者，不把匿名流量都算到同一个固定值上。 */
    @Test
    void ownerIsClientIpWhenAnonymous() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint(new AttemptRequest("attempt-1", "answer"));
        when(idempotencyService.tryAcquire(any())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation());

        assertThat(capturedRequest().owner()).isEqualTo(CLIENT_IP);
    }

    /** 归属者写入前会去空白并截断到列长度，避免超长值导致数据库写入失败。 */
    @Test
    void ownerIsTrimmedAndTruncated() throws Throwable {
        setClientIp("  " + "a".repeat(100) + "  ");
        ProceedingJoinPoint joinPoint = joinPoint(new AttemptRequest("attempt-1", "answer"));
        when(idempotencyService.tryAcquire(any())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation());

        assertThat(capturedRequest().owner()).isEqualTo("a".repeat(64));
    }

    /** 缺少请求上下文时沿用 WebUtils 的 unknown 占位值，保证归属者始终非空。 */
    @Test
    void ownerIsUnknownWithoutRequestContext() throws Throwable {
        RequestContextHolder.resetRequestAttributes();
        ProceedingJoinPoint joinPoint = joinPoint(new AttemptRequest("attempt-1", "answer"));
        when(idempotencyService.tryAcquire(any())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation());

        assertThat(capturedRequest().owner()).isEqualTo("unknown");
    }

    /** 重复请求必须返回友好业务码，不能让唯一键冲突冒成 500。 */
    @Test
    void duplicateRequestReturnsConflict() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint(new AttemptRequest("attempt-1", "answer"));
        when(idempotencyService.tryAcquire(any())).thenReturn(false);

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.IDEMPOTENT_CONFLICT));

        verify(joinPoint, never()).proceed();
    }

    /** 业务失败时释放占位，允许客户端用同一个键重试。 */
    @Test
    void businessFailureReleasesRecord() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint(new AttemptRequest("attempt-1", "answer"));
        when(idempotencyService.tryAcquire(any())).thenReturn(true);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        verify(idempotencyService).release(any());
    }

    /** 表达式求值不到键时按参数错误返回，不占用记录。 */
    @Test
    void missingKeyReturnsValidationError() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint(new AttemptRequest(null, "answer"));

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));

        verify(idempotencyService, never()).tryAcquire(any());
        verify(joinPoint, never()).proceed();
    }

    private IdempotencyRequest capturedRequest() {
        ArgumentCaptor<IdempotencyRequest> captor =
                ArgumentCaptor.forClass(IdempotencyRequest.class);
        verify(idempotencyService).tryAcquire(captor.capture());
        return captor.getValue();
    }

    private static void setClientIp(String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/attempts");
        request.setRemoteAddr(clientIp);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static void authenticate(long userId) {
        YilianUserPrincipal principal =
                YilianUserPrincipal.of(userId, "user-" + userId, "USER", true);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                principal, "token", principal.getAuthorities()));
    }

    private static ProceedingJoinPoint joinPoint(AttemptRequest request)
            throws NoSuchMethodException {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = Sample.class.getDeclaredMethod("submit", AttemptRequest.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[] {request});
        return joinPoint;
    }

    private static Idempotent annotation() throws NoSuchMethodException {
        return Sample.class
                .getDeclaredMethod("submit", AttemptRequest.class)
                .getAnnotation(Idempotent.class);
    }

    /** 模拟答题提交请求；后续 attempts 表落地后可直接复用 attemptId。 */
    record AttemptRequest(String attemptId, String answer) {}

    /** 供反射读取注解的测试载体。 */
    static class Sample {

        @Idempotent(scope = "attempt-submit", key = "#request.attemptId()")
        void submit(AttemptRequest request) {
            // 仅用于测试注解元数据。
        }
    }
}
