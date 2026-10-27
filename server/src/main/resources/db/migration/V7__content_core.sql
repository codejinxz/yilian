-- 题库、句子身份与不可变内容快照（DATA-MODEL §5.3 / §5.4 / §5.5 / §12.1）。
-- sentences.version 是银行级增量游标；sentence_versions 只增不改，attempt 绑定具体版本。
CREATE TABLE sentence_banks (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id     BIGINT       NULL,
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(500) NULL,
    visibility   VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE',
    status       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    version      BIGINT       NOT NULL DEFAULT 0,
    published_at DATETIME(6)  NULL,
    created_by   BIGINT       NULL,
    updated_by   BIGINT       NULL,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at   DATETIME(6)  NULL,
    deleted_by   BIGINT       NULL,
    PRIMARY KEY (id),
    KEY idx_bank_owner_status (owner_id, status),
    KEY idx_bank_visibility_status (visibility, status),
    KEY idx_bank_updated (updated_at),
    CONSTRAINT fk_bank_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_bank_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT chk_bank_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED', 'RECYCLED', 'ARCHIVED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE sentences (
    id                 CHAR(26)    NOT NULL,
    bank_id            BIGINT      NOT NULL,
    external_key       VARCHAR(64) NULL,
    status             VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    current_version_id BIGINT      NULL,
    version            BIGINT      NOT NULL DEFAULT 0,
    fingerprint        CHAR(64)    NOT NULL,
    active_fingerprint CHAR(64)
        GENERATED ALWAYS AS (IF(deleted_at IS NULL, fingerprint, NULL)) STORED,
    created_by         BIGINT      NULL,
    updated_by         BIGINT      NULL,
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at         DATETIME(6) NULL,
    deleted_by         BIGINT      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sentence_bank_external (bank_id, external_key),
    UNIQUE KEY uk_sentence_bank_active_fingerprint (bank_id, active_fingerprint),
    KEY idx_sentence_bank_version (bank_id, version),
    KEY idx_sentence_bank_status (bank_id, status, deleted_at),
    KEY idx_sentence_current_version (current_version_id),
    CONSTRAINT fk_sentence_bank FOREIGN KEY (bank_id) REFERENCES sentence_banks (id) ON DELETE RESTRICT,
    CONSTRAINT chk_sentence_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED', 'RECYCLED', 'ARCHIVED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE sentence_versions (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    sentence_id     CHAR(26)     NOT NULL,
    bank_id         BIGINT       NOT NULL,
    version_no      INT          NOT NULL,
    zh              VARCHAR(255) NOT NULL,
    ref             VARCHAR(512) NOT NULL,
    note            VARCHAR(1000) NOT NULL,
    chunks          JSON         NOT NULL,
    blank           JSON         NOT NULL,
    tags            JSON         NOT NULL,
    level           VARCHAR(32)  NULL,
    source          VARCHAR(128) NULL,
    license         VARCHAR(64)  NULL,
    content_hash    CHAR(64)     NOT NULL,
    schema_version  SMALLINT     NOT NULL DEFAULT 1,
    import_batch_id CHAR(36)     NULL,
    created_by      BIGINT       NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sentence_version (sentence_id, version_no),
    KEY idx_sentence_version_bank (bank_id, created_at),
    KEY idx_sentence_version_hash (content_hash),
    CONSTRAINT fk_sentence_version_sentence FOREIGN KEY (sentence_id) REFERENCES sentences (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_sentence_version_bank FOREIGN KEY (bank_id) REFERENCES sentence_banks (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_sentence_version_batch FOREIGN KEY (import_batch_id) REFERENCES import_batches (id)
        ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 循环引用：先建 sentences，后补 current_version_id 外键。
ALTER TABLE sentences
    ADD CONSTRAINT fk_sentence_current_version FOREIGN KEY (current_version_id)
        REFERENCES sentence_versions (id) ON DELETE RESTRICT;

-- import_batches / import_batch_rows 在 V6 建表；当时 sentence_banks/sentences 还不存在，这里补外键。
ALTER TABLE import_batches
    ADD CONSTRAINT fk_import_bank FOREIGN KEY (bank_id) REFERENCES sentence_banks (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_import_creator FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE RESTRICT;

ALTER TABLE import_batch_rows
    ADD CONSTRAINT fk_import_row_batch FOREIGN KEY (batch_id) REFERENCES import_batches (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_import_row_sentence FOREIGN KEY (sentence_id) REFERENCES sentences (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_import_row_version FOREIGN KEY (sentence_version_id)
        REFERENCES sentence_versions (id) ON DELETE RESTRICT;

-- 快照不可变：改题必须新增 version_no，不能覆盖历史。
CREATE TRIGGER trg_sentence_versions_no_update
    BEFORE UPDATE
    ON sentence_versions
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sentence_versions is immutable';

CREATE TRIGGER trg_sentence_versions_no_delete
    BEFORE DELETE
    ON sentence_versions
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sentence_versions is immutable';
