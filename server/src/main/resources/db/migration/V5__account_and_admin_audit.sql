-- 账号与审计收口（DATA-MODEL §5.1 / §5.14 / §5.15 / §12.1）。
-- V1 的 app_user 只服务 bootstrap；这里改名并补齐账号、时区、FSRS 设置与软删字段。
-- V3 的 operation_log 是旧审计表，字段无法恢复历史真实 actor，只能保留可迁移部分后删除。
-- V2 的 error_log 需要改成真实 user_id + operator 并按月分区，因此在本版本重建约束。
-- 已应用迁移不改 checksum；本文件是 V5，V1–V4 保持原样。

RENAME TABLE app_user TO users;

ALTER TABLE users
    MODIFY password_hash VARCHAR(255) NOT NULL,
    MODIFY enabled BOOLEAN NOT NULL DEFAULT TRUE,
    DROP INDEX uk_app_user_username,
    ADD COLUMN email VARCHAR(255) NULL AFTER id,
    ADD COLUMN password_changed BOOLEAN NOT NULL DEFAULT FALSE AFTER enabled,
    ADD COLUMN password_updated_at DATETIME(6) NULL AFTER password_changed,
    ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' AFTER password_updated_at,
    ADD COLUMN daily_goal SMALLINT NOT NULL DEFAULT 3 AFTER timezone,
    ADD COLUMN rating_standard VARCHAR(16) NOT NULL DEFAULT 'TEM8' AFTER daily_goal,
    ADD COLUMN desired_retention DECIMAL(4, 3) NOT NULL DEFAULT 0.900 AFTER rating_standard,
    ADD COLUMN theme VARCHAR(8) NOT NULL DEFAULT 'paper' AFTER desired_retention,
    ADD COLUMN notifications_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER theme,
    ADD COLUMN last_login_at DATETIME(6) NULL AFTER notifications_enabled,
    ADD COLUMN disabled_reason VARCHAR(255) NULL AFTER last_login_at,
    ADD COLUMN disabled_at DATETIME(6) NULL AFTER disabled_reason,
    ADD COLUMN deleted_at DATETIME(6) NULL AFTER disabled_at,
    ADD COLUMN deleted_by BIGINT NULL AFTER deleted_at,
    ADD UNIQUE KEY uk_users_email (email),
    ADD UNIQUE KEY uk_users_username (username),
    ADD KEY idx_users_role_enabled (role, enabled),
    ADD KEY idx_users_deleted_at (deleted_at),
    ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN')),
    ADD CONSTRAINT chk_users_daily_goal CHECK (daily_goal IN (1, 3, 5)),
    ADD CONSTRAINT chk_users_desired_retention CHECK (desired_retention BETWEEN 0.800 AND 0.970);

