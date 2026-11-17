-- V15：管理员 2FA（TOTP 自研 RFC 6238）——users 三列 + 恢复码表 user_2fa_recovery_codes。
--
-- 背景：pending-20261117 P0-01（公网部署硬前置，AGENTS §3.7「公网部署前管理员启用 2FA」）。
-- 形状裁决：
--   a) users.totp_secret_cipher VARCHAR(512) NULL：TOTP 密钥（20 字节 SecureRandom）经
--      SecretCipher（AES-256-GCM，主密钥只走 YILIAN_SECRET_MASTER_KEY，与 027B 同纪律）
--      加密后的 Base64 密文——Base64(12B IV ‖ 20B+16B tag) ≈ 68 字符，512 留足余量；
--      列上无明文。enrollment 分两步（setup 落密文、activate 才启用），setup 重复调用
--      覆盖旧密文（作废旧密钥），disable/reset-2fa 清回 NULL。
--   b) users.totp_enabled BOOLEAN NOT NULL DEFAULT FALSE：登录第二步开关；存量账号零
--      影响（黑盒 304 套件期望不变）。开关与密文分列，便于「已 setup 未 activate」的
--      中间态（enabled=FALSE 但密文已在）。
--   c) users.totp_enrolled_at DATETIME(6) NULL：启用时刻留痕；未启用为 NULL。
--   d) user_2fa_recovery_codes：一次性恢复码投影表。明文只在 activate 响应里出现一次，
--      库内只存 BCrypt 哈希（code_hash VARCHAR(255)，与 users.password_hash 同长）；
--      使用即落 used_at（append-only 语义：不删行、不重置），verify 时按 user_id 取
--      未用行逐条 BCrypt 比对。disable / reset-2fa 清 enrollment 时删行（恢复码是
--      凭据投影而非答题/审计记录：残留旧码在重新 enrollment 后仍可登录，属安全漏洞，
--      删除是安全要求而非历史抹除）。
--   e) 零物理外键（仓库纪律）：user_id 逻辑引用 users.id；级联清理由应用层同事务保证。
-- 回滚：DROP TABLE user_2fa_recovery_codes；ALTER TABLE users DROP 三列。

ALTER TABLE users
    ADD COLUMN totp_secret_cipher VARCHAR(512) NULL
        COMMENT 'TOTP 密钥密文：SecretCipher AES-256-GCM（主密钥 YILIAN_SECRET_MASTER_KEY）；列上无明文，未 setup 为 NULL',
    ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '登录第二步开关：activate 后 TRUE；disable/reset-2fa 清回 FALSE',
    ADD COLUMN totp_enrolled_at DATETIME(6) NULL
        COMMENT '2FA 启用时刻（UTC）；未启用为 NULL';

CREATE TABLE user_2fa_recovery_codes (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT       NOT NULL COMMENT '逻辑引用 users.id（零物理外键，应用层同事务清理）',
    code_hash  VARCHAR(255) NOT NULL COMMENT '恢复码 BCrypt 哈希（8 位数字）；明文只在 activate 响应出现一次',
    used_at    DATETIME(6)  NULL COMMENT '使用时刻（UTC）；NULL=未使用，落值后永不复活',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '生成时刻（UTC）',
    PRIMARY KEY (id),
    INDEX idx_2fa_recovery_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '2FA 一次性恢复码投影表：只存 BCrypt 哈希，使用落 used_at 不删行；disable/reset-2fa 清 enrollment 时删行';
