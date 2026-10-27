# 译练 · 数据模型（V1 / M1 + M2）

| 项 | 内容 |
|---|---|
| 版本 | v0.1.1（讨论稿，待确认） |
| 日期 | 2026-10-24 |
| 状态 | 待产品/技术负责人确认后冻结 |
| 上游 | `AGENTS.md`、`docs/PRD.md` §5/§8/§11/§16/§17/§18、`docs/NAMING.md`、`docs/GAP-ANALYSIS.md` |
| 下游 | `docs/API.md`（未开始）、`docs/openapi.yaml`（M1/M2 实现后导出） |

> 本文件是字段的唯一来源。`docs/API.md`、`openapi.yaml`、前端类型只能从这里取字段；发现字段不够用，先改本文件并追加变更记录，再改接口。
>
> 本文件不写可执行 DDL。表结构以「字段 / 类型 / 可空 / 默认值 / 说明」描述，迁移文件按 §12 的顺序落地。

---

## 1. 本次范围与关键结论

### 1.1 范围

- M1：账号、登录会话、邀请码、题库与句子版本、导入预览/提交/批次、操作日志、异常日志、系统配置。
- M2：拉题、填空/整句 attempt 提交、判分、错题、FSRS 复习队列、申诉记录。
- M3 预留、本次不建：模型目录、Prompt 版本、LLM 用量、统计聚合。
- 不做：三题定级、积压熔断/顺延、排行榜、打卡、UGC、模型生成题库。

### 1.2 已冻结的结论

1. `attempts` 只存一次提交；`attempt_gradings` 存每次判分/复核/改判；两者都 append-only。  
2. 同一 `attemptId` 重复提交返回已有结果；同一 ID 但载荷不同返回 409；游客只落本地。  
3. `sentences` 存题目身份/当前版本指针/银行级修订号；`sentence_versions` 存不可变内容。  
4. 导入按 PRD 重建：预览 → 提交 → 批次查询/回滚；现有异步 `import_batch` 只保留为上传基础设施。  
5. 复习采用完整 FSRS：四档评分、期望保留率、参数版本与优化记录；UI 仍显示 `新题 / 学习中 / 已掌握`。  
6. 所有时间戳 UTC；到期用 `DATETIME`，不用 `DATE`；日界按用户 IANA 时区计算。  
7. 软删统一 `deleted_at` / `deleted_by`；`enabled` 只表示账号启停，不表示删除。  
8. 迁移不使用 `out-of-order`；测试库可重置，生产库禁止手改历史迁移。  

### 1.3 与 PRD §8 的差异

| PRD 表 | 差异 | 原因 |
|---|---|---|
| `users` | 改名 `users`（原实现 `app_user`）；补时区、每日目标、评分标准、期望保留率、停用原因、软删、首次改密 | 账号要跨设备、时区日界和 FSRS 设置 |
| `sentence_banks` | 补生命周期状态、软删、银行级版本游标 | 支持发布/停用/回收站与增量拉取 |
| `sentences` | 拆成 `sentences` + `sentence_versions` | 改题不能覆盖历史，attempt 必须能回放旧版本 |
| `attempts` | 拆成 `attempts` + `attempt_gradings`；`answer` 改 JSON | 复核/改判 append-only，填空需要逐槽结构 |
| `reviews` | 改为 FSRS 投影；`due` 改 `due_at`；补稳定性/难度/评分/参数版本 | 完整 FSRS 与 UTC 到期 |
| 新增 | `invite_codes`、`import_batches`、`import_batch_rows`、`appeals`、`review_logs`、`fsrs_parameter_versions`、`admin_audit_log`、`error_log`、`app_settings` | PRD §16–§18、GAP P0、FSRS |

---

## 2. 实体清单

| 域 | 实体 | 来源 | 本次 |
|---|---|---|---|
| account | `users` | PRD §8 | M1 |
| account | `invite_codes` | PRD §16 | M1 |
| content | `sentence_banks` | PRD §8 | M1 |
| content | `sentences` | PRD §8 | M1 |
| content | `sentence_versions` | 新增（从 sentences 拆出） | M1 |
| content | `import_batches` | PRD §16 | M1 |
| content | `import_batch_rows` | 新增（逐行原因） | M1 |
| practice | `attempts` | PRD §8 | M2 |
| practice | `attempt_gradings` | 新增（从 attempts 拆出） | M2 |
| practice | `appeals` | PRD §5.1 / §16 | M2（记录/排除；LLM 裁决 M3） |
| practice | `reviews` | PRD §8 | M2 |
| practice | `review_logs` | 新增（FSRS 复习日志） | M2 |
| practice | `fsrs_parameter_versions` | 新增（FSRS 参数版本） | M2 |
| admin | `admin_audit_log` | PRD §18.1 | M1 |
| admin | `error_log` | PRD §18.2 | M1 |
| system | `app_settings` | PRD §17 | M1（只读配置；编辑 M3） |
| analytics | `user_daily_stats` | PRD §16 | M3 预留，不建 |
| ai | `llm_providers` / `llm_models` / `prompt_templates` / `prompt_versions` / `llm_usage` | PRD §17 | M3 预留，不建 |

基础设施表（不属于业务实体，但属于最终 schema）：

- `spring_session` / `spring_session_attributes`：Spring Session JDBC 的 refresh 会话；V1 已有，保留。
- `flyway_schema_history`：Flyway 自动维护。

**明确不建**：

| 候选 | 结论 | 理由 |
|---|---|---|
| `auth_sessions` | 不建 | 新 AGENTS 已定 refresh 用 Spring Session JDBC，不重复建会话表 |
| `user_settings` | 不建 | 设置字段少，直接放 `users` |
| `practice_sessions` | 不建 | 题数/正确率/连续天数由 attempts + 用户时区派生 |
| `sentence_answer_variants` | 不建 | 已接受申诉作为 `appeals` overlay 参与判分，不物理改题目 |
| `content_changes` | 不建 | 用 `sentences.version` 银行级游标 + `sentence_versions` 快照 |
| `question_stats` | 暂不建 | 坏题探测到 M3 有真实数据后再加 |
| `fsrs_optimization_runs` | 不建 | 优化结果写 `fsrs_parameter_versions`，失败进 `error_log` |
| `operation_log` | 改名 | 统一为 PRD §18.1 的 `admin_audit_log` |
| `import_batch` | 改名/重建 | 统一为 `import_batches` + `import_batch_rows` |

---

## 3. 关系图

```mermaid
erDiagram
    USERS ||--o{ INVITE_CODES : creates
    USERS ||--o{ SENTENCE_BANKS : owns
    USERS ||--o{ ATTEMPTS : submits
    USERS ||--o{ APPEALS : raises
    USERS ||--o{ REVIEWS : owns
    USERS ||--o{ REVIEW_LOGS : reviews
    USERS ||--o{ FSRS_PARAMETER_VERSIONS : owns
    USERS ||--o{ ADMIN_AUDIT_LOG : acts
    USERS ||--o{ USER_DAILY_STATS : aggregates

    SENTENCE_BANKS ||--o{ SENTENCES : contains
    SENTENCE_BANKS ||--o{ IMPORT_BATCHES : imports
    SENTENCES ||--o{ SENTENCE_VERSIONS : snapshots
    SENTENCES ||--o{ ATTEMPTS : targets
    SENTENCES ||--o{ REVIEWS : tracks
    SENTENCES ||--o{ REVIEW_LOGS : logs

    SENTENCE_VERSIONS ||--o{ ATTEMPTS : binds
    SENTENCE_VERSIONS ||--o{ IMPORT_BATCH_ROWS : produces
    IMPORT_BATCHES ||--o{ IMPORT_BATCH_ROWS : rows

    ATTEMPTS ||--o{ ATTEMPT_GRADINGS : graded_by
    ATTEMPTS ||--o{ APPEALS : raises
    ATTEMPTS ||--o{ REVIEW_LOGS : triggers

    REVIEWS ||--o{ REVIEW_LOGS : logs
    FSRS_PARAMETER_VERSIONS ||--o{ REVIEW_LOGS : weights

    LLM_PROVIDERS ||--o{ LLM_MODELS : offers
    LLM_MODELS ||--o{ LLM_USAGE : metered
    PROMPT_TEMPLATES ||--o{ PROMPT_VERSIONS : versions
    PROMPT_VERSIONS ||--o{ LLM_USAGE : used_by
```

`APP_SETTINGS`、`ERROR_LOG` 为独立表；`LLM_*` 为 M3 预留，不随 M1/M2 建。

---

## 4. 通用约定

### 4.1 命名与类型

- 表名、列名用 `snake_case`；表名用复数（历史 `error_log` 除外，沿用 PRD）。
- 主键：业务内部 ID 用 `BIGINT AUTO_INCREMENT`；客户端提交的 attempt 用 `CHAR(36)` UUID；句子用 `CHAR(26)` ULID。
- 对外 JSON 中所有 `BIGINT` ID 一律序列化为字符串，避免 JS 精度问题；`attemptId` 保持 UUID 字符串。
- 字符串统一 `utf8mb4` / `utf8mb4_0900_ai_ci`。
- 布尔用 `BOOLEAN`（MySQL `TINYINT(1)`）；历史 `BIT(1)` 表在迁移时统一。
- 金额用 `DECIMAL(12,6)`；比例用 `DECIMAL(4,3)`。
- 时间用 `DATETIME(6)`，按 UTC 写入；禁止 `TIMESTAMP`（会受服务器/会话时区影响）。

### 4.2 审计字段

| 字段 | 规则 |
|---|---|
| `created_at` | 所有表都有（除纯关联表）；UTC |
| `updated_at` | 可变表才有；append-only 表没有 |
| `created_by` / `updated_by` | 管理端写入或需要追溯的表才有；存 `users.id` |
| `deleted_at` / `deleted_by` | 可软删表才有；`NULL` 表示未删 |
| `enabled` | 只用于账号/邀请码/配置的启停，不等于删除 |
| `status` | 内容生命周期；回收站 = `RECYCLED` + `deleted_at` 非空 |

### 4.3 只增不改

以下表禁止应用层 UPDATE/DELETE，迁移中加 `BEFORE UPDATE` / `BEFORE DELETE` 触发器（`SIGNAL SQLSTATE '45000'`）：

- `sentence_versions`
- `attempts`
- `attempt_gradings`
- `review_logs`
- `admin_audit_log`（UPDATE 禁止；保留期通过月分区 DROP 清理，不按行 DELETE）
- `error_log`（同上）

`reviews`、`appeals`、`import_batches`、`import_batch_rows`、`users`、`sentences`、`sentence_banks` 是可变投影/状态表，允许受控 UPDATE；变更必须写 `admin_audit_log` 或 `review_logs`。

### 4.4 时区与日界

