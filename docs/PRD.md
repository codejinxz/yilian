# 译练 · 产品需求文档（V1）

| 项 | 内容 |
|---|---|
| 版本 | v1.0 |
| 日期 | 2026-10-23 |
| 状态 | 定稿 |
| 范围 | V1：语块填空 + 整句翻译 |

## 0. 一页速览

「译练」是面向中文母语者的中译英刻意练习工具。用户看到中文句子，先以语块填空把关键表达写对，再过渡到整句自由翻译；系统即时给出可解释的批改，并把错句排进复习队列。

V1 是单机部署的 Web 应用（PWA），先本机使用，但预留多用户注册与管理员题库导入。

## 1. 定位与非目标

### 1.1 一句话

把「翻译练习」从模糊的自我感觉，变成每句都有明确判定和批注的刻意练习。

### 1.2 解决的问题

- 市面产品多为选择题或固定答案，练不到自由产出
- 自由翻译无人批改，写了也不知道错在哪
- 逐词对照会强化直译习惯，反而损害语感

### 1.3 非目标（V1 明确不做）

- 不做社交、排行榜、打卡社区
- 不做语音与口语评分
- 不做原生 App
- 不做多语言界面
- 不做付费与订阅
- 不做公开题库市场

## 2. 用户与身份

| 身份 | 标识 | 数据归属 | 能力 |
|---|---|---|---|
| 游客 | `device_id`（浏览器生成 UUID） | 本地 IndexedDB | 刷公共题库，记录留本地 |
| 用户 | `user_id` | 服务端 | 错题、复习队列、个人题库、跨设备 |
| 管理员 | `user_id` + `role=ADMIN` | 服务端 | 导入/发布公共题库、管理用户 |

游客升级：注册时按 `device_id` 把本地 attempts 迁移到新账号，迁移成功后清空本地队列。

游客练习引擎（2026-10-24 增补）：在服务端练习切片落地前，游客与登录用户的练习/复习/统计由前端本地引擎承担——匿名拉取公开题库（`GET /api/banks/**`，PUBLIC_OR_USER），判分与排程全部本地完成，记录只存本机；服务端零游客态（DATA-MODEL D-001）。

## 3. 核心循环

取题 → 作答 → 即时判定 → 批注 → 入错题 → 到期复习

一屏一句。没有侧边栏，没有仪表盘。

## 4. V1 范围

| 模块 | V1 | 说明 |
|---|---|---|
| 语块填空 | ✅ | 主练习模式，判定 100% 本地 |
| 整句翻译 | ✅ | 本地即时反馈 + 服务端深度批改 |
| 语块排序 | ❌ V2 | |
| 改错 | ❌ V2 | |
| 账号与权限 | ✅ | 注册、登录、游客升级、管理员 |
| 题库导入 | ✅ | 管理员，JSON/CSV |
| 错题与复习 | ✅ | 简化三档 SRS（见 5.3） |
| 统计 | ✅ | 基础：正确率、错误类型分布 |
| 离线 | ✅ | 填空完全离线；整句离线仅本地反馈 |

## 5. 练习模式

### 5.1 语块填空

界面：中文原句在上，需要填的语块用朱红下划线标出并编号；英文骨架在下，挖空按单词拆分，一个单词一个输入框。

判定流程：

1. 归一化：转小写、去首尾空格、压缩连续空格、弯引号→直引号、全角→半角、去句末标点
2. 逐词与 `answers[]` 对应位置比对；整块连续输入命中任一 answer 时，整个语块算全对
3. 全对 100 分；部分对 = 命中词数 / 总词数 × 100
4. 任一词未命中 → 该句进入错题

提示：首字母 / 词数 / 答案三级，按语块编号给出。

看答案：直接填入正确单词，本题记为学习中，不计入正确率；参考译文与解析照常展示。

布局：输入框随输入内容变宽，长单词不截断；英文原句较长时自动缩小字号，避免单词被遮挡。

申诉：每槽旁有「我觉得也对」。异步送 LLM 裁决；通过则把答案追加进 `answers`，标记 `source: "llm"`，并记录一次 `appeal` 事件。

