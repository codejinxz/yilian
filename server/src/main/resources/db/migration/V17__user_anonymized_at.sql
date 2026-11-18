-- V17：users 匿名化时刻列（自助删号 + 30 天冷静期匿名化的幂等游标）。
--
-- 形状裁决：
--   a) anonymized_at DATETIME(6) NULL：匿名化完成后落值（UTC），与 deleted_at/created_at 同为
--      DATETIME(6) UTC 口径；NULL = 未匿名化（含未删与冷静期内）。列只增不改语义：
--      匿名化是不可逆终态，重复调度以 IS NULL 过滤，落值后不再触碰。
--   b) 删号当下只软删（deleted_at 落值，与管理员删号同口径），匿名化由日调度任务
--      AccountAnonymizationJob 在 deleted_at + 30 天后执行——本列即该任务的幂等游标。
--   c) 不新增索引：任务扫描条件 deleted_at IS NOT NULL AND anonymized_at IS NULL
--      AND deleted_at <= cutoff 复用 V5 的 idx_users_deleted_at（已删账号量级极小，
--      全量扫描代价可忽略），不为冷路径加索引。
--   d) 学习记录（attempts/attempt_gradings/appeals/review_logs）append-only 一律不动，
--      匿名化只处置 users 身份字段（username→deleted_user_<id>、email→NULL、
--      password_hash→无效常量、个人化设置回默认、TOTP 三列清）。
--   e) 迁移前快照：server/data/pre-v17-self-delete-snapshot.sql（mysqldump 全库）。
-- 回滚：ALTER TABLE users DROP COLUMN anonymized_at。
ALTER TABLE users
    ADD COLUMN anonymized_at DATETIME(6) NULL COMMENT '匿名化完成时刻（UTC）：自助删号 30 天冷静期后由 AccountAnonymizationJob 落值；NULL=未匿名化' AFTER deleted_by
