package io.github.codejinxz.yilian.admin.dao;

/**
 * 审计事件写入行（{@code admin_audit_log} 单行 append；无 JPA 实体，走 JdbcTemplate 落库）。
 *
 * <p>module 恒为 {@code admin}、outcome 恒为 {@code SUCCESS}、created_at 由数据库默认值生成——
 * 该端点的语义就是「记一条审计」，只有落库成功才返回 id，失败直接抛错不吞。
 *
 * @param traceId 当前请求 traceId（MDC，可空）
 * @param actorId 操作管理员 id
 * @param actorName 操作管理员用户名
 * @param actorRole 操作管理员角色
 * @param clientIp 客户端 IP，可空
 * @param requestMethod 请求方法，可空
 * @param requestPath 请求路径，可空
 * @param action 审计动作（白名单校验后传入）
 * @param targetType 对象类型，可空
 * @param targetId 对象标识，可空
 * @param reason 操作原因，可空
 */
public record AdminAuditEventRow(
        String traceId,
        Long actorId,
        String actorName,
        String actorRole,
        String clientIp,
        String requestMethod,
        String requestPath,
        String action,
        String targetType,
        String targetId,
        String reason) {}