- 所有 `DATETIME` 存 UTC。
- `users.timezone` 存 IANA 时区（如 `Asia/Shanghai`）。
- “今天到期/今日已练”按 `LocalDate.now(ZoneId.of(users.timezone))` 计算本地日界，再转成 UTC 区间查询。
- `reviews.due_at` 用 `DATETIME(6)` UTC，不用 `DATE`。原因：跨时区/夏令时下 DATE 无法表达“下一次到期瞬间”，且 PRD 的“今日”是用户本地概念。
- `user_daily_stats.stat_date` 是唯一使用 `DATE` 的业务列，表示用户本地日期；同时快照 `timezone`。

### 4.5 外键约定

| 子表.列 | 父表.列 | ON DELETE | 说明 |
|---|---|---|---|
| `sentence_banks.owner_id` | `users.id` | RESTRICT | NULL = 官方 |
| `sentences.bank_id` | `sentence_banks.id` | RESTRICT | 题目不脱离题库 |
| `sentences.current_version_id` | `sentence_versions.id` | RESTRICT | 两表建完后补 FK；插入时先 NULL，同事务回填 |
| `sentence_versions.sentence_id` | `sentences.id` | RESTRICT | 快照永不删 |
| `sentence_versions.bank_id` | `sentence_banks.id` | RESTRICT | 冗余校验 |
| `sentence_versions.import_batch_id` | `import_batches.id` | RESTRICT | 批次不硬删 |
| `import_batches.bank_id` | `sentence_banks.id` | RESTRICT | 导入目标 |
| `import_batches.created_by` | `users.id` | RESTRICT | 另存 `created_by_name` 快照 |
| `import_batch_rows.batch_id` | `import_batches.id` | RESTRICT | 批次不硬删 |
| `import_batch_rows.sentence_id` | `sentences.id` | RESTRICT | 成功后关联 |
| `import_batch_rows.sentence_version_id` | `sentence_versions.id` | RESTRICT | 成功后关联 |
| `attempts.user_id` | `users.id` | RESTRICT | 游客不落库 |
| `attempts.sentence_id` | `sentences.id` | RESTRICT | 题目标识 |
| `attempts.sentence_version_id` | `sentence_versions.id` | RESTRICT | 内容快照 |
| `attempts.retry_of_id` | `attempts.id` | RESTRICT | 自关联 |
| `attempt_gradings.attempt_id` | `attempts.id` | RESTRICT | 判分不脱离提交 |
| `appeals.attempt_id` | `attempts.id` | RESTRICT | 申诉不脱离提交 |
| `appeals.user_id` | `users.id` | RESTRICT | 申诉人 |
| `appeals.sentence_id` | `sentences.id` | RESTRICT | overlay 查询 |
| `reviews.user_id` | `users.id` | RESTRICT | 用户卡 |
| `reviews.sentence_id` | `sentences.id` | RESTRICT | 题目卡 |
| `review_logs.review_id` | `reviews.id` | RESTRICT | 复习日志 |
| `review_logs.user_id` | `users.id` | RESTRICT | 用户 |
| `review_logs.sentence_id` | `sentences.id` | RESTRICT | 题目 |
| `review_logs.attempt_id` | `attempts.id` | RESTRICT | 触发提交 |
| `review_logs.weights_version_id` | `fsrs_parameter_versions.id` | RESTRICT | 参数版本 |
| `fsrs_parameter_versions.user_id` | `users.id` | RESTRICT | 仅 USER owner |
| `admin_audit_log.actor_id` | `users.id` | SET NULL | 保留 `actor_name` 快照 |
| `error_log.user_id` | `users.id` | SET NULL | 保留 `operator` 快照 |
| `user_daily_stats.user_id` | `users.id` | RESTRICT | 聚合表 |

**禁止**：答题链路（`attempts`/`attempt_gradings`/`review_logs`/`reviews`）使用 `ON DELETE CASCADE`；任何硬删题目的外键级联都不允许。

---

## 5. 表结构

> 可空列用 `NULL` 标注；未标 `NULL` 的均为 `NOT NULL`。默认值只写业务默认，不写数据库引擎默认（迁移中再落 `DEFAULT`）。

### 5.1 `users`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 | AUTO | 主键 |
| `email` | VARCHAR(255) | 是 | NULL | 登录/通知；唯一；删号 30 天后匿名化 |
| `username` | VARCHAR(64) | 否 | — | 唯一登录名/展示名 |
| `password_hash` | VARCHAR(255) | 否 | — | BCrypt 现在；Argon2id 预留长度 |
| `role` | VARCHAR(16) | 否 | `USER` | `USER` / `ADMIN` |
| `enabled` | BOOLEAN | 否 | `TRUE` | 账号启停；停用立即吊销 refresh 会话 |
| `password_changed` | BOOLEAN | 否 | `FALSE` | 首次登录是否已改密 |
| `password_updated_at` | DATETIME(6) | 是 | NULL | 最近改密时间 |
| `timezone` | VARCHAR(64) | 否 | `Asia/Shanghai` | IANA 时区 |
| `daily_goal` | SMALLINT | 否 | `3` | 1 / 3 / 5 |
| `rating_standard` | VARCHAR(16) | 否 | `TEM8` | `TEM8` / `CATTI` / `IELTS` |
| `desired_retention` | DECIMAL(4,3) | 否 | `0.900` | FSRS 期望保留率，0.800–0.970 |
| `theme` | VARCHAR(8) | 否 | `paper` | `paper` / `night`；前端也可本地覆盖 |
| `notifications_enabled` | BOOLEAN | 否 | `FALSE` | 通知默认关闭 |
| `last_login_at` | DATETIME(6) | 是 | NULL | 最近登录成功 |
| `disabled_reason` | VARCHAR(255) | 是 | NULL | 停用原因，管理员必填 |
| `disabled_at` | DATETIME(6) | 是 | NULL | 停用时间 |
| `deleted_at` | DATETIME(6) | 是 | NULL | 软删；30 天后匿名化 |
| `deleted_by` | BIGINT | 是 | NULL | 操作者 |
| `created_at` | DATETIME(6) | 否 | — | UTC |
| `updated_at` | DATETIME(6) | 否 | — | UTC |

索引/约束：

- 主键 `(id)`
- 唯一 `uk_users_email (email)`
- 唯一 `uk_users_username (username)`
- 普通 `idx_users_role_enabled (role, enabled)`
- 普通 `idx_users_deleted_at (deleted_at)`

软删：`deleted_at` / `deleted_by`。`enabled=FALSE` 是停用，不是删除。

### 5.2 `invite_codes`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 | AUTO | 主键 |
| `code_hash` | CHAR(64) | 否 | — | 规范化邀请码的 SHA-256；不存明文 |
| `code_prefix` | VARCHAR(12) | 否 | — | 列表展示前缀，如 `YL-AB12` |
| `max_uses` | SMALLINT | 否 | `1` | 一码一人时固定 1 |
| `used_count` | SMALLINT | 否 | `0` | 已使用次数 |
| `used_by` | BIGINT | 是 | NULL | 使用者；一码一人时唯一 |
| `used_at` | DATETIME(6) | 是 | NULL | 使用时间 |
| `expires_at` | DATETIME(6) | 是 | NULL | 过期时间；空表示不过期 |
| `enabled` | BOOLEAN | 否 | `TRUE` | 是否可继续使用 |
| `created_by` | BIGINT | 是 | NULL | 管理员 |
| `created_at` | DATETIME(6) | 否 | — | UTC |
| `deleted_at` | DATETIME(6) | 是 | NULL | 软删 |
| `deleted_by` | BIGINT | 是 | NULL | 操作者 |
| `revoked_reason` | VARCHAR(255) | 是 | NULL | 撤销原因 |

索引/约束：

- 主键 `(id)`
- 唯一 `uk_invite_code_hash (code_hash)`
- 唯一 `uk_invite_used_by (used_by)`（一码一人；`max_uses>1` 时另开批次/放开）
- 普通 `idx_invite_expires (expires_at)`

注册：事务内 `SELECT ... FOR UPDATE` 校验 hash、未过期、`used_count < max_uses`；创建用户后回写 `used_by/used_at/used_count`。

### 5.3 `sentence_banks`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 | AUTO | 主键 |
| `owner_id` | BIGINT | 是 | NULL | NULL = 官方题库 |
| `name` | VARCHAR(128) | 否 | — | 题库名 |
| `description` | VARCHAR(500) | 是 | NULL | 说明 |
| `visibility` | VARCHAR(16) | 否 | `PRIVATE` | `PUBLIC` / `PRIVATE` |
| `status` | VARCHAR(16) | 否 | `DRAFT` | `DRAFT`/`PUBLISHED`/`DISABLED`/`RECYCLED`/`ARCHIVED` |
| `version` | BIGINT | 否 | `0` | 银行级修订号；每次发布/改题/删题/恢复 +1 |
| `published_at` | DATETIME(6) | 是 | NULL | 首次/最近发布 |
| `created_by` | BIGINT | 是 | NULL | 创建者 |
| `updated_by` | BIGINT | 是 | NULL | 最近修改者 |
| `created_at` | DATETIME(6) | 否 | — | UTC |
| `updated_at` | DATETIME(6) | 否 | — | UTC |
| `deleted_at` | DATETIME(6) | 是 | NULL | 软删/回收站 |
| `deleted_by` | BIGINT | 是 | NULL | 操作者 |

索引/约束：

- 主键 `(id)`
- 普通 `idx_bank_owner_status (owner_id, status)`
- 普通 `idx_bank_visibility_status (visibility, status)`
- 普通 `idx_bank_updated (updated_at)`

软删：`deleted_at`；回收站同时 `status='RECYCLED'`。官方题库不允许删除，只可停用/归档。

### 5.4 `sentences`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | CHAR(26) | 否 | — | ULID，全局唯一 |
| `bank_id` | BIGINT | 否 | — | 所属题库 |
| `external_key` | VARCHAR(64) | 是 | NULL | 导入源 id（如 `s_0001`）；同题库唯一 |
| `status` | VARCHAR(16) | 否 | `DRAFT` | 同题库生命周期 |
| `current_version_id` | BIGINT | 是 | NULL | 指向 `sentence_versions.id`；插入时先空，同事务回填 |
| `version` | BIGINT | 否 | `0` | 银行级修订号（用于增量 `sinceVersion`） |
| `fingerprint` | CHAR(64) | 否 | — | 归一化 `zh|ref|chunks` 的 SHA-256 |
| `active_fingerprint` | CHAR(64) | 是 | 生成列 | `IF(deleted_at IS NULL, fingerprint, NULL)`；用于未删重复硬拦 |
| `created_by` | BIGINT | 是 | NULL | 创建者 |
| `updated_by` | BIGINT | 是 | NULL | 最近修改者 |
| `created_at` | DATETIME(6) | 否 | — | UTC |
| `updated_at` | DATETIME(6) | 否 | — | UTC |
| `deleted_at` | DATETIME(6) | 是 | NULL | 软删；删除也必须 bump `version` |
| `deleted_by` | BIGINT | 是 | NULL | 操作者 |

