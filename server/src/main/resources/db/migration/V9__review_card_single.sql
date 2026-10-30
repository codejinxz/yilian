-- Q1 拍板「一句一卡」：复习卡唯一键收敛为 (user_id, sentence_id)；
-- mode 保留为「最近练习模式」列（切换模式时覆盖更新，不新增卡片）。
-- 另：review_logs.weights_version_id NOT NULL FK 需要一行 GLOBAL 原型权重种子
-- （Q2 本期不接优化器，权重为占位原型值；M2.1 优化器替换）。

ALTER TABLE reviews
    DROP INDEX uk_review_card,
    ADD UNIQUE KEY uk_review_card (user_id, sentence_id);

INSERT INTO fsrs_parameter_versions (owner_type, user_id, algorithm, weights, sample_count, status, optimized_at, created_by, reason)
VALUES ('GLOBAL', NULL, 'FSRS-6',
        '{"schemaVersion": 1, "w": [0.4, 1.2, 2.4, 5.3, 3.2, 0.8, 0.6, 2.0, 1.5, 0.3, 1.0, 2.0, 40.0, 0.6, 3.0, 5.1, 0.9, 0.4], "note": "prototype placeholder; optimizer lands in M2.1"}',
        0, 'ACTIVE', NULL, NULL, 'M2 原型占位权重：排程按本地引擎四分支，不消费权重');
