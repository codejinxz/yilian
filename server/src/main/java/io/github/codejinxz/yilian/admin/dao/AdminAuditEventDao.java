package io.github.codejinxz.yilian.admin.dao;

/**
 * 审计事件写入 Dao（{@code admin_audit_log} 单行 append）。
 *
 * <p>admin_audit_log 是 V5 按月分区表（主键含 created_at），没有 JPA 实体；写路径与 common 的 {@code
 * JdbcOperationLogRecorder} 同款走 JdbcTemplate（无实体的审计表， append-only 单行写入不引入 MyBatis/JPA
 * 映射）。动作名白名单校验在服务层完成，本 Dao 只落库。
 */
public interface AdminAuditEventDao {

    /**
     * 追加一条审计事件行；append-only，不做任何 UPDATE。
     *
     * @param row 写入行（module=admin、outcome=SUCCESS、created_at 由数据库默认值生成）
     * @return 数据库生成的审计行 id
     */
    long insert(AdminAuditEventRow row);
}
