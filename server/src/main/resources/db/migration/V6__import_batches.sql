-- 导入批次按 PRD 重建（DATA-MODEL §5.6 / §5.7 / §12.1）。
-- 旧 import_batch 是上传→PROCESSING→resume 的过渡基础设施，与 preview→commit 契约冲突；
-- 旧表数据没有题目关联价值，直接删除后重建，避免把旧状态机带进新接口。
DROP TABLE IF EXISTS import_batch;

CREATE TABLE import_batches (
    id                CHAR(36)     NOT NULL,
    bank_id           BIGINT       NOT NULL,
    format            VARCHAR(8)   NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_key       VARCHAR(512) NOT NULL,
    content_hash      CHAR(64)     NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PREVIEWING',
    total_rows        BIGINT       NOT NULL DEFAULT 0,
    valid_rows        BIGINT       NOT NULL DEFAULT 0,
    invalid_rows      BIGINT       NOT NULL DEFAULT 0,
    imported_rows     BIGINT       NOT NULL DEFAULT 0,
    error_summary     JSON         NULL,
    created_by        BIGINT       NOT NULL,
    created_by_name   VARCHAR(64)  NOT NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    previewed_at      DATETIME(6)  NULL,
    committed_at      DATETIME(6)  NULL,
    rolled_back_at    DATETIME(6)  NULL,
    rolled_back_by    BIGINT       NULL,
    rollback_reason   VARCHAR(500) NULL,
    PRIMARY KEY (id),
    KEY idx_import_bank_created (bank_id, created_at),
    KEY idx_import_status (status),
    KEY idx_import_creator (created_by, created_at),
    CONSTRAINT chk_import_format CHECK (format IN ('JSON', 'CSV')),
    CONSTRAINT chk_import_status CHECK (status IN
        ('PREVIEWING', 'PREVIEWED', 'COMMITTING', 'COMMITTED', 'FAILED', 'ROLLED_BACK', 'EXPIRED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE import_batch_rows (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    batch_id            CHAR(36)    NOT NULL,
    row_no              INT         NOT NULL,
    external_key        VARCHAR(64) NULL,
    raw                 JSON        NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'VALID',
    error_codes         JSON        NULL,
    sentence_id         CHAR(26)    NULL,
    sentence_version_id BIGINT      NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_import_row (batch_id, row_no),
    KEY idx_import_row_status (batch_id, status),
    KEY idx_import_row_sentence (sentence_id),
    CONSTRAINT chk_import_row_status CHECK (status IN ('VALID', 'INVALID', 'IMPORTED', 'SKIPPED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
