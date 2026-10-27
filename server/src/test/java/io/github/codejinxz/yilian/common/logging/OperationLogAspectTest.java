package io.github.codejinxz.yilian.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

/** {@link OperationLogAspect} 的注解识别、异常透传与 MDC 清理。 */
class OperationLogAspectTest {

    private final OperationLogRecorder recorder = mock(OperationLogRecorder.class);
    private final OperationLogAspect aspect = new OperationLogAspect(recorder);

    @BeforeEach
    void setUp() {
        // 防御性清场：用例顺序由 JVM 决定，上一个用例遗留的 SecurityContext 会让 actor 断言失真。
        SecurityContextHolder.clearContext();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(
                        new MockHttpServletRequest("POST", "/api/auth/login")));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        OperationLogContext.clear();
        SecurityContextHolder.clearContext();
    }

    /**
     * 成功时原样返回，并在结束后清理操作日志 MDC。
     *
     * @throws Throwable 切面执行异常
     */
    @Test
    void successPassesThroughAndClearsMdc() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("login");
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.around(joinPoint, annotation("login"));

        assertThat(result).isEqualTo("ok");
        verify(joinPoint).proceed();
        // 未声明 target/reason 的 login 仍按空值透传，不改变既有行为。
        verify(recorder)
                .record(
                        any(),
                        eq(""),
                        eq(""),
                        eq(""),
                        eq("SUCCESS"),
                        anyLong(),
                        isNull(),
                        isNull(),
                        eq("unknown"),
                        eq("SYSTEM"));
        assertThat(org.slf4j.MDC.get("operationAction")).isNull();
    }

    /**
     * 已登录操作者的 actor 快照必须透传 SecurityContext 中的 principal，而不是回退 SYSTEM 占位， 否则审计日志无法定位到真实操作者。
     *
     * @throws Throwable 切面执行异常
     */
    @Test
    void authenticatedActorIsForwardedToRecorder() throws Throwable {
        YilianUserPrincipal principal = YilianUserPrincipal.of(42L, "admin", "ADMIN", true);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal, "", principal.getAuthorities()));

        ProceedingJoinPoint joinPoint = joinPoint("login");
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation("login"));

        verify(recorder)
                .record(
                        any(),
                        eq(""),
                        eq(""),
                        eq(""),
                        eq("SUCCESS"),
                        anyLong(),
                        isNull(),
                        eq(42L),
                        eq("admin"),
                        eq("ADMIN"));
    }

    /**
     * 注解上的审计对象与原因必须原样透传给落库接口。
     *
     * @throws Throwable 切面执行异常
     */
    @Test
    void forwardsAuditTargetAndReason() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("viewOriginal");
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation("viewOriginal"));

        ArgumentCaptor<OperationLog> operationCaptor = ArgumentCaptor.forClass(OperationLog.class);
        verify(recorder)
                .record(
                        operationCaptor.capture(),
                        eq("attempt"),
                        eq("att-42"),
                        eq("support-ticket-7"),
                        eq("SUCCESS"),
                        anyLong(),
                        isNull(),
                        isNull(),
                        eq("unknown"),
                        eq("SYSTEM"));
        assertThat(operationCaptor.getValue().module()).isEqualTo("admin");
        assertThat(operationCaptor.getValue().action()).isEqualTo("view-original");
    }

    /**
     * 业务方法写入的上下文必须覆盖注解里的静态审计字段。
     *
     * @throws Throwable 切面执行异常
     */
    @Test
    void contextOverridesAnnotationAuditFields() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("viewOriginal");
        when(joinPoint.proceed())
                .thenAnswer(
                        invocation -> {
                            OperationLogContext.set("import-batch", "batch-42", "管理员上传导入文件");
                            return "ok";
                        });

        aspect.around(joinPoint, annotation("viewOriginal"));

        verify(recorder)
                .record(
                        any(),
                        eq("import-batch"),
                        eq("batch-42"),
                        eq("管理员上传导入文件"),
                        eq("SUCCESS"),
                        anyLong(),
                        isNull(),
                        isNull(),
                        eq("unknown"),
                        eq("SYSTEM"));
        assertThat(OperationLogContext.current()).isEmpty();
    }

    /**
     * 上下文只覆盖非空白字段，其余字段继续使用注解静态值。
     *
     * @throws Throwable 切面执行异常
     */
    @Test
    void blankContextFieldsFallBackToAnnotation() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("viewOriginal");
        when(joinPoint.proceed())
                .thenAnswer(
                        invocation -> {
                            OperationLogContext.set(null, "att-99", " ");
                            return "ok";
                        });

        aspect.around(joinPoint, annotation("viewOriginal"));

        verify(recorder)
                .record(
                        any(),
                        eq("attempt"),
                        eq("att-99"),
                        eq("support-ticket-7"),
                        eq("SUCCESS"),
                        anyLong(),
                        isNull(),
                        isNull(),
                        eq("unknown"),
                        eq("SYSTEM"));
    }

    /**
     * 前置请求遗留的上下文不能串到下一次操作，操作结束后也必须清空。
     *
     * @throws Throwable 切面执行异常
     */
    @Test
    void staleContextDoesNotLeakIntoNextOperation() throws Throwable {
        OperationLogContext.set("import-batch", "batch-stale", "上一个请求");

        ProceedingJoinPoint joinPoint = joinPoint("login");
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation("login"));

        verify(recorder)
                .record(
                        any(),
                        eq(""),
                        eq(""),
                        eq(""),
                        eq("SUCCESS"),
                        anyLong(),
                        isNull(),
                        isNull(),
                        eq("unknown"),
                        eq("SYSTEM"));
        assertThat(OperationLogContext.current()).isEmpty();
    }

    /**
     * 方法抛异常时仍要写入上下文里的审计字段，并在 finally 中清理线程变量。
     *
     * @throws Throwable 切面执行异常
     */
    @Test
    void contextIsClearedAfterFailure() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("viewOriginal");
        when(joinPoint.proceed())
                .thenAnswer(
                        invocation -> {
                            OperationLogContext.set("import-batch", "batch-err", "管理员上传导入文件");
                            throw new IllegalStateException("boom");
                        });

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation("viewOriginal")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        verify(recorder)
                .record(
                        any(),
                        eq("import-batch"),
                        eq("batch-err"),
                        eq("管理员上传导入文件"),
                        eq("FAILURE"),
                        anyLong(),
                        any(),
                        isNull(),
                        eq("unknown"),
                        eq("SYSTEM"));
        assertThat(OperationLogContext.current()).isEmpty();
    }

    /**
     * 异常必须原样抛回，不能在切面内吞掉。
     *
     * @throws Throwable 切面执行异常
     */
    @Test
    void exceptionIsRethrown() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("logout");
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation("logout")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        verify(recorder)
                .record(
                        any(),
                        eq(""),
                        eq(""),
                        eq(""),
                        eq("FAILURE"),
                        anyLong(),
                        any(),
                        isNull(),
                        eq("unknown"),
                        eq("SYSTEM"));
    }

    /**
     * 构造只携带目标方法元数据的 join point，避免在单元测试里拉起完整的 Spring AOP 代理与容器。
     *
     * <p>切面只通过 {@code signature.getMethod()} 反射读注解并调用 {@code proceed()}，mock 这两处即可驱动 around 全流程。
     *
     * @param methodName {@link Sample} 中的载体方法名
     * @return 可传给切面的 join point
     * @throws NoSuchMethodException 载体方法不存在时抛出
     */
    private static ProceedingJoinPoint joinPoint(String methodName) throws NoSuchMethodException {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(Sample.class.getDeclaredMethod(methodName));
        return joinPoint;
    }

    /**
     * 按方法名读取载体方法上的操作日志注解，使测试数据与生产注解保持同源，避免各用例手写注解字面量后漂移。
     *
     * @param methodName {@link Sample} 中的载体方法名
     * @return 方法上的操作日志注解
     * @throws NoSuchMethodException 载体方法不存在时抛出
     */
    private static OperationLog annotation(String methodName) throws NoSuchMethodException {
        Method method = Sample.class.getDeclaredMethod(methodName);
        return method.getAnnotation(OperationLog.class);
    }

    /** 供反射读取注解的测试载体。 */
    static class Sample {

        @OperationLog(module = "auth", action = "login")
        void login() {
            // 仅用于测试注解元数据。
        }

        @OperationLog(module = "auth", action = "logout")
        void logout() {
            // 仅用于测试注解元数据。
        }

        @OperationLog(
                module = "admin",
                action = "view-original",
                targetType = "attempt",
                targetId = "att-42",
                reason = "support-ticket-7")
        void viewOriginal() {
            // 仅用于验证审计对象与原因的反射读取和切面透传。
        }
    }
}
