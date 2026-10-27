package io.github.codejinxz.yilian.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要限流的方法。
 *
 * <p>只标在登录、深度批改、邀请码校验等有明确成本或被滥用的入口上，不标读接口，避免给正常 查询增加一次 Redis 往返。切面只读取方法签名与请求来源，不读取、不记录请求体；
 * 账号表达式的结果仅用于拼 Redis 键，日志中只出现场景名与维度。
 *
 * <p>三个典型配置：
 *
 * <pre>{@code
 * @RateLimit(scene = "login", dimensions = {IP, ACCOUNT}, limit = 10, windowSeconds = 60,
 *         accountExpression = "#request.username()")
 * @RateLimit(scene = "deep-grade", dimensions = ACCOUNT, limit = 20, windowSeconds = 3600)
 * @RateLimit(scene = "invite-code", dimensions = IP, limit = 5, windowSeconds = 600)
 * }</pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 业务场景名，用于隔离不同入口的计数桶，不得包含用户输入。
     *
     * @return 稳定英文标识，例如 {@code login}、{@code deep-grade}、{@code invite-code}
     */
    String scene();

    /**
     * 参与计数的维度，可组合 IP + 账号实现双维度限流。
     *
     * @return 维度列表，默认只按 IP
     */
    RateLimitDimension[] dimensions() default {RateLimitDimension.IP};

    /**
     * 窗口内允许的最大请求数。
     *
     * @return 正整数，超过即拒绝
     */
    int limit();

    /**
     * 滑动窗口长度，单位秒。
     *
     * @return 正整数
     */
    int windowSeconds();

    /**
     * 账号维度的 SpEL 表达式；为空时回退到当前登录用户 id。
     *
     * <p>登录发生在认证之前，没有安全上下文，因此需要显式指向请求体里的用户名，例如 {@code "#request.username()"}。表达式结果只参与键拼接，不写日志。
     *
     * @return SpEL 表达式，默认空串表示使用登录用户 id
     */
    String accountExpression() default "";
}
