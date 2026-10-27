package io.github.codejinxz.yilian.common.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要幂等保护的方法。
 *
 * <p>以数据库唯一约束为准，应用层只把重复插入翻译成友好业务码；不在内存或 Redis 里 维护「已处理」状态，避免重启或多实例造成漏判。唯一约束包含归属者、作用域与键，
 * 归属者由切面从登录用户或客户端 IP 解析，不同用户使用同一个业务键也不会互相占位。{@code key} 是 SpEL 表达式， 直接引用方法参数，例如 {@code
 * "#request.attemptId()"}；后续 {@code attempts} 表落地后，答题提交处可直接复用 attemptId，不需要改本组件。
 *
 * <p>键值不写日志；请求体只计算 SHA-256 摘要存入 {@code request_hash}，不保存原文。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * 业务作用域，用于排查和后续清理；为空时取「类名#方法名」。
     *
     * @return 稳定英文标识，例如 {@code attempt-submit}
     */
    String scope() default "";

    /**
     * 幂等键的 SpEL 表达式，必须能从方法参数中求值出非空值。
     *
     * <p>参数名依赖编译期 {@code -parameters}，Spring Boot 默认已开启；例如 {@code "#request.attemptId()"}。
     *
     * @return SpEL 表达式，结果超过 191 字符会被拒绝
     */
    String key();

    /**
     * 幂等记录保留时长，单位秒。
     *
     * @return 正整数，默认 24 小时
     */
    long expiresInSeconds() default 86_400L;
}