索引/约束：

- 主键 `(id)`
- 唯一 `uk_sentence_bank_external (bank_id, external_key)`
- 唯一 `uk_sentence_bank_active_fingerprint (bank_id, active_fingerprint)`
- 普通 `idx_sentence_bank_version (bank_id, version)`（增量拉取）
- 普通 `idx_sentence_bank_status (bank_id, status, deleted_at)`
- 普通 `idx_sentence_current_version (current_version_id)`

软删：`deleted_at`；删除/停用必须 bump `version`，否则增量客户端看不到 tombstone。

### 5.5 `sentence_versions`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 | AUTO | 主键 |
| `sentence_id` | CHAR(26) | 否 | — | 题目身份 |
| `bank_id` | BIGINT | 否 | — | 冗余，便于按题库查询 |
| `version_no` | INT | 否 | — | 单题内容版本，从 1 递增 |
| `zh` | VARCHAR(255) | 否 | — | 中文原句（4–60 字） |
| `ref` | VARCHAR(512) | 否 | — | 参考译文（8–300 字符） |
| `note` | VARCHAR(1000) | 否 | — | 解析（20–300 字） |
| `chunks` | JSON | 否 | — | 语块数组，见 §6.1 |
| `blank` | JSON | 否 | — | 挖空语块下标数组，见 §6.2 |
| `tags` | JSON | 否 | — | 考点标签数组，见 §6.3 |
| `level` | VARCHAR(32) | 是 | NULL | 专八/CATTI/雅思等 |
| `source` | VARCHAR(128) | 是 | NULL | 来源 |
| `license` | VARCHAR(64) | 是 | NULL | 许可 |
| `content_hash` | CHAR(64) | 否 | — | 内容快照 SHA-256 |
| `schema_version` | SMALLINT | 否 | `1` | `chunks/blank/tags` 的结构版本 |
| `import_batch_id` | CHAR(36) | 是 | NULL | 来源批次 |
| `created_by` | BIGINT | 是 | NULL | 创建者 |
| `created_at` | DATETIME(6) | 否 | — | UTC |

索引/约束：

- 主键 `(id)`
- 唯一 `uk_sentence_version (sentence_id, version_no)`
- 普通 `idx_sentence_version_bank (bank_id, created_at)`
- 普通 `idx_sentence_version_hash (content_hash)`
- 外键 `fk_sentence_version_sentence (sentence_id) -> sentences(id)`；`fk_sentence_version_batch (import_batch_id) -> import_batches(id)`（批次表先建或后加）

不可变：无 `updated_at`/`deleted_at`；触发器禁止 UPDATE/DELETE。修改题目 = 新增 `version_no` + 更新 `sentences.current_version_id`。

### 5.6 `import_batches`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | CHAR(36) | 否 | — | UUID，客户端/服务端生成 |
| `bank_id` | BIGINT | 否 | — | 目标题库 |
| `format` | VARCHAR(8) | 否 | — | `JSON` / `CSV` |
| `original_filename` | VARCHAR(255) | 否 | — | 仅展示，不含客户端路径 |
| `storage_key` | VARCHAR(512) | 否 | — | 原文件对象键 |
| `content_hash` | CHAR(64) | 否 | — | 文件 SHA-256 |
| `size_bytes` | BIGINT | 否 | — | 原始大小，≤ 5MB |
| `status` | VARCHAR(16) | 否 | `PREVIEWING` | 见 §7 |
| `total_rows` | BIGINT | 否 | `0` | 数据行数 |
| `valid_rows` | BIGINT | 否 | `0` | 校验通过 |
| `invalid_rows` | BIGINT | 否 | `0` | 校验失败 |
| `imported_rows` | BIGINT | 否 | `0` | 已入库 |
| `error_summary` | JSON | 是 | NULL | 批次级摘要，见 §6.6 |
| `created_by` | BIGINT | 否 | — | 管理员 id |
| `created_by_name` | VARCHAR(64) | 否 | — | 用户名快照 |
| `created_at` | DATETIME(6) | 否 | — | UTC |
| `previewed_at` | DATETIME(6) | 是 | NULL | 预览完成 |
| `committed_at` | DATETIME(6) | 是 | NULL | 提交完成 |
| `rolled_back_at` | DATETIME(6) | 是 | NULL | 回滚完成 |
| `rolled_back_by` | BIGINT | 是 | NULL | 回滚人 |
| `rollback_reason` | VARCHAR(500) | 是 | NULL | 回滚原因 |

索引/约束：

- 主键 `(id)`
- 普通 `idx_import_bank_created (bank_id, created_at)`
- 普通 `idx_import_status (status)`
- 普通 `idx_import_creator (created_by, created_at)`

状态机：`PREVIEWING → PREVIEWED → COMMITTING → COMMITTED`；任一步失败 `FAILED`；已提交可 `ROLLED_BACK`；超过 24 小时未提交 `EXPIRED`。预览阶段不写题目；提交在单事务内写 `sentence_versions` 与 `sentences`，失败整批回滚。

### 5.7 `import_batch_rows`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 | AUTO | 主键 |
| `batch_id` | CHAR(36) | 否 | — | 批次 |
| `row_no` | INT | 否 | — | 数据行号，从 1 开始 |
| `external_key` | VARCHAR(64) | 是 | NULL | 源 id |
| `raw` | JSON | 否 | — | 原始行；不写日志 |
| `status` | VARCHAR(16) | 否 | `VALID` | `VALID`/`INVALID`/`IMPORTED`/`SKIPPED` |
| `error_codes` | JSON | 是 | NULL | 逐行原因，见 §6.7 |
| `sentence_id` | CHAR(26) | 是 | NULL | 成功后关联 |
| `sentence_version_id` | BIGINT | 是 | NULL | 成功后关联 |
| `created_at` | DATETIME(6) | 否 | — | UTC |
| `updated_at` | DATETIME(6) | 否 | — | UTC |

索引/约束：

- 主键 `(id)`
- 唯一 `uk_import_row (batch_id, row_no)`
- 普通 `idx_import_row_status (batch_id, status)`
- 普通 `idx_import_row_sentence (sentence_id)`

回滚：按 `batch_id` 找到 `sentence_id`，把题目置 `RECYCLED` 并 bump `sentences.version`；不硬删题目，不级联删 attempt。

### 5.8 `attempts`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | CHAR(36) | 否 | — | 客户端 UUID，幂等键 |
| `user_id` | BIGINT | 否 | — | 登录用户；游客不落服务端 |
| `device_id` | VARCHAR(64) | 是 | NULL | 离线来源设备，仅诊断 |
| `sentence_id` | CHAR(26) | 否 | — | 题目身份 |
| `sentence_version_id` | BIGINT | 否 | — | 绑定内容快照 |
| `question_version` | INT | 否 | — | 冗余 `version_no`，便于展示 |
| `mode` | VARCHAR(16) | 否 | — | `CLOZE` / `FREE` |
| `kind` | VARCHAR(16) | 否 | — | `FIRST` / `RETRY` / `REVEAL` |
| `retry_of_id` | CHAR(36) | 是 | NULL | 自关联；`RETRY` 必填 |
| `answer` | JSON | 否 | — | 提交载荷，见 §6.4 |
| `answer_hash` | CHAR(64) | 否 | — | 规范化载荷 SHA-256；同 ID 不同载荷检测 |
| `hints_used` | SMALLINT | 否 | `0` | 0–3；影响 FSRS 评分 |
| `duration_ms` | INT | 是 | NULL | 客户端耗时；上限 24h |
| `client_created_at` | DATETIME(6) | 是 | NULL | 离线发生时间；仅展示/排序 |
| `created_at` | DATETIME(6) | 否 | — | 服务端 UTC；统计以此为准 |

索引/约束：

- 主键 `(id)`
- 普通 `idx_attempt_user_created (user_id, created_at)`
- 普通 `idx_attempt_user_sentence (user_id, sentence_id, mode, created_at)`
- 普通 `idx_attempt_version (sentence_version_id)`
- 外键 `retry_of_id -> attempts(id)`（`ON DELETE RESTRICT`）

幂等：`id` 是唯一约束。重复提交时：

1. 同一 `user_id` + 同一 `answer_hash`：返回已有 attempt + 最新 grading，HTTP 200。
2. 同一 `id` 但不同 `user_id` 或不同 `answer_hash`：HTTP 409，错误码 `ATTEMPT_ID_REUSED`。
3. 并发重复：以数据库唯一键为准，捕获冲突后查询已有行。

`score`/`result` **不在 attempts**：见 PRD §8 差异；API 的 `score`/`result` 取最新 `attempt_gradings`。

### 5.9 `attempt_gradings`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 | AUTO | 主键 |
| `attempt_id` | CHAR(36) | 否 | — | 所属提交 |
| `sequence` | INT | 否 | — | 1=初次；2+=复核/改判 |
| `grader` | VARCHAR(16) | 否 | — | `LOCAL`/`GRAMMAR`/`MODEL`/`HUMAN` |
| `score` | INT | 是 | NULL | 0–100；`REVEAL` 无判分时为 NULL |
| `max_score` | INT | 否 | `100` | 满分 |
| `result` | JSON | 否 | — | 批注明细，见 §6.5 |
| `normalizer_version` | VARCHAR(16) | 否 | — | 归一化算法版本 |
| `rubric_version` | VARCHAR(16) | 是 | NULL | 评分标准版本 |
| `prompt_version_id` | BIGINT | 是 | NULL | M3 Prompt 版本 |
| `model_snapshot` | VARCHAR(128) | 是 | NULL | M3 模型快照 |
| `latency_ms` | INT | 是 | NULL | 判分耗时 |
| `created_at` | DATETIME(6) | 否 | — | UTC |

索引/约束：

- 主键 `(id)`
- 唯一 `uk_grading_attempt_sequence (attempt_id, sequence)`
- 普通 `idx_grading_created (created_at)`
- 普通 `idx_grading_grader (grader)`

只增不改；改判 = 新 sequence，不覆盖旧分。