提示：首字母按钮，默认关闭。

两种模式都支持一次「模型复核」：本地判分后，用户可请求复核，复核给调整后的分数与理由。复核作为新记录追加，不覆盖原判分；复核失败保留原分，不阻塞下一题。

### 5.2 整句翻译

界面：只显示中文，自由输入。

两阶段反馈：

| 阶段 | 位置 | 时限 | 内容 |
|---|---|---|---|
| 本地 | 客户端 | < 100ms | 要点覆盖、词级 diff |
| 深度 | 服务端 | P95 ≤ 8s | LanguageTool 语法 + LLM 语义点评 |

深度批改失败（超时 / 报错 / 校验不过）→ 保留本地结果，标注「深度批改暂不可用」，不阻塞下一题。

LLM 输出必须过 JSON Schema 校验；失败重试 1 次；仍失败则降级。

### 5.3 复习（简化 SRS）

V1 只做三档：`新题` / `复习中` / `已掌握`。

- 答错 → 进复习队列，次日到期
- 答对 → 间隔 1 → 3 → 7 → 14 天
- 连续两次全对 → 已掌握

V2 换 FSRS，字段已预留（见 8）。

## 6. 句库规范

```json
{
  "id": "s_0001",
  "zh": "他越来越重视这件事。",
  "ref": "He is paying more and more attention to this.",
  "note": "「越来越」用 more and more 或 increasingly；「重视」用 attach importance to 更书面。",
  "chunks": [
    { "zh": "他", "answers": ["He"] },
    { "zh": "越来越", "answers": ["more and more", "increasingly"] },
    { "zh": "重视", "answers": ["pay attention to", "attach importance to"] },
    { "zh": "这件事", "answers": ["this", "this matter"] }
  ],
  "blank": [1, 2],
  "tags": ["副词结构", "搭配"],
  "level": "TEM8",
  "source": "自建",
  "version": 1
}
```

导入校验（全部必过，否则该行标红并给出原因）：

1. `zh` 长度 4–60 字
2. `chunks` 非空，每块至少 1 个答案，答案非空
3. `blank` 下标必须存在于 `chunks`
4. `chunks[].answers[0]` 按序以空格拼接后，与 `ref` 的归一化形式相等（允许标点差异）
5. `ref` 非空，长度 8–300 字符
6. `note` 必填，20–300 字，说明考点与易错点，不能只写「注意搭配」

第 4 条是最有价值的自动检查，能挡掉大部分脏数据。解析随导入一起录入，缺解析的行不允许导入；练习提交后展示该解析。

## 7. 账号、权限与 bootstrap 管理员

### 7.1 认证

- 密码：BCrypt（strength 10）
- 认证：短期 JWT access token（15 分钟，内存保存，`Authorization: Bearer`）+ HttpOnly refresh token
  （Spring Session JDBC，30 天，刷新轮换，登出/改密吊销该用户全部 refresh 会话）
- CSRF：`CookieCsrfTokenRepository.withHttpOnlyFalse()`，前端读 `XSRF-TOKEN` 回填 `X-XSRF-TOKEN`
- 接口权限：`/api/admin/**` 需 `ROLE_ADMIN`

### 7.2 bootstrap 管理员

目的：数据库被删、管理员被误禁、忘记密码时，仍能通过配置文件恢复入口。

```yaml
yilian:
  bootstrap-admin:
    enabled: true
    username: admin
    password: ${YILIAN_BOOTSTRAP_ADMIN_PASSWORD:change-me-now}
    reset-password-on-startup: false
```

启动时由 `ApplicationRunner` 执行，幂等：

| 场景 | 行为 |
|---|---|
| `enabled=false` | 跳过 |
| 用户不存在 | 创建，`role=ADMIN`，密码 BCrypt |
| 用户存在且正常 | 不做任何修改 |
| 用户存在但被禁用 | 重新启用（`enabled=true`） |
| 用户存在但非管理员 | 提升为 ADMIN |
| `reset-password-on-startup=true` | 重置密码、解锁并吊销该用户 refresh 会话 |

