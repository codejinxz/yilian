-- 操作日志表：只记录显式标注 @OperationLog 的写操作。
-- 不记录参数/请求体/返回值；user_id 为脱敏值，operation 表保留 1 年。
CREATE TABLE operation_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    trace_id       VARCHAR(64)  NOT NULL,
    module         VARCHAR(32)  NOT NULL,
    action         VARCHAR(64)  NOT NULL,
    user_id        VARCHAR(32)  NULL,
    client_ip      VARCHAR(64)  NULL,
    outcome        VARCHAR(16)  NOT NULL,
    duration_ms    BIGINT       NOT NULL,
    error_type     VARCHAR(128) NULL,
    request_method VARCHAR(16)  NULL,
    request_path   VARCHAR(512) NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_operation_log_created_at (created_at),
    KEY idx_operation_log_module (module),
    KEY idx_operation_log_trace_id (trace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