### 5.10 `appeals`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 | AUTO | 主键 |
| `attempt_id` | CHAR(36) | 否 | — | 被申诉提交 |
| `annotation_key` | VARCHAR(64) | 否 | — | 批注稳定键，如 `slot:2` |
| `sentence_id` | CHAR(26) | 否 | — | 冗余，便于 overlay |
| `chunk_index` | INT | 否 | — | 语块下标 |
| `user_id` | BIGINT | 否 | — | 申诉人 |
| `kind` | VARCHAR(16) | 否 | — | `APPEAL`（我觉得也对）/ `DISPUTE`（不认同） |
| `status` | VARCHAR(16) | 否 | `PENDING` | `PENDING`/`ACCEPTED`/`REJECTED`/`WITHDRAWN` |
| `proposed_answer` | VARCHAR(255) | 是 | NULL | 用户认为可接受的答案 |
| `reason` | VARCHAR(500) | 是 | NULL | 理由 |
| `decision` | JSON | 是 | NULL | LLM/人工裁决原文（M3） |
| `decision_reason` | VARCHAR(500) | 是 | NULL | 可展示理由 |
| `decided_by` | VARCHAR(16) | 是 | NULL | `USER`/`LLM`/`ADMIN` |
| `decided_at` | DATETIME(6) | 是 | NULL | 裁决时间 |
| `model_snapshot` | VARCHAR(128) | 是 | NULL | M3 |
| `prompt_version_id` | BIGINT | 是 | NULL | M3 |
| `created_at` | DATETIME(6) | 否 | — | UTC |
| `updated_at` | DATETIME(6) | 否 | — | UTC |

索引/约束：

- 主键 `(id)`
- 唯一 `uk_appeal_annotation (attempt_id, annotation_key, kind)`
- 普通 `idx_appeal_overlay (sentence_id, status, chunk_index)`
- 普通 `idx_appeal_user_status (user_id, status)`

`DISPUTE` 由用户即时生效（`status='ACCEPTED'`, `decided_by='USER'`），只影响错题/统计的批注排除，不改题目内容。`APPEAL` 在 M2 只记录，M3 接 LLM；通过后作为 overlay 参与判分，不物理追加 `sentence_versions.chunks[].answers`。

### 5.11 `reviews`（FSRS 当前投影）

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 | AUTO | 主键 |
| `user_id` | BIGINT | 否 | — | 用户 |
| `sentence_id` | CHAR(26) | 否 | — | 题目身份（跨版本） |
| `mode` | VARCHAR(16) | 否 | — | `CLOZE`/`FREE`；同一句两种模式各一张卡 |
| `state` | VARCHAR(16) | 否 | `NEW` | UI 三档：`NEW`/`LEARNING`/`MASTERED` |
| `fsrs_state` | VARCHAR(16) | 否 | `NEW` | `NEW`/`LEARNING`/`REVIEW`/`RELEARNING` |
| `due_at` | DATETIME(6) | 否 | — | UTC 下次到期 |
| `stability` | DOUBLE | 否 | `0` | FSRS S |
| `difficulty` | DOUBLE | 否 | `0` | FSRS D |
| `reps` | INT | 否 | `0` | 复习次数 |
| `lapses` | INT | 否 | `0` | 遗忘次数 |
| `elapsed_days` | INT | 否 | `0` | 距上次复习天数 |
| `scheduled_days` | INT | 否 | `0` | 上次排出的间隔天数 |
| `last_rating` | SMALLINT | 是 | NULL | 1–4 |
| `last_review_at` | DATETIME(6) | 是 | NULL | 最近复习 |
| `last_attempt_id` | CHAR(36) | 是 | NULL | 最近触发排程的提交 |
| `last_weights_version_id` | BIGINT | 是 | NULL | 最近使用的 FSRS 参数版本 |
| `created_at` | DATETIME(6) | 否 | — | UTC |
| `updated_at` | DATETIME(6) | 否 | — | UTC |

索引/约束：

- 主键 `(id)`
- 唯一 `uk_review_card (user_id, sentence_id, mode)`
- 普通 `idx_review_due (user_id, due_at)`
- 普通 `idx_review_state_due (user_id, state, due_at)`

UI 三档映射（确定性投影）：

- `reps = 0` → `NEW`
- `fsrs_state in (LEARNING, RELEARNING)` → `LEARNING`
- `fsrs_state = REVIEW AND scheduled_days >= 14` → `MASTERED`
- 其余 `REVIEW` → `LEARNING`

`MASTERED` 是展示标签，不是 FSRS 内部状态；答错后回到 `LEARNING/RELEARNING`，不复用“已掌握”掩盖遗忘。

### 5.12 `review_logs`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 | AUTO | 主键 |
| `review_id` | BIGINT | 否 | — | 当前卡 |
| `user_id` | BIGINT | 否 | — | 用户 |
| `sentence_id` | CHAR(26) | 否 | — | 题目 |
| `mode` | VARCHAR(16) | 否 | — | 模式 |
| `attempt_id` | CHAR(36) | 是 | NULL | 触发本次排程的提交；RETRY 不写 |
| `rating` | SMALLINT | 否 | — | 1=AGAIN / 2=HARD / 3=GOOD / 4=EASY |
| `state_before` | VARCHAR(16) | 否 | — | FSRS state |
| `state_after` | VARCHAR(16) | 否 | — | FSRS state |
| `due_before` | DATETIME(6) | 是 | NULL | 原到期 |
| `due_after` | DATETIME(6) | 否 | — | 新到期 |
| `stability_before` | DOUBLE | 是 | NULL | 原 S |
| `stability_after` | DOUBLE | 否 | — | 新 S |
| `difficulty_before` | DOUBLE | 是 | NULL | 原 D |
| `difficulty_after` | DOUBLE | 否 | — | 新 D |
| `elapsed_days` | INT | 否 | — | 本次间隔 |
| `scheduled_days` | INT | 否 | — | 新间隔 |
| `review_at` | DATETIME(6) | 否 | — | 复习时间 UTC |
| `duration_ms` | INT | 是 | NULL | 作答耗时 |
| `weights_version_id` | BIGINT | 否 | — | 本次使用的参数版本 |
| `created_at` | DATETIME(6) | 否 | — | UTC |

索引/约束：

- 主键 `(id)`
- 唯一 `uk_review_log_attempt (attempt_id)`（一个提交最多触发一次排程；NULL 允许多行）
- 普通 `idx_review_log_user_time (user_id, review_at)`
- 普通 `idx_review_log_review (review_id, review_at)`
- 普通 `idx_review_log_sentence (sentence_id, mode)`

只增不改；保留期不短于 FSRS 参数优化所需（V1 不清理，见 §11）。

### 5.13 `fsrs_parameter_versions`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 | AUTO | 主键 |
| `owner_type` | VARCHAR(8) | 否 | — | `GLOBAL` / `USER` |
| `user_id` | BIGINT | 是 | NULL | `USER` 时必填 |
| `algorithm` | VARCHAR(16) | 否 | `FSRS-6` | 算法版本 |
| `weights` | JSON | 否 | — | 21 个 double，见 §6.8 |
| `sample_count` | INT | 否 | `0` | 优化样本数 |
| `status` | VARCHAR(16) | 否 | `CANDIDATE` | `CANDIDATE`/`ACTIVE`/`RETIRED` |
| `metrics` | JSON | 是 | NULL | 优化指标，见 §6.9 |
| `optimized_at` | DATETIME(6) | 是 | NULL | 优化完成时间 |
| `created_by` | BIGINT | 是 | NULL | 管理员/任务 |
| `reason` | VARCHAR(255) | 是 | NULL | 生成/启用原因 |
| `created_at` | DATETIME(6) | 否 | — | UTC |
| `active_owner_key` | VARCHAR(32) | 是 | 生成列 | `IF(status='ACTIVE', CONCAT(owner_type,':',IFNULL(user_id,0)), NULL)` |

索引/约束：

- 主键 `(id)`
- 唯一 `uk_fsrs_active_owner (active_owner_key)`
- 普通 `idx_fsrs_owner_status (owner_type, user_id, status, created_at)`

规则：

- 每个 owner 最多一个 `ACTIVE`；新版本先 `CANDIDATE`，管理员启用后旧的置 `RETIRED`。
- `weights` 不可变；改参数 = 新行。
- 全局默认权重由启动引导幂等写入（无则插入），不走 Flyway 数据文件。
- 完整 FSRS 优化器写入 `CANDIDATE`；没有真实复习数据前不产生候选，不伪造优化结果。

### 5.14 `admin_audit_log`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 | AUTO | 主键 |
| `trace_id` | VARCHAR(64) | 是 | NULL | 链路 id |
| `module` | VARCHAR(32) | 否 | — | `account`/`content`/`practice`/`ai`/`admin`/`analytics`/`system` |
| `actor_id` | BIGINT | 是 | NULL | 真实操作者 id；系统任务为空 |
| `actor_name` | VARCHAR(64) | 否 | — | 用户名快照 |
| `actor_role` | VARCHAR(16) | 是 | NULL | `ADMIN`/`USER`/`SYSTEM` |
| `client_ip` | VARCHAR(45) | 是 | NULL | 客户端 IP |
| `action` | VARCHAR(64) | 否 | — | 稳定英文动作，如 `USER_DISABLE` |
| `target_type` | VARCHAR(32) | 是 | NULL | `USER`/`SENTENCE`/`ATTEMPT`/`IMPORT_BATCH` |
| `target_id` | VARCHAR(64) | 是 | NULL | 对象 id |
| `reason` | VARCHAR(500) | 是 | NULL | 敏感操作必填 |
| `detail` | JSON | 是 | NULL | 脱敏前后值，见 §6.10 |
| `outcome` | VARCHAR(16) | 否 | — | `SUCCESS`/`FAILURE` |
| `duration_ms` | INT | 是 | NULL | 耗时 |
| `request_method` | VARCHAR(16) | 是 | NULL | HTTP 方法 |
| `request_path` | VARCHAR(512) | 是 | NULL | 路径 |
| `created_at` | DATETIME(6) | 否 | — | UTC，分区键 |

索引/约束：

- 主键 `(id, created_at)`（分区表要求分区键在 PK 中）
- 普通 `idx_audit_created (created_at)`
- 普通 `idx_audit_module (module, created_at)`
- 普通 `idx_audit_actor (actor_id, created_at)`
- 普通 `idx_audit_action (action, created_at)`
- 普通 `idx_audit_target (target_type, target_id)`

分区：按 `created_at` 月分区；保留 1 年；到期 `DROP PARTITION`，不做行 DELETE。禁止 UPDATE；应用层只 INSERT。

### 5.15 `error_log`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 | AUTO | 主键 |
| `trace_id` | VARCHAR(64) | 否 | — | 链路 id |
| `module` | VARCHAR(32) | 否 | — | `account`/`content`/`practice`/`ai`/`admin`/`analytics`/`system` |
| `exception_class` | VARCHAR(255) | 否 | — | 异常类全名 |
| `message` | VARCHAR(2000) | 是 | NULL | 脱敏后的消息 |
| `stack_trace` | MEDIUMTEXT | 是 | NULL | 脱敏后的堆栈 |
| `request_method` | VARCHAR(16) | 是 | NULL | HTTP 方法 |
| `request_path` | VARCHAR(512) | 是 | NULL | 路径 |
| `user_id` | BIGINT | 是 | NULL | 真实用户 id，受控后台可检索 |
| `operator` | VARCHAR(64) | 是 | NULL | 用户名快照 |
| `client_ip` | VARCHAR(45) | 是 | NULL | 客户端 IP |
| `created_at` | DATETIME(6) | 否 | — | UTC，分区键 |

