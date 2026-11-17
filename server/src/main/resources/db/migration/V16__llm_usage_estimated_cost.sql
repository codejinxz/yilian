-- V16：llm_usage 估算成本列（033 预算护栏，PRD §17「预算到 80% 提醒管理员，到 100% 自动降级为本地批改，
-- 不做静默扣费」；计划 docs/plans/033-budget-guard-v16.md AC-1）。
--
-- 形状裁决：
--   a) estimated_cost_micro_cny BIGINT NULL：微元整数口径（1 CNY = 1e6 微元）而非 DECIMAL——
--      月度 SUM 与预算比较全在整数域进行，杜绝浮点求和误差；展示层再换算回元。
--      取整口径固定 ROUND_HALF_UP（计算见 LlmCostCalculator，输入/输出分开按模型定价计价后求和）。
--   b) 语义是「估算值」：按管理端配置的模型定价（app_settings 键 llm.budget）推算，非厂商账单——
--      PRD §17 的 cost 语义以估算承接，列名以 estimated_ 前缀如实表达。
--   c) NULL = 不计入预算：当次调用时该模型无定价配置（fail-open 口径，预披露裁决「定价缺失→成本
--      NULL 不拦调用」）。护栏只对已定价模型生效，未定价=免费语义如实；管理端补价后自下次调用起
--      生效，历史行不回填（append-only，行写一次不改）。
--   d) 迁移前快照：server/data/pre-v16-budget-snapshot.sql（mysqldump 全库）。
-- 回滚：ALTER TABLE llm_usage DROP COLUMN estimated_cost_micro_cny。
ALTER TABLE llm_usage
    ADD COLUMN estimated_cost_micro_cny BIGINT NULL
        COMMENT '估算成本（微元，1 CNY=1e6 微元，ROUND_HALF_UP）：按当次定价推算；无定价为 NULL=不计入预算';