约束：

- local 可使用文档化默认密码；prod 必须通过环境变量覆盖，且不得使用默认值或弱口令
- `reset-password-on-startup` 默认 `false`，仅在需要时临时打开，用完关掉
- 日志中不打印密码
- email 字段、`force-change-on-first-login` 不在当前骨架内，留给后续账号切片

## 8. 数据模型（MySQL 8）

```sql
app_user(
  id BIGINT PK AUTO_INCREMENT,
  username VARCHAR(64) UNIQUE,
  password_hash VARCHAR(100),
  role VARCHAR(16),            -- ADMIN / USER
  enabled BIT(1) DEFAULT b'1',
  created_at DATETIME(6),
  updated_at DATETIME(6)
)

refresh 会话使用 Spring Session JDBC 官方的 spring_session / spring_session_attributes 表
```

sentence_banks(
  id BIGINT PK,
  owner_id BIGINT NULL,        -- NULL = 官方题库
  name VARCHAR(128),
  visibility VARCHAR(16),      -- public / private
  version INT DEFAULT 1
)

sentences(
  id VARCHAR(32) PK,
  bank_id BIGINT,
  zh VARCHAR(255),
  ref VARCHAR(512),
  chunks JSON,
  blank JSON,
  tags JSON,
  level VARCHAR(32),
  source VARCHAR(128),
  version INT
)

attempts(
  id CHAR(36) PK,              -- 客户端生成，保证幂等
  user_id BIGINT NULL,
  device_id VARCHAR(64) NULL,
  sentence_id VARCHAR(32),
  mode VARCHAR(16),            -- cloze / free
  answer TEXT,
  score INT,
  result JSON,
  grader VARCHAR(16),          -- local / grammar / model
  latency_ms INT,
  created_at DATETIME
)