索引/约束：

- 主键 `(id, created_at)`
- 普通 `idx_error_created (created_at)`
- 普通 `idx_error_module (module, created_at)`
- 普通 `idx_error_trace (trace_id)`
- 普通 `idx_error_user (user_id, created_at)`

分区：按 `created_at` 月分区；保留 30 天；`DROP PARTITION` 清理，任务可重入。只写未知异常；业务异常/参数校验/404/405/415/越权/CSRF 不写。脱敏只作用于消息/堆栈；`user_id`/`operator` 是受控后台字段，不写入 JSON 日志。

### 5.16 `app_settings`

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `key` | VARCHAR(128) | 否 | — | 配置键 |
| `value` | JSON | 否 | — | 配置值，见 §6.11 |
| `description` | VARCHAR(255) | 是 | NULL | 说明 |
| `version` | INT | 否 | `1` | 乐观版本 |
| `updated_by` | BIGINT | 是 | NULL | 管理员 |
| `updated_at` | DATETIME(6) | 否 | — | UTC |

索引：主键 `(key)`。不放密钥；LLM API Key 只在 `llm_providers.api_key_cipher`（M3）。

### 5.17 `user_daily_stats`（M3 预留，不建）

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `user_id` | BIGINT | 否 | — | 用户 |
| `stat_date` | DATE | 否 | — | 用户本地日期 |
| `timezone` | VARCHAR(64) | 否 | — | 当时时区快照 |
| `attempts_count` | INT | 否 | `0` | 提交数 |
| `sentences_done` | INT | 否 | `0` | 完成句数 |
| `correct_count` | INT | 否 | `0` | 全对 |
| `wrong_count` | INT | 否 | `0` | 未全对 |
| `revealed_count` | INT | 否 | `0` | 看答案 |
| `study_ms` | BIGINT | 否 | `0` | 作答耗时 |
| `error_tags` | JSON | 是 | NULL | 考点计数 |
| `streak_days` | INT | 否 | `0` | 连续天数 |
| `created_at` | DATETIME(6) | 否 | — | UTC |
| `updated_at` | DATETIME(6) | 否 | — | UTC |

主键 `(user_id, stat_date)`；索引 `(stat_date)`。M3 统计页使用；M2 统计先查 `attempts`。

### 5.18 M3 预留表（只登记，不建）

#### `llm_providers`

`id BIGINT PK`、`name VARCHAR(64)`、`kind VARCHAR(32)`（`openai-compatible`/`local`）、`base_url VARCHAR(512)`、`api_key_cipher VARBINARY(2048)`（AES-GCM，主密钥来自环境变量）、`temperature DECIMAL(3,2)`、`max_tokens INT`、`timeout_ms INT`、`enabled BOOLEAN`、`priority INT`、`created_by/updated_by BIGINT`、`created_at/updated_at DATETIME(6)`。

#### `llm_models`

`id BIGINT PK`、`provider_id BIGINT`、`model_name VARCHAR(128)`、`display_name VARCHAR(128)`、`context_window INT`、`input_price_per_1k DECIMAL(12,6)`、`output_price_per_1k DECIMAL(12,6)`、`status VARCHAR(16)`（`ONLINE`/`OFFLINE`）、`online_at/offline_at DATETIME(6)`、`snapshot_key VARCHAR(128)`。

#### `prompt_templates`

`id BIGINT PK`、`code VARCHAR(64) UNIQUE`、`name VARCHAR(128)`、`task_type VARCHAR(32)`（`grade`/`appeal`/`generate`）、`description VARCHAR(500)`、`created_at/updated_at DATETIME(6)`。

#### `prompt_versions`

`id BIGINT PK`、`template_id BIGINT`、`version_no INT`、`content TEXT`、`variables JSON`、`status VARCHAR(16)`（`DRAFT`/`CANDIDATE`/`PRODUCTION`/`RETIRED`）、`created_by BIGINT`、`created_at DATETIME(6)`；唯一 `(template_id, version_no)`；不可变。

#### `llm_usage`

`id BIGINT PK`、`user_id BIGINT NULL`、`provider_id BIGINT`、`model_id BIGINT`、`prompt_version_id BIGINT NULL`、`attempt_grading_id BIGINT NULL`、`purpose VARCHAR(16)`（`GRADE`/`APPEAL`/`TEST`）、`prompt_tokens INT`、`completion_tokens INT`、`cost DECIMAL(12,6)`、`latency_ms INT`、`ok BOOLEAN`、`error_code VARCHAR(64)`、`created_at DATETIME(6)`。

---

## 6. JSON 列结构

所有 JSON 列都带结构版本：`sentence_versions` 用行上的 `schema_version`；其余 JSON 在对象内放 `"schemaVersion"`。解析器必须按版本分支，禁止“看到字段就猜”。

### 6.1 `sentence_versions.chunks`

```json
[
  {
    "index": 0,
    "zh": "他",
    "answers": ["He"],
    "tag": "代词",
    "note": null
  },
  {
    "index": 1,
    "zh": "越来越",
    "answers": ["more and more", "increasingly"],
    "tag": "副词结构",
    "note": "more and more 更口语，increasingly 更书面"
  },
  {
    "index": 2,
    "zh": "重视",
    "answers": ["pay attention to", "attach importance to"],
    "tag": "搭配",
    "note": "attach importance to 更正式"
  }
]
```

字段语义：

- `index`：0 基、稳定、不可变；判分/申诉都用它定位。
- `zh`：语块中文，非空。
- `answers`：可接受译法，至少 1 个；`answers[0]` 是规范答案，用于 `blank` 拼接校验和词数提示。
- `tag`：考点标签；可空，空时用 `tags[0]`，再空用 `未分类`。
- `note`：该语块解析，可空；句子级 `note` 必填。

结构版本：跟随 `sentence_versions.schema_version`；v1 即上述结构。变更字段必须升版本并写迁移/兼容解析。

### 6.2 `sentence_versions.blank`

```json
[1, 2]
```

- 0 基下标数组；每个元素必须存在于 `chunks`；至少 1 个；建议 1–3 个。
- 顺序即题面编号顺序。
- 结构版本同 `sentence_versions.schema_version`。

### 6.3 `sentence_versions.tags`

```json
["副词结构", "搭配"]
```

- 1–5 个字符串；用于统计与“按考点续练”。
- 结构版本同 `sentence_versions.schema_version`。
- 不建全文索引；按 tag 筛选后续用独立统计/物化，不在 V1 加 JSON 索引。

### 6.4 `attempts.answer`

填空：

```json
{
  "schemaVersion": 1,
  "mode": "CLOZE",
  "slots": [
    { "chunkIndex": 1, "value": "more and more" },
    { "chunkIndex": 2, "value": "pay attentions to" }
  ]
}
```

整句：

```json
{
  "schemaVersion": 1,
  "mode": "FREE",
  "text": "He is paying more and more attention to this."
}
```

看答案：

```json
{
  "schemaVersion": 1,
  "mode": "CLOZE",
  "revealed": true,
  "slots": []
}
```

- `schemaVersion` 必填；当前 1。
- `mode` 必须与 `attempts.mode` 一致。
- `CLOZE`：`slots` 覆盖所有 `blank` 下标；允许空串（未填）。
- `FREE`：`text` 原文；不写日志。
- `REVEAL`：`attempts.kind=REVEAL`，`slots` 为空。
- `answer_hash` 对上述对象做规范化 JSON（键排序、去空白）后 SHA-256。

### 6.5 `attempt_gradings.result`

```json
{
  "schemaVersion": 1,
  "score": 80,
  "maxScore": 100,
  "hitCount": 8,
  "totalCount": 10,
  "annotations": [
    {
      "key": "slot:1",
      "chunkIndex": 1,
      "tag": "副词结构",
      "expected": ["more and more", "increasingly"],
      "received": "more and more",
      "correct": true,
      "message": null,
      "minimalFix": null
    },
    {
      "key": "slot:2",
      "chunkIndex": 2,
      "tag": "搭配",
      "expected": ["pay attention to", "attach importance to"],
      "received": "pay attentions to",
      "correct": false,
      "message": "搭配错误",
      "minimalFix": "pay attention to"
    }
  ],
  "summary": "还差 1 处"
}
```

字段语义：

- `key`：`slot:{chunkIndex}`；同一 attempt 内稳定；申诉/不认同用它。
- `tag`：必填（来自 chunk 或句子 tags，兜底 `未分类`）。
- `expected`：可接受答案数组；`received`：用户输入。
- `correct`：该语块是否整块命中。
- `message`/`minimalFix`：可展示批注与最小修改；不写完整译文。
- `summary`：一句话首败提示。
- 版本列 `normalizer_version`/`rubric_version` 在 `attempt_gradings` 行上，不放进 JSON；JSON 的 `schemaVersion` 只描述结构。

### 6.6 `import_batches.error_summary`

```json
{
  "schemaVersion": 1,
  "code": "ROW_VALIDATION_FAILED",
  "message": "3 行未通过校验",
  "sampleRows": [3, 7, 9]
}
```

- 只放批次级摘要；逐行原因在 `import_batch_rows.error_codes`。
- 不写完整译文/密码/token。

### 6.7 `import_batch_rows.error_codes`

```json
[
  {
    "code": "CHUNK_REF_MISMATCH",
    "field": "chunks",
    "message": "chunks[].answers[0] 按序拼接与 ref 不一致"
  },
  {
    "code": "NOTE_TOO_SHORT",
    "field": "note",
    "message": "解析至少 20 字"
  }
]
```

- `code` 是稳定英文枚举；`field` 可空；`message` 中文可展示。
- 逐行原因为空表示通过。

### 6.8 `fsrs_parameter_versions.weights`

```json
[0.2172, 1.1177, 3.2602, 16.1507, 7.0114, 0.57, 2.0966, 0.0069,
 1.5261, 0.112, 1.0178, 1.849, 0.1133, 0.3127, 2.2934, 0.2191,
 3.0004, 0.7536, 0.3332, 0.1437, 0.2]
```

- 21 个 double，FSRS-6 默认权重顺序；长度必须为 21。
- 只允许追加新版本，不改旧行；`java-fsrs` 的 `Scheduler.parameters(double[])` 接受该数组。
- 参数版本与 `algorithm` 绑定；换算法必须新版本。

### 6.9 `fsrs_parameter_versions.metrics`

```json
{
  "schemaVersion": 1,
  "sampleCount": 1200,
  "logLoss": 0.4213,
  "rmse": 0.0812,
  "optimizer": "offline-py-fsrs",
  "optimizerVersion": "6.0"
}
```

- 没有真实优化结果时为空；禁止写假指标。
- `optimizer` 记录实际使用的优化器；M2 运行时可以先只有默认权重，没有候选。

