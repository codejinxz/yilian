-- 未知异常落库表。
-- 业务异常（密码错误、参数校验、资源不存在等）属于预期结果，不写这张表；
-- 只有 ApiExceptionHandler 的未知 500 分支与会话/过滤器之外的未处理异常才记录。
CREATE TABLE error_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    trace_id        VARCHAR(64)  NOT NULL,
    module          VARCHAR(32)  NOT NULL,
    exception_class VARCHAR(255) NOT NULL,
    message         VARCHAR(2000) NULL,
    stack_trace     MEDIUMTEXT   NULL,
    request_method  VARCHAR(16)  NULL,
    request_path    VARCHAR(512) NULL,
    -- 脱敏后的用户标识，不保存明文用户 id；anonymous 表示未认证。
    user_id         VARCHAR(32)  NULL,
    client_ip       VARCHAR(64)  NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_error_log_created_at (created_at),
    KEY idx_error_log_module (module),
    KEY idx_error_log_trace_id (trace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
