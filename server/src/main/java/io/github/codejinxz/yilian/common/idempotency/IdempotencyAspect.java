package io.github.codejinxz.yilian.common.idempotency;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.security.SecurityUtils;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.common.util.WebUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等切面：拦截显式标注 {@link Idempotent} 的方法。
 *
 * <p>执行顺序在限流之后：先判断是否被限流，再占用幂等键。占用失败立即抛 {@code IDEMPOTENT_CONFLICT}；
 * 业务方法抛异常时删除本次占位，让客户端能用同一个键重试，避免一次失败把键永久占住。
 *
 * <p>每次占位都带归属者：已登录取用户 id，未登录取客户端 IP。归属者与作用域、键共同参与唯一性， 避免 A 用户先提交的键挡住 B 用户，也避免匿名用户之间仅凭业务键互相占用。
 * 归属者只写入数据库，不写日志；切面不记录幂等键与请求体，只记录作用域。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class IdempotencyAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
    private static final int MAX_OWNER_LENGTH = 64;
    private static final int MAX_SCOPE_LENGTH = 64;
    private static final int MAX_KEY_LENGTH = 191;
    private static final int MAX_EXPIRE_SECONDS = 30 * 24 * 60 * 60;

    /** 无法从安全上下文或请求中取到归属者时的兜底值，与迁移脚本的列默认值一致。 */
    private static final String ANONYMOUS_OWNER = "anonymous";

    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    private final IdempotencyService idempotencyService;
    private final Map<String, Expression> keyExpressions = new ConcurrentHashMap<>();

    /**
     * 注入幂等服务；切面自身只负责解析键与翻译结果。
     *
     * @param idempotencyService 幂等占位服务
     */
    public IdempotencyAspect(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    /**
     * 占用幂等键后执行原方法，重复请求返回 409 业务码。
     *
     * @param joinPoint 被拦截的方法调用
     * @param idempotent 方法上的幂等注解
     * @return 原方法返回值
     * @throws Throwable 原方法异常原样传递；重复请求抛业务异常
     */
    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] arguments = joinPoint.getArgs();
        IdempotencyRequest request =
                new IdempotencyRequest(
                        resolveOwner(),
                        resolveScope(idempotent, method),
                        resolveKey(idempotent, method, arguments),
                        IdempotencyHasher.hash(arguments),
                        Math.min(MAX_EXPIRE_SECONDS, Math.max(1L, idempotent.expiresInSeconds())));
        if (!idempotencyService.tryAcquire(request)) {
            log.info("idempotent replay rejected scope={}", request.scope());
            throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT);
        }
        try {
            return joinPoint.proceed();
        } catch (Throwable failure) {
            releaseQuietly(request);
            throw failure;
        }
    }

    private String resolveOwner() {
        String owner =
                SecurityUtils.currentUserId()
                        .map(String::valueOf)
                        .orElseGet(() -> WebUtils.clientIp(currentRequest()));
        if (StringUtils.isBlank(owner)) {
            return ANONYMOUS_OWNER;
        }
        return StringUtils.truncate(owner.trim(), MAX_OWNER_LENGTH);
    }

    private String resolveScope(Idempotent idempotent, Method method) {
        String scope = idempotent.scope();
        if (StringUtils.isBlank(scope)) {
            scope = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        }
        scope = scope.trim();
        return scope.length() <= MAX_SCOPE_LENGTH ? scope : scope.substring(0, MAX_SCOPE_LENGTH);
    }

    private String resolveKey(Idempotent idempotent, Method method, Object[] arguments) {
        Expression expression =
                keyExpressions.computeIfAbsent(
                        idempotent.key(), EXPRESSION_PARSER::parseExpression);
        MethodBasedEvaluationContext context =
                new MethodBasedEvaluationContext(
                        null, method, arguments, PARAMETER_NAME_DISCOVERER);
        Object value = expression.getValue(context);
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "请求缺少幂等键，请重试");
        }
        String key = String.valueOf(value).trim();
        if (key.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "幂等键过长");
        }
        return key;
    }

    private void releaseQuietly(IdempotencyRequest request) {
        try {
            idempotencyService.release(request);
        } catch (RuntimeException releaseFailure) {
            log.error(
                    "failed to release idempotency record scope={}",
                    request.scope(),
                    releaseFailure);
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