### 6.10 `admin_audit_log.detail`

```json
{
  "schemaVersion": 1,
  "before": { "enabled": true },
  "after": { "enabled": false },
  "note": "用户申请停用"
}
```

- 只放脱敏前后值；禁止密码、token、完整译文。
- 查看答题原文的审计只记录 `attemptId`/`reason`，不把原文复制进 `detail`。

### 6.11 `app_settings.value`

按 key 定义，统一带 `schemaVersion`：

```json
{ "schemaVersion": 1, "value": 0.9 }
```

已登记 key：

| key | 形状 | 说明 |
|---|---|---|
| `fsrs.default_desired_retention` | `{"schemaVersion":1,"value":0.9}` | 全局默认；用户可覆盖 |
| `fsrs.default_weights` | `{"schemaVersion":1,"weights":[...]}` | 首次启动种子；不覆盖已有 ACTIVE |
| `daily_goal.default` | `{"schemaVersion":1,"value":3}` | 1/3/5 |
| `registration.invite_required` | `{"schemaVersion":1,"value":true}` | prod true；local 可 false |
| `llm.monthly_budget_cny` | `{"schemaVersion":1,"value":30}` | M3 |
| `llm.default_model` | `{"schemaVersion":1,"providerId":"1","modelId":"2"}` | M3 |

---

## 7. 枚举总表

| 枚举 | 值 | 落库 | 说明 |
|---|---|---|---|
| `UserRole` | `USER` / `ADMIN` | `users.role` | `ADMIN` 才能访问 `/api/admin/**` |
| `BankVisibility` | `PUBLIC` / `PRIVATE` | `sentence_banks.visibility` | 官方题库 public |
| `ContentStatus` | `DRAFT` / `PUBLISHED` / `DISABLED` / `RECYCLED` / `ARCHIVED` | `sentence_banks.status`、`sentences.status` | 回收站 = `RECYCLED` + `deleted_at` |
| `PracticeMode` | `CLOZE` / `FREE` | `attempts.mode`、`reviews.mode` | 同句两模式两张卡 |
| `AttemptKind` | `FIRST` / `RETRY` / `REVEAL` | `attempts.kind` | 只有 FIRST/REVEAL 可能触发 FSRS |
| `Grader` | `LOCAL` / `GRAMMAR` / `MODEL` / `HUMAN` | `attempt_gradings.grader` | M2 只有 LOCAL |
| `AppealKind` | `APPEAL` / `DISPUTE` | `appeals.kind` | APPEAL 待 LLM；DISPUTE 用户即时排除 |
| `AppealStatus` | `PENDING` / `ACCEPTED` / `REJECTED` / `WITHDRAWN` | `appeals.status` | — |
| `ReviewState` | `NEW` / `LEARNING` / `MASTERED` | `reviews.state` | UI 三档投影 |
| `FsrsState` | `NEW` / `LEARNING` / `REVIEW` / `RELEARNING` | `reviews.fsrs_state`、`review_logs.state_*` | FSRS 内部状态 |
| `FsrsRating` | `1=AGAIN` / `2=HARD` / `3=GOOD` / `4=EASY` | `review_logs.rating`、`reviews.last_rating` | 由分数/提示/耗时派生 |
| `FsrsParameterStatus` | `CANDIDATE` / `ACTIVE` / `RETIRED` | `fsrs_parameter_versions.status` | 每个 owner 一个 ACTIVE |
| `ImportStatus` | `PREVIEWING` / `PREVIEWED` / `COMMITTING` / `COMMITTED` / `FAILED` / `ROLLED_BACK` / `EXPIRED` | `import_batches.status` | 预览与提交分离 |
| `ImportRowStatus` | `VALID` / `INVALID` / `IMPORTED` / `SKIPPED` | `import_batch_rows.status` | — |
| `ImportFormat` | `JSON` / `CSV` | `import_batches.format` | 大小上限 5MB |
| `AuditOutcome` | `SUCCESS` / `FAILURE` | `admin_audit_log.outcome` | — |
| `ErrorModule` | `account` / `content` / `practice` / `ai` / `admin` / `analytics` / `system` | `error_log.module` | 与日志 module 同源 |
| `PromptStatus` | `DRAFT` / `CANDIDATE` / `PRODUCTION` / `RETIRED` | `prompt_versions.status` | M3 |
| `ModelStatus` | `ONLINE` / `OFFLINE` | `llm_models.status` | M3 |
| `LlmPurpose` | `GRADE` / `APPEAL` / `TEST` | `llm_usage.purpose` | M3 |

### 7.1 CHECK 约束（MySQL 8.0.16+ 强制执行）

| 表 | 约束 |
|---|---|
| `users` | `role IN ('USER','ADMIN')`；`daily_goal IN (1,3,5)`；`desired_retention BETWEEN 0.800 AND 0.970` |
| `sentence_banks` | `visibility IN ('PUBLIC','PRIVATE')`；`status IN ('DRAFT','PUBLISHED','DISABLED','RECYCLED','ARCHIVED')` |
| `sentences` | `status` 同上 |
| `attempts` | `mode IN ('CLOZE','FREE')`；`kind IN ('FIRST','RETRY','REVEAL')`；`hints_used BETWEEN 0 AND 3`；`duration_ms IS NULL OR duration_ms BETWEEN 0 AND 86400000` |
| `attempt_gradings` | `grader IN ('LOCAL','GRAMMAR','MODEL','HUMAN')`；`score IS NULL OR score BETWEEN 0 AND 100`；`max_score = 100` |
| `appeals` | `kind IN ('APPEAL','DISPUTE')`；`status IN ('PENDING','ACCEPTED','REJECTED','WITHDRAWN')` |
| `reviews` | `state IN ('NEW','LEARNING','MASTERED')`；`fsrs_state IN ('NEW','LEARNING','REVIEW','RELEARNING')`；`last_rating IS NULL OR last_rating BETWEEN 1 AND 4` |
| `review_logs` | `rating BETWEEN 1 AND 4`；`state_before/state_after` 同上 |
| `fsrs_parameter_versions` | `owner_type IN ('GLOBAL','USER')`；`status IN ('CANDIDATE','ACTIVE','RETIRED')`；`owner_type='USER' OR user_id IS NULL` |
| `import_batches` | `format IN ('JSON','CSV')`；`status` 见 §7 |
| `import_batch_rows` | `status IN ('VALID','INVALID','IMPORTED','SKIPPED')` |
| `admin_audit_log` | `module IN ('account','content','practice','ai','admin','analytics','system')`；`outcome IN ('SUCCESS','FAILURE')` |
| `error_log` | `module IN ('account','content','practice','ai','admin','analytics','system')` |

业务校验（应用层，不能只靠数据库）：

- `users.email` 可空但非空时必须含 `@`；`username` 3–64、字母数字 `._-`。
- `sentence_versions.zh` 4–60 字；`ref` 8–300 字符；`note` 20–300 字；`chunks/blank` 满足 PRD §6 五条。
- `attempts.kind=RETRY` 时 `retry_of_id` 必填且属于同一 `user_id`/`sentence_id`/`mode`。
- `attempts.kind=REVEAL` 时 `answer.revealed=true`。
- `admin_audit_log` 的 `VIEW_ATTEMPT_RAW`、`USER_DISABLE`、`USER_DELETE`、`ROLE_CHANGE` 必须填 `reason`。

---

## 8. FSRS 设计

### 8.1 为什么是 FSRS

PRD §5.3 的三档 + `streak` 无法表达“同一档内难度差异”和“个体遗忘曲线”，且 `due` 只有日期，跨时区会漂移。FSRS 用 `stability`（记忆稳定性）、`difficulty`（题目难度）、`retrievability`（可提取概率）和期望保留率计算下次间隔，能同时满足“答错次日再来”和“长期少复习”。

### 8.2 四档评分从哪来

不新增自评按钮，避免打断练习；从已有信号派生：

| 条件 | Rating | 说明 |
|---|---|---|
| `kind=REVEAL` 或 `score < 60` | `AGAIN(1)` | 没想起来 / 看答案 |
| `60 <= score < 100` | `HARD(2)` | 部分对，仍要重打 |
| `score = 100` 且 `hints_used > 0` | `GOOD(3)` | 全对但用了提示 |
| `score = 100`、`hints_used = 0`、`duration_ms <= 60000` | `EASY(4)` | 首次全对且快 |
| `score = 100`、`hints_used = 0`、`duration_ms > 60000` 或空 | `GOOD(3)` | 全对但不轻松 |

只有 `kind=FIRST` 和“没有 FIRST 的 REVEAL”会触发排程；`RETRY` 不触发。`review_logs.attempt_id` 唯一约束保证一个提交最多排程一次。

### 8.3 调度库与参数

- 调度使用 `io.github.open-spaced-repetition:fsrs:1.0.0`（MIT，Java 17，2025-07 发布；白名单外依赖，理由见 D-017）。它提供 `Scheduler`、`Card`、`ReviewLog`、四档 `Rating`、`desiredRetention`、`learningSteps`/`relearningSteps`、`maximumInterval`、`enableFuzzing`。
- 该库**不提供参数优化器**。完整 FSRS 的优化器是独立切片：读取 `review_logs`，产出 `CANDIDATE` 权重，写 `fsrs_parameter_versions`；管理员启用后旧 `ACTIVE` 置 `RETIRED`，写 `admin_audit_log`。
- M2 运行时先用全局默认权重 + 用户 `desired_retention`；没有真实数据前不生成候选权重，不伪造“已优化”。
- `desired_retention` 默认 0.90，范围 0.80–0.97；用户可在设置中改，重建 Scheduler。
- 学习步长：`learning_steps = [1m, 10m]`，`relearning_steps = [10m]`，`maximum_interval = 36500` 天，`enable_fuzzing = true`；具体值放 `app_settings`，不硬编码在代码。

### 8.4 状态机

```
NEW --FIRST/REVEAL--> LEARNING/RELEARNING --rating>=GOOD--> REVIEW
REVIEW --AGAIN--> RELEARNING
RELEARNING --rating>=GOOD--> REVIEW
任意状态 --连续多次 AGAIN--> LEARNING（由 FSRS 库决定）
```

- 答错：`AGAIN`，`lapses++`，`due_at` 由 FSRS 给出（通常是分钟级学习步，而非 PRD 的“次日”）。  
  **注意**：PRD §5.3 写“答错次日到期”；FSRS 默认学习步是 1 分钟/10 分钟。为兼顾产品预期，V1 将 `learning_steps` 配成 `[1d]`/`[1d]`，即答错次日；后续可通过 `app_settings` 改成分钟级。该取舍登记在 D-006。
- 答对：按 FSRS 计算 `stability/difficulty/due_at`。
- `MASTERED` 只是 UI：`fsrs_state=REVIEW AND scheduled_days >= 14`。
- 复习队列：`WHERE user_id=? AND due_at <= now()`，按 `due_at` 升序；不额外维护“今日队列”表。

