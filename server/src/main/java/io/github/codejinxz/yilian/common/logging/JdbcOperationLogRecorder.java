package io.github.codejinxz.yilian.common.logging;

import io.github.codejinxz.yilian.common.util.IdUtils;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.common.util.WebUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 使用 JdbcTemplate 写入 {@code admin_audit_log}。
 *
 * <p>只保存方法级元数据、真实 actor id/用户名/角色、IP、结果和耗时，不保存参数、请求体、返回值或异常堆栈。 写入失败只记服务端日志，不影响业务响应。
 */
@Service
public class JdbcOperationLogRecorder implements OperationLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(JdbcOperationLogRecorder.class);
    private static final String INSERT_SQL =
            "INSERT INTO admin_audit_log (trace_id, module, actor_id, actor_name, actor_role,"
                    + " client_ip, action, target_type, target_id, reason, outcome, duration_ms,"
                    + " request_method, request_path)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 注入共享数据源上的 JdbcTemplate。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public JdbcOperationLogRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(
            OperationLog operation,
            String targetType,
            String targetId,
            String reason,
            String outcome,
            long durationMs,
            Throwable failure,
            Long actorId,
            String actorName,
            String actorRole) {
        try {
            HttpServletRequest request = currentRequest();
            String path = request == null ? null : request.getRequestURI();
            jdbcTemplate.update(
                    INSERT_SQL,
                    StringUtils.defaultIfBlank(MDC.get("traceId"), IdUtils.newCompactId()),
                    normalizeModule(operation.module()),
                    actorId,
                    StringUtils.truncate(StringUtils.defaultIfBlank(actorName, "unknown"), 64),
                    StringUtils.truncate(StringUtils.defaultIfBlank(actorRole, "SYSTEM"), 16),
                    StringUtils.truncate(WebUtils.clientIp(request), 45),
                    StringUtils.truncate(operation.action(), 64),
                    auditText(targetType, 32),
                    auditText(targetId, 64),
                    auditText(reason, 500),
                    "FAILURE".equals(outcome) ? "FAILURE" : "SUCCESS",
                    (int) Math.min(durationMs, Integer.MAX_VALUE),
                    request == null ? null : request.getMethod(),
                    StringUtils.truncate(path, 512));
        } catch (RuntimeException persistenceFailure) {
            log.error("failed to persist admin audit log", persistenceFailure);
        }
    }

    /**
     * 旧注解可能仍写 auth/import；落库前映射到 DATA-MODEL §7 允许的模块，避免违反 CHECK 约束。
     *
     * @param module 注解模块名
     * @return 合法模块名
     */
    private static String normalizeModule(String module) {
        String value = StringUtils.defaultIfBlank(module, "system");
        return switch (value) {
            case "auth" -> "account";
            case "import" -> "content";
            case "account", "content", "practice", "ai", "admin", "analytics", "system" -> value;
            default -> "system";
        };
    }

    /**
     * 把注解的可选审计字段归一为数据库可存的值。
     *
     * @param value 注解字段原值
     * @param maxLength 数据库列宽
     * @return 去空白后截断的值；空白输入返回 null
     */
    private static String auditText(String value, int maxLength) {
        return StringUtils.truncate(StringUtils.defaultIfBlank(value, null), maxLength);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
