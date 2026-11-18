-- V14：llm_provider_keys 管理端密钥加密入库（DATA-MODEL §5.20）。
--
-- 背景：provider 目录（app_settings 键 llm.providers）只存元数据，密钥按条目 id 走
-- YILIAN_LLM_KEY_<大写ID> 环境变量索引（密钥不入库）。本表把「密钥」扩为管理端可动态录入：
-- 条目密钥 AES-256-GCM 加密落本表，主密钥只走环境变量 YILIAN_SECRET_MASTER_KEY
-- （prod 缺失启动失败；local 允许 application-local.yml 的 local-dev-only 开发值，禁复用）。
-- 解析优先级：本表密文解密 > YILIAN_LLM_KEY_<大写ID> env > legacy 环境变量三件套
-- （legacy 回退语义不变，见 LlmClientRegistry）。
--
-- 形状裁决：
--   a) 表名全小写 llm_provider_keys（V12 教训：Windows 本机 lctn=1 掩盖大小写笔误，
--      CI lctn=0 大小写敏感，实体映射与 SQL 全部用小写）。
--   b) provider_id VARCHAR(64) 单列主键：与 provider 目录条目 id（LlmProviderDescriptor
--      同形态约束，小写字母/数字/连字符）一一对应，一目录条目至多一把入库密钥。
--      零物理外键（仓库纪律）：目录存 app_settings JSON，无法建外键；逻辑引用一致性由
--      应用层同事务保证——目录删除事务内删密钥行（审计经 @OperationLog 留痕）。
--   c) secret_cipher TEXT NOT NULL：密文格式 Base64(12 字节随机 IV ‖ GCM 密文含 128 位
--      auth tag)。TEXT 上限 64KB 远超任何 API Key 长度；列上无明文，明文只在加密前的
--      内存里短暂存在，不入日志/审计/响应。
--   d) key_last4 VARCHAR(4) NULL：录入时留痕的尾 4 位辨识号（env 态无此列值）。仅用于
--      管理员辨认「存的是哪把钥匙」，不是明文泄露；可空兼容测试夹具。
--   e) 无 append-only 触发器：密钥投影表允许受控 UPDATE/DELETE——录入即
--      upsert（覆盖旧密文）、目录删除即删行；updated_at 每次变更留痕，主键唯一保证
--      同一 provider 只有一行，历史不可伪造由主密钥保密性与审计日志承担。
-- 回滚：DROP TABLE llm_provider_keys；解析链回退纯 env 轨（原行为，向后兼容）。

CREATE TABLE llm_provider_keys (
    provider_id   VARCHAR(64) NOT NULL COMMENT 'provider 目录条目 id（逻辑引用，零物理外键）',
    secret_cipher TEXT        NOT NULL COMMENT 'AES-256-GCM 密文：Base64(IV ‖ 密文+tag)，主密钥只走 YILIAN_SECRET_MASTER_KEY',
    key_last4     VARCHAR(4)  NULL COMMENT '录入时留痕的尾 4 位辨识号；env 态为空',
    updated_at    DATETIME(6) NOT NULL COMMENT '最近一次写入时间（UTC）；upsert 与目录删除同事务变更',
    PRIMARY KEY (provider_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