### 8.5 参数优化

- 数据源：`review_logs`（rating、时间、S/E/D 前后值、间隔）。
- 触发：管理员手动触发或每日低峰任务；样本数 < 500 不运行，避免过拟合。
- 输出：`fsrs_parameter_versions` 新 `CANDIDATE`，`metrics` 记录 logLoss/RMSE；不自动 ACTIVE。
- 启用：管理员确认后 ACTIVE；旧 ACTIVE RETIRED；写 `admin_audit_log`。
- 回滚：把旧版本重新 ACTIVE，新版本 RETIRED；不删除版本。
- 实现位置：M2.1 独立切片；M2 不承诺优化器上线，只承诺表和调度。

---

## 9. 幂等与唯一约束

| 场景 | 约束 | 行为 |
|---|---|---|
| 同一 `attemptId` 重复提交 | `attempts.id` PK | 同用户同 `answer_hash` → 200 返回已有结果；不同 → 409 `ATTEMPT_ID_REUSED` |
| 跨设备离线重放 | 同上 | 同一用户复用它，返回原结果；不重复扣复习/统计 |
| 游客升级 | 游客不落服务端 | 注册时把本地未上传 attempt 作为新用户提交；不回填 `user_id` |
| 判分复核 | `(attempt_id, sequence)` 唯一 | 追加新 grading；不覆盖 |
| 复习排程 | `(user_id, sentence_id, mode)` 唯一；`review_logs.attempt_id` 唯一 | 一次 due 周期只排一次 |
| 导入提交 | `import_batches.id` PK；行 `(batch_id,row_no)` 唯一 | 提交幂等；重复提交返回已有批次 |
| 注册邀请码 | `code_hash` 唯一；`used_by` 唯一 | 事务锁码；一码一人 |
| 题库/句子精确重复 | `(bank_id, active_fingerprint)` 唯一 | 未删重复硬拦；已删可重新导入 |

---

## 10. 索引与分区

### 10.1 只凭访问模式建的索引

| 表 | 索引 | 访问模式 |
|---|---|---|
| `users` | `(email)`、`(username)`、`(role, enabled)`、`(deleted_at)` | 登录、后台筛选、软删过滤 |
| `invite_codes` | `(code_hash)`、`(used_by)`、`(expires_at)` | 注册校验、后台列表、过期清理 |
| `sentence_banks` | `(owner_id, status)`、`(visibility, status)` | 我的题库、公共题库 |
| `sentences` | `(bank_id, version)`、`(bank_id, status, deleted_at)`、`(bank_id, active_fingerprint)` | 增量拉取、练习选题、导入去重 |
| `sentence_versions` | `(sentence_id, version_no)`、`(bank_id, created_at)` | 当前版本、回放 |
| `import_batches` | `(bank_id, created_at)`、`(status)` | 批次列表、恢复 |
| `import_batch_rows` | `(batch_id, row_no)`、`(batch_id, status)` | 逐行原因、提交 |
| `attempts` | `(user_id, created_at)`、`(user_id, sentence_id, mode, created_at)`、`(sentence_version_id)` | 统计、错题、回放 |
| `attempt_gradings` | `(attempt_id, sequence)`、`(created_at)` | 最新判分、审计 |
| `appeals` | `(attempt_id, annotation_key, kind)`、`(sentence_id, status, chunk_index)`、`(user_id, status)` | 申诉、overlay |
| `reviews` | `(user_id, due_at)`、`(user_id, state, due_at)`、`(user_id,sentence_id,mode)` | 到期队列、筛选、卡片 |
| `review_logs` | `(user_id, review_at)`、`(review_id, review_at)`、`(attempt_id)` | FSRS 优化、回放 |
| `fsrs_parameter_versions` | `(owner_type,user_id,status,created_at)`、`active_owner_key` | 取 ACTIVE、版本历史 |
| `admin_audit_log` | `(created_at)`、`(module, created_at)`、`(actor_id, created_at)`、`(action, created_at)`、`(target_type,target_id)` | 后台筛选 |
| `error_log` | `(created_at)`、`(module, created_at)`、`(trace_id)`、`(user_id, created_at)` | 后台检索 |
| `user_daily_stats` | `(user_id, stat_date)`、`(stat_date)` | 统计 |

**不建**：全文索引、JSON 多值索引、向量索引。AGENTS §7 明确禁止在未实测慢查询前上；V1 的后台日志关键字检索用 `LIKE`，数据量小，等有慢查询证据再加。

### 10.2 分区

| 表 | 分区 | 保留 | 清理 |
|---|---|---|---|
| `error_log` | `RANGE (TO_DAYS(created_at))` 月分区 | 30 天 | `DROP PARTITION`，任务可重入 |
| `admin_audit_log` | `RANGE (TO_DAYS(created_at))` 月分区 | 1 年 | `DROP PARTITION`，任务可重入 |

- 分区表主键必须含 `created_at`：`PRIMARY KEY (id, created_at)`。
- 应用层不做 `DELETE FROM error_log/admin_audit_log`；保留期用分区 DDL。
- 迁移时预建当前月 + 未来 3 个月分区；定时任务补未来分区。
- `attempts`、`review_logs` 不分区；V1 数据量小，且 `review_logs` 是 FSRS 优化数据，不能按 90 天清。

---

## 11. 保留与删除

| 数据 | 策略 |
|---|---|
| `users` | 软删 30 天后匿名化 `email/username/password_hash`；答题/审计保留 |
| `sentence_banks` / `sentences` | 软删进回收站；不硬删；删除 bump `version` |
| `sentence_versions` | 永不删除；随题目保留 |
| `attempts` / `attempt_gradings` | 只增不改；V1 不按 90 天清理（PRD §16 的 90 天保留与 AGENTS §2.5 冲突，登记为待定，见 D-011） |
| `review_logs` | 只增不改；至少保留 2 年或至用户删号，FSRS 优化需要 |
| `admin_audit_log` | 1 年，月分区 DROP |
| `error_log` | 30 天，月分区 DROP |
| `import_batches` / `import_batch_rows` | 批次保留 1 年；原文件 30 天；行级 raw 90 天（后续清理任务） |
| `app_settings` | 长期；变更写审计 |

**禁止**：硬删题目、级联删 attempt、原地 UPDATE 覆盖历史分数、删除 `sentence_versions`。

---

## 12. 迁移顺序与 Flyway 规划

### 12.1 版本规划

> 当前仓库已有 V1–V6 的历史（V5/V6 顺序错位，测试库曾用 out-of-order）。本次收口后**不使用 out-of-order**；`yilian_test` 可重建；`yilian` 本地库只到 V3。

| 版本 | 内容 | 说明 |
|---|---|---|
| `V1__baseline.sql` | 历史 `app_user`、`spring_session`、`spring_session_attributes` | 保留已应用历史，不重写 |
| `V2__error_log.sql` | `error_log` | 30 天 |
| `V3__operation_log.sql` | 历史 `operation_log` | 保留已应用历史 |
| `V4__rate_limit_idempotency.sql` | `idempotency_record` | 通用幂等；attempts 不用它 |
| `V5__account_and_admin_audit.sql` | `app_user` → `users`，补 §5.1 字段；新建分区 `admin_audit_log`，复制旧 `operation_log` 可保留字段，补 `actor_id/actor_name/actor_role/target_*/reason`，最后删除旧表 | 账号与审计收口；历史日志无法恢复真实 actor 的置空 |
| `V6__import_batches.sql` | `import_batches` + `import_batch_rows`（按 PRD 重建） | 预览/提交/回滚；旧 `import_batch` 删除并重建 |
| `V7__content_core.sql` | `sentence_banks`、`sentences`、`sentence_versions` | 版本与增量 |
| `V8__practice_core.sql` | `attempts`、`attempt_gradings`、`appeals`、`reviews`、`review_logs`、`fsrs_parameter_versions` | M2 |
| `V9__analytics.sql`（M3） | `user_daily_stats` | 暂不建 |
| `V10__ai.sql`（M3） | `llm_providers`、`llm_models`、`prompt_templates`、`prompt_versions`、`llm_usage` | 暂不建 |

规则：

1. 已应用的迁移不改 checksum；新增变更一律新版本号。
2. `V5`/`V6` 重排后，`yilian_test` 直接 drop 重建；`yilian` 本地库从 V3 顺序升到 V6。
3. 生产迁移前自动快照：`mysqldump --single-transaction --routines --triggers`；快照文件不进 git。
4. `out-of-order` 在所有 profile 固定 `false`；不靠它掩盖版本错位。
5. 迁移在一个 `V*` 文件内按“表 → 索引 → 约束 → 触发器 → 分区”顺序；不要跨文件循环依赖。
6. `sentence_versions` 的不可变触发器必须在建表后立刻创建；`attempts`/`attempt_gradings`/`review_logs` 同理。
7. `error_log`/`admin_audit_log` 的分区建表语句与索引一起；后续月份由 `PartitionMaintenanceJob` 补，不走 Flyway。

### 12.2 前置快照

- 本地：`scripts/snapshot-local.ps1`（待建）在 `mvn flyway:migrate` 前执行 `mysqldump` 到 `.runtime/snapshots/`（已忽略）。
- 生产：发布流水线第一步做全量快照 + binlog 位点；失败即停止迁移。
- 恢复演练：每季度一次，记录 RPO/RTO。

### 12.3 测试库

- `yilian_test` 由集成测试创建；本次收口时允许 `DROP DATABASE yilian_test` 后重建。
- 测试不使用 `out-of-order`；`AuthFlowIntegrationTest` 去掉 `spring.flyway.out-of-order=true`。
- 本地 `application-local.yml` 去掉 `spring.flyway.out-of-order: true`。

---

## 13. 决策记录