reviews(
  id BIGINT PK,
  user_id BIGINT,
  sentence_id VARCHAR(32),
  mode VARCHAR(16),
  state VARCHAR(16),           -- new / learning / mastered
  due DATE,
  streak INT,                  -- V2 换 FSRS 时替换为 stability/difficulty/reps/lapses
  updated_at DATETIME,
  UNIQUE (user_id, sentence_id, mode)
)
```

MySQL 约定：`utf8mb4` / `utf8mb4_0900_ai_ci`；JSON 列在 Hibernate 6 用 `@JdbcTypeCode(SqlTypes.JSON)` 映射。

## 9. 技术栈（已定）

| 层 | 选型 |
|---|---|
| JDK | 21 LTS |
| 后端 | Java 21 + Spring Boot 4.1.x |
| 持久化 | MySQL 8 + Spring Data JPA + Flyway |
| 缓存 | Caffeine 一级 + Redis 二级 |
| 语法 | LanguageTool 内嵌 |
| LLM | Spring AI（Ollama / OpenAI 兼容端点） |
| 前端 | React 19 + TypeScript + Vite |
| 样式 | Tailwind CSS 4 + shadcn/ui（定制 token） |
| 状态 | TanStack Query + 少量本地 state |
| 本地库 | Dexie（IndexedDB） |
| SRS | V1 自实现三档，V2 用 FSRS |
| PWA | vite-plugin-pwa |
| 部署 | 本机 Docker Compose；后续 VPS |

## 10. 功能清单

| 优先级 | 功能 |
|---|---|
| P0 | 注册 / 登录 / 登出、bootstrap 管理员、游客升级 |
| P0 | 语块填空（判定、申诉、提示） |
| P0 | 整句翻译（本地反馈 + 深度批改） |
| P0 | 错题列表、三档复习 |
| P0 | 管理员导入题库（校验、预览、发布） |
| P0 | 基础统计（正确率、错误类型） |
| P1 | 离线队列与同步冲突处理 |
| P1 | 暗色模式 |
| P1 | 题库版本增量拉取 |
| P2 | 语块排序、改错 |
| P2 | 导出 Anki / CSV |
| P2 | 多设备实时同步 |

## 11. 非功能需求

| 项 | 指标 |
|---|---|
| 填空判定 | < 50ms（本地） |
| 整句本地反馈 | < 100ms |
| 深度批改 | P95 ≤ 8s，失败降级 |
| 首屏 | 本地 < 1.5s |
| 离线 | 可拉题库、可填空、可整句（仅本地反馈） |
| 移动端 | iOS 15+ / Android 10+，键盘不遮挡输入 |
| 成本 | 单次深度批改 < ¥0.02，月度上限可配 |
| 安全 | BCrypt、httpOnly Cookie、CSRF、admin 鉴权、导入文件上限 5MB |

## 12. 验收标准

1. 空数据库启动 → Flyway 建表 → 自动生成 bootstrap 管理员 → 可登录（强制改密留到账号切片）
2. 删库重启 → 系统仍可启动并生成管理员，历史数据清空但不报错
3. `reset-password-on-startup=true` 重启 → 新密码生效，旧密码失效
4. 管理员被禁用 → 重启后被重新启用
5. 游客刷 10 题 → 注册 → 10 条记录迁移到账号，本地队列清空
6. 断网完成 20 题填空 → 记录进本地队列 → 联网自动同步，attempts 无重复
7. 同一 `attemptId` 重复提交 → 服务端只记一次
8. 整句提交 → 100ms 内出本地批改 → 8s 内补上深度批改
9. LLM 超时 → 页面保留本地结果并提示，可继续下一题
10. 导入 50 句，其中 3 句 `chunks` 拼接与 `ref` 不符 → 3 行标红并给出原因，47 句入库
11. iOS / Android 各一台真机，键盘弹起时输入框与提交按钮可见
12. 填空提交后刷新页面 → 答题记录与复习状态不丢

## 13. 不做清单

- 社交、排行榜、学习小组
- 语音输入与口语评分
- 原生 App（Android / iOS）
- 多语言界面
- 付费、订阅
- 公开题库市场与用户投稿
- 邮件服务（找回密码走管理员重置）
- SSR / SEO
- 实时协作

## 14. 里程碑

| 里程碑 | 内容 | 出口 |
|---|---|---|
| M1 | 骨架 + 认证 + bootstrap admin + 句库导入 | 能登录、能导入 50 句 |
| M2 | 填空闭环 + 本地判定 + 错题 | 能连续刷 20 题 |
| M3 | 整句 + 服务端批改 + 统计 | 两种模式都能用 |
| M4 | PWA + 移动端 + Compose 部署 | 手机上可用 |

## 15. 待定

- 句库来源与版权：真题仅自用；若公开发布需替换为自建或授权语料
- LLM 供应商与预算：默认 Ollama 本地，云端作为可选
- 产品名「译练」为工作名

## 16. 基础约定

- 邀请码注册，一码一人。
- 管理员查看答题原文必须选原因并写审计；停用账号必须填原因。
- 管理员不能停用/降级自己，不能移除最后一个 ADMIN。
- 五维评分：信息完整 30 / 语法 20 / 搭配用词 20 / 句法重组 20 / 语域简洁 10。
- 练习侧：阶梯提示、改对才过、今日到期、每日目标、今天到此复盘、按考点续练；三题定级（2026-10-24 增补）：首次打开进入、可整体跳过（「先开始，稍后再测」）、设置页可重新定级，只给起点与「10 题后自动校准」，不出现等级标签。
- 设置侧：评分标准（专八 / CATTI / 雅思）、每日目标、通知说明。
- 后台列表统一分页，默认每页 20 条；支持关键字与下拉筛选，筛选变化回到第 1 页。
- 练习页可打开当前题库的题目清单，查看已练/未练，并直接跳题。
- 题目解析随导入录入，练习提交后展示；后台题目编辑可补写解析。

数据与权限：

- `attempts` 原始流水保留 90 天；`user_daily_stats` 长期保留聚合；`admin_audit_log` 保留 1 年。
- `attempts` 增加 `question_version`、`duration_ms`；`users` 增加 `disabled_reason`、`deleted_at`。
- 题库增加 `import_batches` + 不可变 `question_versions`，导入失败整批回滚。当前已落地通用异步上传/导入框架（`upload` 模块；`import_batch` 表、5MB/扩展名/MIME/断点/轮询/失败摘要，类名保留 `ImportBatch*` 以表达具体导入批次语义）；`question_versions` 与 `chunks=ref` 业务校验留到题库业务切片，扩展点见 `ImportRowValidator`。

## 17. 模型配置与用量

目标：换模型、换 Key、调预算都在后台完成，不改代码、不重启。

```sql
llm_providers(
  id, name, kind,              -- openai-compatible / local
  base_url, model,
  api_key_cipher,              -- AES-GCM，主密钥来自环境变量
  temperature, max_tokens, timeout_ms,
  enabled, priority,
  updated_by, updated_at
)

