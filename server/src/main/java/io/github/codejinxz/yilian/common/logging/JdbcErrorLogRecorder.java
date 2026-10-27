package io.github.codejinxz.yilian.common.logging;

import io.github.codejinxz.yilian.common.security.SecurityUtils;
import io.github.codejinxz.yilian.common.util.IdUtils;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.common.util.WebUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 使用 JdbcTemplate 写入 {@code error_log}，避免异常回滚影响审计记录。
 *
 * <p>只写未知异常；业务异常、参数校验、404/405/415、越权与 CSRF 不进入本表。写入前对 message/stackTrace 做敏感字段兜底遮盖和长度限制；user_id 存真实
 * BIGINT、operator 存用户名快照，供受控后台检索。 任何持久化异常只写服务端日志，不覆盖原始错误响应。
 */
@Service
public class JdbcErrorLogRecorder implements ErrorLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(JdbcErrorLogRecorder.class);
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_STACK_LENGTH = 16_000;
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_MODULE_LENGTH = 32;
    private static final int MAX_EXCEPTION_CLASS_LENGTH = 255;
    private static final int MAX_CLIENT_IP_LENGTH = 45;
    private static final int MAX_OPERATOR_LENGTH = 64;

    private static final String INSERT_SQL =
            "INSERT INTO error_log (trace_id, module, exception_class, message, stack_trace,"
                    + " request_method, request_path, user_id, operator, client_ip)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 注入共享数据源上的 JdbcTemplate。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public JdbcErrorLogRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(Throwable throwable, HttpServletRequest request) {
        try {
            String traceId = StringUtils.defaultIfBlank(MDC.get("traceId"), IdUtils.newCompactId());
            String path = request == null ? null : request.getRequestURI();
            String module =
                    StringUtils.truncate(RequestLoggingFilter.moduleOf(path), MAX_MODULE_LENGTH);
            Long userId = SecurityUtils.currentUserId().orElse(null);
            String operator =
                    StringUtils.truncate(
                            SecurityUtils.currentUsername().orElse(null), MAX_OPERATOR_LENGTH);
            String clientIp =
                    StringUtils.truncate(WebUtils.clientIp(request), MAX_CLIENT_IP_LENGTH);
            String exceptionClass =
                    StringUtils.truncate(
                            throwable.getClass().getName(), MAX_EXCEPTION_CLASS_LENGTH);
            String message =
                    LogMasking.sanitize(
                            StringUtils.truncate(throwable.getMessage(), MAX_MESSAGE_LENGTH));
            String stackTrace =
                    LogMasking.sanitize(
                            StringUtils.truncate(stackTraceOf(throwable), MAX_STACK_LENGTH));
            jdbcTemplate.update(
                    INSERT_SQL,
                    traceId,
                    module,
                    exceptionClass,
                    message,
                    stackTrace,
                    request == null ? null : request.getMethod(),
                    StringUtils.truncate(path, MAX_PATH_LENGTH),
                    userId,
                    operator,
                    clientIp);
        } catch (RuntimeException persistenceFailure) {
            log.error("failed to persist error log", persistenceFailure);
        }
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
