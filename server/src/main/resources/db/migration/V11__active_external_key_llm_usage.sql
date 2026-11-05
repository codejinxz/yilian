-- V11：external_key 仅活跃行唯一 + llm_usage 用量落库（DATA-MODEL §5.18；023 待批项落地）。
-- 三件事一件迁移，逐条裁决如下。
--
-- ===== 1. sentences.active_external_key 生成列 + 仅活跃行唯一键 =====
-- 问题：V7 的 uk_sentence_bank_external (bank_id, external_key) 是「全行唯一」——批次回滚后题目
-- 进入 RECYCLED（软删，deleted_at 置值），external_key 不释放，同文件重导必撞已回收行（016 评审
-- 05#01；文件路径暂以「冲突翻译为 40904」收敛，重导仍失败）。指纹键早已用 active_fingerprint
-- 生成列（未删时 = fingerprint，已删恒为 NULL）按活跃行放行，两键口径不一致，本迁移对齐。
--
-- 裁决：旧键 uk_sentence_bank_external 必须删除。它对并发导入的防护由两层承接，防护不降级：
--   a) 生成列唯一键 uk_sentence_active_external (bank_id, active_external_key)：两个并发批次
--      提交同 (bank_id, external_key) 的活跃行，后者 INSERT 撞唯一键 → DataIntegrityViolation
--      → ImportBatchServiceImpl.causedByDataIntegrityViolation 翻译为 40904 整批回滚，
--      与旧键的兜底路径完全一致（活跃行互斥语义不变）；已回收行 active_external_key 为 NULL，
--      MySQL 唯一索引对 NULL 不去重，多个回收行同键共存，重导放行。
--   b) 导入事务锁：importSentences 以 bankDao.lockById 锁库行，同库并发导入在行锁上串行化，
--      唯一键冲突只发生在「锁外预检后、锁内落库前」的窗口，兜底语义不变。
-- 损失评估：旧键额外约束「回收行之间不同键」，该约束无业务语义（回收站不参与去重、不可见、
-- 不可练），放弃无损失。同内容重导仍被指纹键拦截（40904），本迁移只放行「同 key 不同内容」
-- 与「同文件重导（内容相同但旧行已回收）」两种此前被误伤的路径。
-- 生成列与 active_fingerprint 同款：未删取 external_key，已删恒 NULL；STORED 以便走索引。
-- 回滚：DROP COLUMN active_external_key 后按 V7 原样重建 uk_sentence_bank_external。
ALTER TABLE sentences
    ADD COLUMN active_external_key VARCHAR(64)
        GENERATED ALWAYS AS (IF(deleted_at IS NULL, external_key, NULL)) STORED,
    ADD UNIQUE KEY uk_sentence_active_external (bank_id, active_external_key),
    DROP KEY uk_sentence_bank_external;

-- ===== 2. llm_usage 建表（DATA-MODEL §5.18，017「落库待批」项） =====
-- 形状即 §5.18 登记的精简版：只记元数据（token/耗时/成败），不落问题与回答内容
-- （AGENTS §3.6：日志与异常表不得写入完整译文；本表同口径）。无物理外键（V10 决策，
-- 完整性由应用层校验），user_id / sentence_id 为逻辑引用，仅建查询索引：
--   idx_llm_usage_user_created  按用户聚合用量（M3 用量页、预算核算的主查询路径）
--   idx_llm_usage_sentence      按题排障（某句的答疑调用史）
--   idx_llm_usage_created       按时间段清扫/报表（无分区，保留策略后续裁决，先留扫描入口）
-- scene 记调用场景（tutor-ask）；protocol/model 记当次生效的协议与模型快照——模型可能下线，
-- 快照使历史行自解释（GAP「调用账本」口径的最小实现）。
CREATE TABLE llm_usage (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NULL,
    sentence_id       CHAR(26)     NULL,
    scene             VARCHAR(32)  NOT NULL,
    protocol          VARCHAR(32)  NULL,
    model             VARCHAR(128) NULL,
    prompt_tokens     INT          NULL,
    completion_tokens INT          NULL,
    duration_ms       BIGINT       NULL,
    ok                BOOLEAN      NOT NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_llm_usage_user_created (user_id, created_at),
    KEY idx_llm_usage_sentence (sentence_id),
    KEY idx_llm_usage_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ===== 3. spring_session_attributes 孤儿清理：不加索引，清理走应用层 Job =====
-- 背景：V10:78 删除 spring_session_attributes 的物理外键（含 ON DELETE CASCADE）后，会话行
-- 被删而属性行残留的孤儿 BLOB 无自动回收（016 评审实测一轮孤儿占 43%）。裁决：清理逻辑放
-- PartitionMaintenanceJob（DML 可重入、失败只记日志下周期重试），不放迁移内一次性 DML——
-- 迁移里 DELETE 大表在锁与 binlog 上不可控，且无法随调度周期复跑；孤儿产生是持续性的，
-- 一次性清完不等于不再长。
-- 索引结论：反连接 DELETE ... LEFT JOIN spring_session 的两条路径都已覆盖——
-- spring_session_attributes 的 PK 左前缀 (SESSION_PRIMARY_ID) 即驱动侧扫描/回表键，
-- spring_session 的 PK (PRIMARY_ID) 即被探测侧等值键；无需新增索引，V11 不动这两表结构。
