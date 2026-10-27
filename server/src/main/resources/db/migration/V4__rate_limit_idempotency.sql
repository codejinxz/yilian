-- 幂等占位表：以唯一键兜底重复提交，应用层只负责把冲突翻译成友好业务码。
-- 只提供通用能力，不绑定 attempts 等尚未落地的业务表；后续答题提交可直接复用 attemptId 作为幂等键。
-- 过期记录由后续清理任务按 expires_at 分批删除；本项目暂不提供自动清理，保留窗口由调用方按业务设置。
CREATE TABLE idempotency_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    -- 业务幂等键（如 attemptId）；全局唯一，唯一约束是幂等的最终判据。
    idempotency_key VARCHAR(191) NOT NULL,
    -- 业务作用域，仅用于排查、统计与后续清理，不参与唯一性判断。
    scope           VARCHAR(64)  NOT NULL,
    -- 请求参数的 SHA-256 摘要，只存摘要不存原文。
    request_hash    VARCHAR(64)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_record_key (idempotency_key),
    KEY idx_idempotency_record_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
