package io.github.codejinxz.yilian.common.idempotency;

/**
 * 一次幂等占位请求。
 *
 * <p>归属者、作用域与幂等键三者共同确定唯一性：不同用户即使使用同一个业务键，也各自占位，互不遮挡； 同一个用户在不同作用域下也允许复用同一个键。
 *
 * @param owner 幂等归属者：登录用户 id 或匿名客户端 IP，只用于隔离占位，不写入日志
 * @param scope 业务作用域，参与唯一键
 * @param key 幂等键，对应 {@code idempotency_record.idempotency_key}
 * @param requestHash 请求参数的 SHA-256 摘要，不保存原文
 * @param expiresInSeconds 记录保留秒数
 */
public record IdempotencyRequest(
        String owner, String scope, String key, String requestHash, long expiresInSeconds) {}