llm_usage(
  id, user_id, provider_id, model, purpose,   -- grade / appeal / test
  prompt_tokens, completion_tokens, cost,
  latency_ms, ok, created_at
)

app_settings(key, value)       -- 月度预算、默认模型、回退顺序
```

规则：

- API Key 只写不读，查询接口只返回掩码；日志与异常信息统一脱敏
- 保存后发布配置变更事件，客户端注册表重建；进行中的请求用旧配置跑完
- 余额分两类：厂商开放接口的自动刷新（如 DeepSeek `/user/balance`），其余显示「本月估算消耗」并允许手动录入
- 预算到 80% 提醒管理员，到 100% 自动降级为本地批改，不做静默扣费
- 测试连接发一条最小请求，限流，不计入用户用量
- 所有配置变更写入 `admin_audit_log`

## 18. 日志与异常

后台分两个独立页面，不混在一起。

### 18.1 操作日志

记录需要追溯的写操作，例如登录、登出、管理端变更、导入与提交。当前由 `@OperationLog` 标注需要审计的接口，AOP 同时输出结构化操作日志并写入 `operation_log`（V3 建表，V6 补审计对象字段）；读接口默认不标注。后台操作日志页面留到管理端切片。

字段：`trace_id, module, action, target_type, target_id, reason, user_id（脱敏）, client_ip, outcome, duration_ms, error_type, request_method, request_path, created_at`；不记录参数、请求体、返回值。`target_type/target_id` 标识被操作对象（如 `user/42`、`sentence/1001`），`reason` 是注解上声明的静态审计依据；三者均可空，未声明时落库为 `NULL`。

### 18.2 异常日志

只记录未预期异常，供管理员定位到模块与堆栈；业务异常、校验失败、404/405/415、越权与 CSRF 不写入。

```sql
error_log(
  id BIGINT PK,
  trace_id VARCHAR(64),
  module VARCHAR(32),          -- auth / content / practice / ai / admin / system
  exception_class VARCHAR(255),
  message VARCHAR(2000),
  stack_trace MEDIUMTEXT,
  request_method VARCHAR(16),
  request_path VARCHAR(512),
  user_id VARCHAR(32) NULL,    -- 脱敏值，不存明文 id
  client_ip VARCHAR(64),
  created_at DATETIME(6)
)
```

规则：

- 客户端只收到 `ApiResponse<T>` 信封与通用文案，堆栈只进后台。
- 按时间分区或按月分表，默认保留 30 天，定时清理，任务可重入。
- 页面支持按时间、模块、关键字（异常类 / 消息 / traceId）筛选，显示操作者与 IP。
- 不记录密码、token、完整译文。

## 19. 缓存

- **L1 Caffeine**：进程内热点，题库元数据、模型目录、系统配置；小容量、短 TTL。
- **L2 Redis**：跨实例共享，限流计数、Prompt 版本、句包；必须带过期时间。refresh 会话不使用 Redis，存 Spring Session JDBC。
- 键名：`yilian:{env}:{module}:{key}:{version}`。
- 写操作先落库再删缓存；Redis 不可用时回源数据库，主链路不阻塞。
- 答题原文、密钥、完整译文不进缓存。