-- 邀请码只存 SHA-256 与前缀；一码一人（used_by 唯一）。
CREATE TABLE invite_codes (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    code_hash     CHAR(64)     NOT NULL,
    code_prefix   VARCHAR(12)  NOT NULL,
    max_uses      SMALLINT     NOT NULL DEFAULT 1,
    used_count    SMALLINT     NOT NULL DEFAULT 0,
    used_by       BIGINT       NULL,
    used_at       DATETIME(6)  NULL,
    expires_at    DATETIME(6)  NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by    BIGINT       NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at    DATETIME(6)  NULL,
    deleted_by    BIGINT       NULL,
    revoked_reason VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_invite_code_hash (code_hash),
    UNIQUE KEY uk_invite_used_by (used_by),
    KEY idx_invite_expires (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 系统配置只读表（编辑 M3）；键值用 JSON 并带 schemaVersion。
CREATE TABLE app_settings (
    `key`        VARCHAR(128) NOT NULL,
    `value`      JSON         NOT NULL,
    description  VARCHAR(255) NULL,
    version      INT          NOT NULL DEFAULT 1,
    updated_by   BIGINT       NULL,
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 管理员审计：真实 actor_id + 用户名/角色快照，按 created_at 月分区，保留 1 年。
-- 分区表不能建外键（MySQL 限制），actor_id 的 SET NULL 语义由应用层保证；索引保留。
CREATE TABLE admin_audit_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    trace_id       VARCHAR(64)  NULL,
    module         VARCHAR(32)  NOT NULL,
    actor_id       BIGINT       NULL,
    actor_name     VARCHAR(64)  NOT NULL,
    actor_role     VARCHAR(16)  NULL,
    client_ip      VARCHAR(45)  NULL,
    action         VARCHAR(64)  NOT NULL,
    target_type    VARCHAR(32)  NULL,
    target_id      VARCHAR(64)  NULL,
    reason         VARCHAR(500) NULL,
    detail         JSON         NULL,
    outcome        VARCHAR(16)  NOT NULL,
    duration_ms    INT          NULL,
    request_method VARCHAR(16)  NULL,
    request_path   VARCHAR(512) NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id, created_at),
    KEY idx_audit_created (created_at),
    KEY idx_audit_module (module, created_at),
    KEY idx_audit_actor (actor_id, created_at),
    KEY idx_audit_action (action, created_at),
    KEY idx_audit_target (target_type, target_id),
    CONSTRAINT chk_audit_module
        CHECK (module IN ('account', 'content', 'practice', 'ai', 'admin', 'analytics', 'system')),
    CONSTRAINT chk_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  PARTITION BY RANGE (TO_DAYS(created_at)) (
      PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
      PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
      PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
      PARTITION p202701 VALUES LESS THAN (TO_DAYS('2027-02-01')),
      PARTITION p_future VALUES LESS THAN MAXVALUE
  );

-- 只迁移旧表能对上的字段；旧 user_id 是脱敏值，无法还原真实 id，统一按未知处理。
INSERT INTO admin_audit_log (trace_id,
                             module,
                             actor_id,
                             actor_name,
                             actor_role,
                             client_ip,
                             action,
                             target_type,
                             target_id,
                             reason,
                             outcome,
                             duration_ms,
                             request_method,
                             request_path,
                             created_at)
SELECT trace_id,
       CASE module
           WHEN 'auth' THEN 'account'
           WHEN 'import' THEN 'content'
           WHEN 'practice' THEN 'practice'
           WHEN 'admin' THEN 'admin'
           WHEN 'content' THEN 'content'
           WHEN 'system' THEN 'system'
           ELSE 'system'
           END,
       NULL,
       'unknown',
       'SYSTEM',
       client_ip,
       action,
       NULL,
       NULL,
       NULL,
       CASE WHEN UPPER(outcome) = 'SUCCESS' THEN 'SUCCESS' ELSE 'FAILURE' END,
       CAST(duration_ms AS SIGNED),
       request_method,
       request_path,
       created_at
FROM operation_log;

DROP TABLE operation_log;

-- error_log：真实 BIGINT user_id + operator；分区键必须进主键。
-- 旧代码的 module 可能是 auth/import 等非枚举值，先归一，否则 CHECK 约束无法建立。
UPDATE error_log
SET module = CASE
                 WHEN module IN ('account', 'content', 'practice', 'ai', 'admin', 'analytics',
                                 'system') THEN module
                 WHEN module = 'auth' THEN 'account'
                 WHEN module = 'import' THEN 'content'
                 ELSE 'system'
    END;

ALTER TABLE error_log
    DROP PRIMARY KEY,
    DROP COLUMN user_id,
    MODIFY id BIGINT NOT NULL AUTO_INCREMENT,
    MODIFY client_ip VARCHAR(45) NULL,
    ADD COLUMN user_id BIGINT NULL AFTER request_path,
    ADD COLUMN operator VARCHAR(64) NULL AFTER user_id,
    ADD PRIMARY KEY (id, created_at),
    ADD KEY idx_error_user (user_id, created_at),
    ADD KEY idx_error_module_created (module, created_at),
    ADD CONSTRAINT chk_error_module
        CHECK (module IN ('account', 'content', 'practice', 'ai', 'admin', 'analytics', 'system'));

ALTER TABLE error_log
    PARTITION BY RANGE (TO_DAYS(created_at)) (
        PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
        PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
        PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
        PARTITION p202701 VALUES LESS THAN (TO_DAYS('2027-02-01')),
        PARTITION p_future VALUES LESS THAN MAXVALUE
        );

-- 幂等归属修复（原 V7 收口内容）：幂等键必须按 owner 隔离，否则 A 用户的 attemptId 会挡住 B 用户。
ALTER TABLE idempotency_record
    ADD COLUMN owner_id VARCHAR(64) NOT NULL DEFAULT 'anonymous' AFTER id,
    DROP INDEX uk_idempotency_record_key,
    ADD UNIQUE KEY uk_idempotency_record_owner_scope_key (owner_id, scope, idempotency_key);
