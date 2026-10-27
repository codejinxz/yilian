package io.github.codejinxz.yilian.common.logging;

import io.github.codejinxz.yilian.common.security.SecurityUtils;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.common.util.WebUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作日志切面：只拦截显式标注 {@link OperationLog} 的方法。
 *
 * <p>记录操作模块、动作、脱敏用户、客户端 IP、成功/失败与耗时；不记录方法参数、请求体和 返回值，避免密码、token、完整译文进入日志。异常会原样抛回，由全局异常出口决定是否写
 * {@code error_log}；本切面不吞异常，也不重复堆栈。
 *
 * <p>审计对象与原因优先取 {@link OperationLogContext} 中业务方法写入的动态值，空白字段回退到注解静态值； 上下文在进入方法前和 finally
 * 中各清理一次，避免线程复用时串数据。
 */
@Aspect
@Component
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);

    private final OperationLogRecorder operationLogRecorder;

    /**
     * 注入操作日志落库器；切面自身只负责采集与输出摘要。
     *
     * @param operationLogRecorder 操作日志落库入口
     */
    public OperationLogAspect(OperationLogRecorder operationLogRecorder) {
        this.operationLogRecorder = operationLogRecorder;
    }

    /**
     * 包住标注方法的执行，成功或异常都写一条操作摘要。
     *
     * @param joinPoint 被拦截的方法调用
     * @param operationLog 方法上的操作日志注解
     * @return 原方法返回值
     * @throws Throwable 原方法抛出的异常原样传递
     */
    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog)
            throws Throwable {
        long startedAt = System.nanoTime();
        // 先清一次，防止线程复用时把上一个请求遗留的上下文当成本次操作。
        OperationLogContext.clear();
        // 登出会在 service 内清空 SecurityContext，必须进入方法前先捕获操作者。
        OperationActor actorBefore = captureActor();
        String outcome = "SUCCESS";
        Throwable failure = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            outcome = "FAILURE";
            failure = throwable;
            throw throwable;
        } finally {
            OperationActor actorAfter = captureActor();
            OperationLogContext.AuditOverride auditOverride =
                    OperationLogContext.current().orElse(null);
            try {
                writeOperationLog(
                        operationLog,
                        auditOverride,
                        outcome,
                        failure,
                        startedAt,
                        actorAfter == null ? actorBefore : actorAfter);
            } finally {
                // 业务方法只负责写，清理必须放在切面里，且无论落库是否失败都要执行。
                OperationLogContext.clear();
            }
        }
    }

    /**
     * 进入/离开方法时抓取真实操作者；复用审计覆盖值载体，避免再建一个 DTO。
     *
     * @return 操作者 id/用户名/角色；未认证时返回 null
     */
    private static OperationActor captureActor() {
        return SecurityUtils.currentPrincipal()
                .map(
                        principal ->
                                new OperationActor(
                                        principal.id(), principal.getUsername(), principal.role()))
                .orElse(null);
    }

    private void writeOperationLog(
            OperationLog operationLog,
            OperationLogContext.AuditOverride auditOverride,
            String outcome,
            Throwable failure,
            long startedAt,
            OperationActor actor) {
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        OperationActor effectiveActor = actor == null ? OperationActor.SYSTEM : actor;
        Long actorId = effectiveActor.id();
        MDC.put("operationModule", operationLog.module());
        MDC.put("operationAction", operationLog.action());
        MDC.put("operationOutcome", outcome);
        MDC.put("operationDurationMs", Long.toString(durationMs));
        MDC.put("userId", LogMasking.maskUserId(actorId));
        MDC.put("clientIp", WebUtils.clientIp(currentRequest()));
        if (failure != null) {
            MDC.put("operationErrorType", failure.getClass().getSimpleName());
        }
        try {
            log.info("operation completed");
            // 上下文优先于注解静态值；两者都没有时仍透传空串，由落库实现归一为 NULL。
            operationLogRecorder.record(
                    operationLog,
                    resolve(
                            auditOverride == null ? null : auditOverride.targetType(),
                            operationLog.targetType()),
                    resolve(
                            auditOverride == null ? null : auditOverride.targetId(),
                            operationLog.targetId()),
                    resolve(
                            auditOverride == null ? null : auditOverride.reason(),
                            operationLog.reason()),
                    outcome,
                    durationMs,
                    failure,
                    actorId,
                    effectiveActor.name(),
                    effectiveActor.role());
        } finally {
            MDC.remove("operationModule");
            MDC.remove("operationAction");
            MDC.remove("operationOutcome");
            MDC.remove("operationDurationMs");
            MDC.remove("operationErrorType");
        }
    }

    /**
     * 上下文值非空白时优先使用，否则回退到注解静态值。
     *
     * @param contextValue 业务方法写入的动态值，可为 null
     * @param annotationValue 注解声明的静态值，未声明时为空串
     * @return 最终写入审计日志的值
     */
    private static String resolve(String contextValue, String annotationValue) {
        return StringUtils.defaultIfBlank(contextValue, annotationValue);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