| 编号 | 决定 | 理由 | 被否方案 | 影响面 |
|---|---|---|---|---|
| D-001 | `attempts` 拆提交 + `attempt_gradings` 判分；重复 attemptId 返回原结果；游客只落本地 | 幂等、改判追加、统计不污染、append-only 一次满足 | 单表 UPDATE；复核写新 attempt；游客服务端落库+所有权迁移表 | practice 表、API、统计、游客升级 |
| D-002 | `sentences` 身份 + `sentence_versions` 快照；`sentences.version` 为银行级游标 | 改题可回放、增量 tombstone、导入回滚 | 只保留 `sentences.version` 原地更新；单独 `content_changes` 表 | content 表、增量接口、attempt 绑定 |
| D-003 | 软删统一 `deleted_at/deleted_by`；`enabled` 仅启停 | 避免“disabled 当删除”歧义；审计可追溯 | `enabled=false` 当软删；`is_deleted` 布尔 | 所有可软删表 |
| D-004 | `due_at DATETIME(6)` UTC；日界用用户 IANA 时区；`stat_date DATE` 仅用于本地日聚合 | DATE 无法表达跨时区到期瞬间 | `due DATE`；全库本地时间 | reviews、统计、API |
| D-005 | `error_log`/`admin_audit_log` 存真实 actor/user id + 快照名；JSON 日志仍脱敏 | 后台要按用户检索；脱敏只针对日志输出 | 表里也存 `u***56` | 审计、异常页、日志 |
| D-006 | 采用完整 FSRS：四档评分、期望保留率、参数版本；M2 先调度+默认权重，优化器 M2.1 | 用户明确选择 FSRS；无数据时优化器不可验证 | 三档 SRS；FSRS 只预留字段；现在上完整优化器 | M2 范围、`reviews`/`review_logs`、依赖、测试 |
| D-007 | 导入按 PRD 重建：预览→提交→批次；现有 `import_batch` 仅上传基础设施 | PRD §16 要求行级原因/整批回滚；异步轮询不能替代预览 | 保留现有 upload→PROCESSING→resume；只做前端 mock | content/import API、前端 ImportPage、V5/V6 |
| D-008 | 最终表名：`users`、`admin_audit_log`、`import_batches`；弃用 `app_user`/`operation_log`/`import_batch` | 与 PRD §8/§18 对齐 | 保留旧名 + 文档映射 | 迁移、JPA 实体、测试 SQL |
| D-009 | 不建全文/JSON/向量索引；后台日志用 LIKE，等慢查询实测 | AGENTS §7 明确禁止提前上 | 先上 FULLTEXT/多值索引 | 日志检索性能、后续迁移 |
| D-010 | Flyway 版本连续；移除 `out-of-order`；测试库可重建；已应用迁移不改 | 迁移必须可复现 | 保留 out-of-order；改历史迁移 | 迁移、CI、本地启动 |
| D-011 | V1 不按 90 天清理 attempts；保留 append-only | PRD §16 与 AGENTS §2.5 冲突；先保证错题/复习可回放 | 90 天硬删；拆原始文本表 | 保留策略、存储、后续归档 |
| D-012 | 内部 ID BIGINT，attempt UUID，sentence ULID；API 中 BIGINT 序列化为字符串 | 避免 JS 精度；客户端 UUID 幂等 | 全 BIGINT 自增 attempt；全 UUID | API 类型、前端 Zod |
| D-013 | REST 统一 `ApiResponse` + `ApiCode`，不用 `ProblemDetail` | 新 AGENTS §3.6 已改；前端已按信封实现 | ProblemDetail + 枚举 | API.md、openapi、前端 |
| D-014 | 邀请码只存 SHA-256 + 前缀；一码一人 | 不在库中存明文凭据；可展示列表 | 明文 code；JWT 邀请 | 注册、后台 |
| D-015 | 申诉通过不物理追加 `chunks[].answers`，用 `appeals` overlay | 已发布题目不可变；避免原地 UPDATE | 追加到 sentence_versions；新建版本 | 判分、申诉、内容版本 |
| D-016 | 不建 `practice_sessions`/`user_settings`/`sentence_answer_variants` | 分别可派生/并入 users/用 overlay | 各建一张表 | 表数量、查询 |
| D-017 | 新增 `io.github.open-spaced-repetition:fsrs:1.0.0`（MIT，Java 17）；优化器不随库提供 | 自研调度风险高；库维护活跃、API 覆盖四档/保留率/参数 | 自研 FSRS；JNI `fsrs-rs`；Python sidecar | 依赖白名单、构建、漏洞面 |
| D-018 | attempt 幂等以 `attempts.id` PK 为准；同 ID 不同载荷 409 | AGENTS §3 唯一约束兜底；离线重放要返回成功 | 通用 `idempotency_record` 409；应用层内存去重 | practice API、错误码 |
| D-019 | `review_logs` 不按 90 天清理；至少 2 年 | FSRS 参数优化需要历史 | 与 attempts 一起清 | 存储、优化器 |
| D-020 | FSRS UI 三档是投影：`REVIEW && scheduled_days>=14` 才显示已掌握 | 保留 PRD 三档，又不丢 FSRS 内部状态 | 直接用 FSRS 四态展示；两次全对即掌握 | 复习页、API、测试 |
| D-021 | `error_log`/`admin_audit_log` 月分区，保留期 DROP PARTITION | 避免大表 DELETE；满足 30 天/1 年 | 单表 DELETE；月表 | 迁移、运维、清理任务 |
| D-022 | 迁移前自动快照；生产禁止 `flyway clean` | AGENTS §3 迁移前快照 | 无快照直接 migrate | 发布流程、恢复 |

---

## 14. 需求增量登记

| 编号 | 需求 | 来源 | 判断 | 理由 / 影响 |
|---|---|---|---|---|
| R-01 | 完整 FSRS（四档、期望保留率、参数优化） | 本轮 Q4 用户选择 | **进本次范围（扩大）** | 超出 PRD §5.3 三档；新增 `review_logs`/`fsrs_parameter_versions`/依赖；M2 出口改为“FSRS 调度可用”，优化器 M2.1 |
| R-02 | 导入按 PRD 重建（预览/提交/行级原因/回滚） | 本轮 Q2 用户选择 | **进本次范围** | 现有异步 `import_batch` 只保留为上传基础设施；前端 ImportPage 需改 |
| R-03 | attempts 拆提交/判分；重复返回原结果；游客本地 | 本轮 Q1 用户选择 | **进本次范围** | 影响 practice 表、API、统计 |
| R-04 | 句库身份 + 不可变快照 + 增量游标 | 本轮 Q2 用户选择 | **进本次范围** | 影响 content 表、增量接口、attempt 绑定 |
| R-05 | 迁移顺序收口 + 移除 out-of-order | 本轮 Q5 用户选择 | **进本次范围** | 影响 V5/V6、测试库、CI |
| R-06 | 三题定级 | GAP P0 / 原型 | **推后** | 本次页面清单未列；不建表、不写 API |
| R-07 | 积压熔断 / 顺延 | GAP P0 / 原型 | **推后 V2** | FSRS 已负责调度；降载 UI 后续 |
| R-08 | LLM 申诉裁决 / 复核 | PRD §5.1 / M3 | **M3 实现** | M2 只记录 appeal/dispute；不伪造 LLM |
| R-09 | 模型目录 / Prompt / 用量 / 系统配置 | PRD §17 | **M3 建表** | 只登记；`app_settings` M1 例外 |
| R-10 | 统计看板 / user_daily_stats | PRD §4 / M3 | **M3** | M2 先查 attempts；M3 建聚合表 |
| R-11 | 2FA / IP 白名单 | GAP P0 | **推后公网前** | 不影响本次表结构 |
| R-12 | 用户导出 / 删号匿名化 | GAP P0 | **推后** | `users.deleted_at` 预留；30 天后匿名化 |
| R-13 | PWA / 离线队列 | PRD M4 | **推后** | 前端可做离线提示；不做同步表 |
| R-14 | 题库五态 / 回收站 | GAP P0 / PRD §16 | **进本次** | `status` + `deleted_at` |
| R-15 | 90 天 attempts 清理 | PRD §16 | **推后/待定** | 与 append-only 冲突；见 D-011 |
| R-16 | 全文索引 / 向量库 | AGENTS §7 | **否决** | 未实测慢查询 |
| R-17 | 申诉通过物理追加 `answers` | PRD §5.1 | **否决“原地追加”** | 用 appeals overlay；不改已发布版本 |
| R-18 | `auth_sessions` 自建会话表 | 早期建议 | **否决** | 新 AGENTS 已定 Spring Session JDBC |
| R-19 | `user_settings` / `practice_sessions` / `sentence_answer_variants` | 早期候选 | **否决** | 并入 users / 派生 / overlay |

---

## 15. 待确认 / 默认值

| 项 | 默认 | 需要你确认 |
|---|---|---|
| FSRS 优化器上线时间 | M2.1，不阻塞 M2 调度 | 是否接受“表先建、优化器后置”；否则 M2 需再扩 |
| FSRS 学习步长 | `[1d]`，答错次日 | 是否坚持 PRD 的“次日”；若改成分钟级，产品文案要改 |
| FSRS 四档映射 | 按 §8.2 | 是否接受由分数/提示/耗时派生，不新增自评按钮 |
| 邀请码 | M1 建表；local 可关、prod 开 | 注册是否 V1 就强制邀请码 |
| `users` 改名 | 由 `app_user` 迁移为 `users` | 是否保留 `app_user` 兼容旧代码 |
| `admin_audit_log` 改名 | 由 `operation_log` 迁移 | 同上 |
| attempts 90 天保留 | V1 不清理 | 是否接受与 PRD §16 的偏差 |
| `java-fsrs` 依赖 | 批准 | 白名单外依赖；需要你确认 D-017 |
| 三题定级 | 已闭环（2026-10-24）：纯本地前端先行 | 不建表、不写 API，R-06 维持；经用户确认纳入本轮页面清单 |

---

## 16. 对现有骨架的直接改动

| 现有实现 | 目标 | 影响文件 |
|---|---|---|
| `app_user` | 迁移为 `users`，补账号字段 | `AppUser.java`、`AppUserDao`、`AuthFlowIntegrationTest`、所有 SQL |
| `operation_log` + 脱敏 `user_id` | 新建 `admin_audit_log`，存 `actor_id/actor_name` | `JdbcOperationLogRecorder`、`OperationLogAspect`、`LogCleanupJob`、测试 |
| `error_log.user_id VARCHAR(32)` 脱敏值 | `user_id BIGINT` + `operator`；月分区 | `V2` 重建、`JdbcErrorLogRecorder`、后台检索 |
| `import_batch`（upload→PROCESSING→resume） | `import_batches` + `import_batch_rows`（preview→commit→rollback） | `upload/**`、`ImportPage.tsx`、`ImportPage.test.tsx`、API 客户端 |
| `spring.flyway.out-of-order=true` | 移除；V5/V6 重排；测试库重建 | `application-local.yml`、`AuthFlowIntegrationTest`、迁移文件 |
| `RateLimitDecision.allowed()` 静态工厂与 record accessor 冲突 | 已修为 `permit()`；保留测试 | `RateLimitDecision`、`RateLimitService` |
| `StorageProperties` 格式 | `mvn fmt:format` 归一 | `StorageProperties.java` |

> 这些改动应在 `docs/API.md` 冻结前完成最小收口；字段变更同步 `docs/DATA-MODEL.md` 变更记录。

---

## 17. 变更记录

| 日期 | 版本 | 变更 | 作者 |
|---|---|---|---|
| 2026-10-24 | v0.1 | 初稿：实体边界、FSRS、表结构、迁移规划 | 技术负责人 |
| 2026-10-24 | v0.1.1 | 补 `admin_audit_log.module`，满足后台按模块筛选 | 技术负责人 |
