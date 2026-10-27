package io.github.codejinxz.yilian.common.ratelimit;

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
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流切面：拦截显式标注 {@link RateLimit} 的方法。
 *
 * <p>只采集场景、维度、来源 IP 与账号表达式结果，不读取、不记录请求体；日志中不出现账号、IP 或参数值。 超限抛出 {@link
 * RateLimitExceededException}，由全局异常出口转成 429 + {@code Retry-After}。 切面顺序在幂等切面之前：被限流的请求不再占用幂等记录。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);
    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    private final RateLimitService rateLimitService;
    private final Map<String, Expression> accountExpressions = new ConcurrentHashMap<>();

    /** 集成测试可关闭限流，避免共享 Redis 计数污染；生产默认开启。 */
    @Value("${yilian.ratelimit.enabled:true}")
    private boolean enabled = true;

    /**
     * 注入限流服务；切面自身只负责解析注解与请求上下文。
     *
     * @param rateLimitService Redis 滑动窗口限流器
     */
    public RateLimitAspect(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    /**
     * 执行限流判定，未超限时继续调用原方法。
     *
     * @param joinPoint 被拦截的方法调用
     * @param rateLimit 方法上的限流注解
     * @return 原方法返回值
     * @throws Throwable 原方法抛出的异常原样传递；超限时抛出限流业务异常
     */
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        if (!enabled) {
            return joinPoint.proceed();
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        List<RateLimitDimension> dimensions = List.of(rateLimit.dimensions());
        String account =
                dimensions.contains(RateLimitDimension.ACCOUNT)
                        ? resolveAccount(rateLimit, method, joinPoint.getArgs())
                        : null;
        if (dimensions.contains(RateLimitDimension.ACCOUNT) && StringUtils.isBlank(account)) {
            log.warn(
                    "rate limit account dimension skipped scene={} reason=no-account",
                    rateLimit.scene());
        }

        RateLimitDecision decision =
                rateLimitService.acquire(
                        rateLimit.scene(),
                        dimensions,
                        rateLimit.limit(),
                        rateLimit.windowSeconds(),
                        WebUtils.clientIp(currentRequest()),
                        account);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
        return joinPoint.proceed();
    }

    private String resolveAccount(RateLimit rateLimit, Method method, Object[] arguments) {
        if (StringUtils.isBlank(rateLimit.accountExpression())) {
            return SecurityUtils.currentUserId().map(String::valueOf).orElse(null);
        }
        Expression expression =
                accountExpressions.computeIfAbsent(
                        rateLimit.accountExpression(), EXPRESSION_PARSER::parseExpression);
        MethodBasedEvaluationContext context =
                new MethodBasedEvaluationContext(
                        null, method, arguments, PARAMETER_NAME_DISCOVERER);
        Object value = expression.getValue(context);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
