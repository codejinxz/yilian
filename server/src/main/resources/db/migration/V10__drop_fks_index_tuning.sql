-- V10：索引调整 + 物理外键改逻辑外键（docs/analysis/db-review-20261031.md P1/P2 结论）。
-- 索引部分：
--   1) reviews.idx_review_due 零查询使用且实测劫持 due 队列计划（state 退化为回表过滤），
--      idx_review_state_due 独立覆盖全部 due 查询；
--   2) error_log.idx_error_log_module 是 idx_error_module_created 的最左前缀冗余，
--      且导致 module+time 检索 filesort；
--   3) attempts.idx_attempt_user_sentence 把 mode 排在 created_at 前，
--      破坏「最新一次提交」与 countWrong 驱动侧的索引序，重排为 (user_id, sentence_id, created_at, mode)。
-- 外键部分：物理外键全部改为逻辑外键——关系完整性由应用层校验
-- （逐表审计见 docs/analysis/db-review-20261031.md 附表），唯一约束/CHECK/触发器/分区全部保留。
-- InnoDB 删 FK 后其支撑索引保留为普通索引，查询路径不变；唯一索引继续兜底幂等语义。
-- 回滚：按本清单逐条重建 FOREIGN KEY 即可（各表清单按 information_schema 实名收录）。

-- ===== 1. 三处索引调整 =====

ALTER TABLE reviews
    DROP INDEX idx_review_due;

ALTER TABLE error_log
    DROP INDEX idx_error_log_module;

ALTER TABLE attempts
    DROP INDEX idx_attempt_user_sentence,
    ADD INDEX idx_attempt_user_sentence (user_id, sentence_id, created_at, mode);

-- ===== 2. 物理外键改逻辑外键（28 条，按表分组） =====

-- appeals
ALTER TABLE appeals DROP FOREIGN KEY fk_appeal_attempt;
ALTER TABLE appeals DROP FOREIGN KEY fk_appeal_sentence;
ALTER TABLE appeals DROP FOREIGN KEY fk_appeal_user;

-- attempt_gradings
ALTER TABLE attempt_gradings DROP FOREIGN KEY fk_grading_attempt;

-- attempts
ALTER TABLE attempts DROP FOREIGN KEY fk_attempt_retry;
ALTER TABLE attempts DROP FOREIGN KEY fk_attempt_sentence;
ALTER TABLE attempts DROP FOREIGN KEY fk_attempt_user;
ALTER TABLE attempts DROP FOREIGN KEY fk_attempt_version;

-- fsrs_parameter_versions
ALTER TABLE fsrs_parameter_versions DROP FOREIGN KEY fk_fsrs_user;

-- import_batch_rows
ALTER TABLE import_batch_rows DROP FOREIGN KEY fk_import_row_batch;
ALTER TABLE import_batch_rows DROP FOREIGN KEY fk_import_row_sentence;
ALTER TABLE import_batch_rows DROP FOREIGN KEY fk_import_row_version;

-- import_batches
ALTER TABLE import_batches DROP FOREIGN KEY fk_import_bank;
ALTER TABLE import_batches DROP FOREIGN KEY fk_import_creator;

-- review_logs
ALTER TABLE review_logs DROP FOREIGN KEY fk_review_log_attempt;
ALTER TABLE review_logs DROP FOREIGN KEY fk_review_log_review;
ALTER TABLE review_logs DROP FOREIGN KEY fk_review_log_sentence;
ALTER TABLE review_logs DROP FOREIGN KEY fk_review_log_user;
ALTER TABLE review_logs DROP FOREIGN KEY fk_review_log_weights;

-- reviews
ALTER TABLE reviews DROP FOREIGN KEY fk_review_sentence;
ALTER TABLE reviews DROP FOREIGN KEY fk_review_user;

-- sentence_banks
ALTER TABLE sentence_banks DROP FOREIGN KEY fk_bank_owner;

-- sentence_versions
ALTER TABLE sentence_versions DROP FOREIGN KEY fk_sentence_version_bank;
ALTER TABLE sentence_versions DROP FOREIGN KEY fk_sentence_version_batch;
ALTER TABLE sentence_versions DROP FOREIGN KEY fk_sentence_version_sentence;

-- sentences
ALTER TABLE sentences DROP FOREIGN KEY fk_sentence_bank;
ALTER TABLE sentences DROP FOREIGN KEY fk_sentence_current_version;

-- spring_session（官方 schema 自带约束，本仓库 V1 建表，一并纳入逻辑外键模式）
ALTER TABLE spring_session_attributes DROP FOREIGN KEY spring_session_attributes_fk;
