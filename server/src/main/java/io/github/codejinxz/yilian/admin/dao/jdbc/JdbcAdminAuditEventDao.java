package io.github.codejinxz.yilian.admin.dao.jdbc;

import io.github.codejinxz.yilian.admin.dao.AdminAuditEventDao;
import io.github.codejinxz.yilian.admin.dao.AdminAuditEventRow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;

/** {@link AdminAuditEventDao} 的 JdbcTemplate 适配器；append-only 单行写入，回读自增主键。 */
@Repository
public class JdbcAdminAuditEventDao implements AdminAuditEventDao {

    private static final String INSERT_SQL =
            "INSERT INTO admin_audit_log (trace_id, module, actor_id, actor_name, actor_role,"
                    + " client_ip, action, target_type, target_id, reason, outcome, duration_ms,"
                    + " request_method, request_path)"
                    + " VALUES (?, 'admin', ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCESS', NULL, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 注入共享数据源上的 JdbcTemplate。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public JdbcAdminAuditEventDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条审计事件行。
     *
     * @param row 写入行
     * @return 数据库生成的审计行 id
     * @throws IllegalStateException 数据库未回传自增主键时抛出（不该发生，防御性收敛）
     */
    @Override
    public long insert(AdminAuditEventRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
                    setObjectOrNull(statement, 1, row.traceId());
                    setObjectOrNull(statement, 2, row.actorId());
                    statement.setString(3, row.actorName());
                    setObjectOrNull(statement, 4, row.actorRole());
                    setObjectOrNull(statement, 5, row.clientIp());
                    statement.setString(6, row.action());
                    setObjectOrNull(statement, 7, row.targetType());
                    setObjectOrNull(statement, 8, row.targetId());
                    setObjectOrNull(statement, 9, row.reason());
                    setObjectOrNull(statement, 10, row.requestMethod());
                    setObjectOrNull(statement, 11, row.requestPath());
                    return statement;
                },
                keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("admin_audit_log 未返回自增主键");
        }
        return key.longValue();
    }

    /**
     * 可空参数绑定；null 显式置 SQL NULL，避免 String 重载歧义。
     *
     * @param statement 预编译语句
     * @param index 参数序号（1 基）
     * @param value 参数值，可空
     */
    private static void setObjectOrNull(PreparedStatement statement, int index, Object value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
            return;
        }
        statement.setObject(index, value);
    }
}
