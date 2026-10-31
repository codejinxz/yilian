package io.github.codejinxz.yilian.admin.dao;

import java.time.Instant;

/**
 * 管理活动行（只读 DTO，MyBatis 直出 admin_audit_log）。
 *
 * <p>admin_audit_log 是 V5 的按月分区表，无 JPA 实体，恰好用 MyBatis 只读 Dao 承载； {@code action/targetType/targetId}
 * 由服务层组装成人类可读的 {@code what}，Dao 层不做拼接。
 *
 * @param id 审计行 id
 * @param actorName 操作者用户名快照
 * @param action 动作标识，如 USER_STATUS_CHANGE
 * @param targetType 对象类型，可为 null
 * @param targetId 对象标识，可为 null
 * @param createdAt 发生时间（UTC）
 */
public record AuditActivityRow(
        long id,
        String actorName,
        String action,
        String targetType,
        String targetId,
        Instant createdAt) {}
