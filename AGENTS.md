# AGENTS.md · 译练项目契约

> 任何会话、贡献者、AI agent 开始工作前先读这一份。
> `docs/PRD.md` 约束「做什么」，本文件约束「怎么做、什么不能做」。

## 1. 项目一句话

面向中文母语者的**中译英刻意练习** PWA：一屏一句，语块填空或整句翻译，即时批改，错题复习。

## 2. 不可协商的产品规则

1. **一屏一句**。不做仪表盘式首页；「今日」只做启动台，一次点击进第一题。
2. **本地优先**。能本地判定的不调模型；模型只做增强，失败必须降级。
3. **批改可解释、可申诉**。每条批注要有考点标签；「不认同」可排除该条错题。
4. **改对才过**。答错必须重打参考译文，或显式选择「看答案，记为学习中」。
5. **错题不删除**。答题记录与审计日志只增不改；删除一律软删。
6. **原文默认不可见**。管理员查看答题原文必须选原因并写审计；不批量导出。
7. **权限在服务端**。前端隐藏不算权限；`/api/admin/**` 必须服务端鉴权。
8. **不贴等级标签**。定级只说「先从哪档开始，N 题后自动校准」。
9. **所有自动化可见、可撤销、有理由**。包括降载、顺延、批量操作。
10. **不做**：排行榜、惩罚性打卡、抽奖、AI 外教、无限 AI 题库、开放 UGC。

## 3. 技术约定

- 命名一律以 [docs/NAMING.md](docs/NAMING.md) 为准：`io.github.codejinxz.yilian`、`yilian-server`、`@yilian/web`。
- 数据库 MySQL 8：全 UTC 存储，展示用用户 IANA 时区；日界用 `LocalDate.now(ZoneId)`。
- 迁移用 Flyway；禁止手改生产库；迁移前自动快照。
- 判分 append-only：`attempts` 绑定 normalizer / rubric / prompt / model 版本；改判写新记录。
- 密钥只进环境变量或 secret，不入库、不进 git、不出现在日志；唯一例外是 local 的 `application-local.yml`，只允许本机开发值且禁止复用。
- 缓存分层：**Caffeine 一级 + Redis 二级**；Redis 同时承担限流计数；refresh 会话存 Spring Session JDBC。
- 不引入消息队列、K8s、多副本；需要时先写决策记录。
- Spring Boot 4.1.x（若锁 3.5，用 3.5.16 并安排升级）；Java 21。
- 幂等以数据库唯一约束为准，应用层只做友好返回。

### 3.1 持久层

- **Spring Data JPA 负责写操作与单表 CRUD；MyBatis 负责复杂查询、统计与报表。**
- 同一张表不维护两套映射：JPA 实体放模块内 `entity`，MyBatis 只返回 DTO / VO，不另建 DO。
- 两者共用 Spring 数据源与事务管理器；跨表写操作必须在同一个 `@Transactional` 内。
- JSON 列：JPA 用 `@JdbcTypeCode(SqlTypes.JSON)`，MyBatis 用 TypeHandler。
- 批量导入等场景允许用 MyBatis 写入，但必须在一个事务内、可回滚，并有测试。
- 禁止用 MyBatis 绕过实体校验改写单条业务数据。
- Dao 接口放模块 `dao` 包，命名以 `Dao` 结尾（如 `SentenceDao`）；业务代码只依赖 Dao 接口，不直接使用 MyBatis Mapper 或 Spring Data Repository 的具体实现。

### 3.2 代码风格

- 注释与 Javadoc / TSDoc 一律使用中文，说明「为什么」而不是复述代码；标识符、错误码、日志字段名保持英文。公共类型/方法使用 `/** ... */` 块注释，Java 方法必须写 `@param` / `@return`，前端导出函数写 `@param` / `@returns`；不要用 `//` 代替 API 文档。生成脚本、第三方文件、许可证头与工具指令（如 biome-ignore 规则名）豁免。
- Java：`google-java-format` + Checkstyle；提交前格式化，CI 校验。
- 前端：Biome，提交前 `biome check --write`。
- 命名与包结构以 [docs/NAMING.md](docs/NAMING.md) 为准。
- 分层固定为 `controller` → `service`（接口） → `service.impl`（实现类） → `dao`，逐层单向依赖：
  - Controller 只做参数校验、调用 Service 接口、组装 `ApiResponse`；**不得直接调用 `dao` / `repository`**。
  - Service 接口与实现分离：接口放模块 `service` 包，实现类放 `service.impl` 包，类名以 `Impl` 结尾（如 `AuthServiceImpl`）。
  - 跨模块只依赖对方的 `service` 接口，不引用 `service.impl`、`dao`、`entity`；跨模块传递数据用 DTO / VO。
  - Dao 接口的包位置与命名见 §3.1。
  - `common` 是横切基础设施，不强制 `service`/`service.impl` 双层，也不强制 `Impl` 后缀；模块内（auth/system/upload 等）必须遵守接口+`service.impl/*Impl`。

### 3.3 依赖

预置白名单：

- Lombok（编译期，不进运行时）
- MapStruct（DTO 映射）
- Flyway（数据库迁移）
- Caffeine（本地缓存）
- springdoc-openapi（接口文档）
- Spring Boot AspectJ starter（仅用于 `@OperationLog` 操作日志切面）
- Spring AI（模型接入）
- Spring Data JPA、MyBatis 官方 starter
- Spring Security OAuth2 JOSE（仅用于 JWT 签发/校验，不引入资源服务器）

白名单外新增依赖，必须说明：解决什么问题、替代方案、维护状态、体积与漏洞影响。

### 3.4 环境与配置

- `local`：本机开发，使用本机 MySQL/Redis；允许文档化默认管理员密码。
- `prod`：默认密码或缺失时启动失败；密钥只来自环境变量或 secret。
- bootstrap 管理员：`local` 允许默认值；`prod` 未设置或仍为 `change-me-now` 时启动失败；忘记密码用一次性的 `reset-password-on-startup=true`，用完关闭。
- 配置优先级：环境变量 > `application-{profile}.yml` > `application.yml`；local 的数据库/JWT/bootstrap 本机值统一放 `server/config/application-local.yml`（通过 `spring.config.additional-location` 加载，不在 classpath），不使用仓库根 `.env`；prod 只从真实环境变量/secret 读取，不读 `.env`。local 的明文凭据仅限本机开发，禁止复用。
- 本地启动：确认本机 MySQL/Redis 已运行，再 `cd server && .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.arguments=--spring.config.additional-location=optional:file:./config/"`。

### 3.5 前端

- 数据请求与缓存：TanStack Query。
- 全局 UI 状态：Zustand，只放主题、设置、会话；业务缓存交给 Query。
- 路由：TanStack Router；样式：Tailwind + shadcn/ui；校验：Zod。
- 请求失败要有重试与降级提示，不出现全屏 spinner 卡死。

### 3.6 日志与异常

- 结构化日志（logstash JSON，字段含 `traceId`、脱敏 `userId`、`clientIp`、`module`、`action`、`durationMs`）仅在 prod 启用；local 控制台保持人类可读格式。
- 统一响应为 `common/api/ApiResponse<T>` + `ApiCode`：成功用 `success(...)`，失败用 `fail(...)`；成功 `code=0`、`message="success"`；失败返回对应业务码与中文文案，`data` 默认 `null`（参数校验失败等场景可携带明细）。
- HTTP 状态码仍保留，表示成功/失败类别（2xx / 4xx / 5xx）；错误同样返回 `ApiResponse` 信封，不再使用 `ProblemDetail`。
- 需要审计的写接口标注 `@OperationLog(module, action)`，由 AOP 统一记录操作者、客户端 IP、结果与耗时；读接口默认不标，且切面不读取参数/请求体。
- 未知异常写入 `error_log`；业务异常、参数校验、404/405/415、越权与 CSRF 属于已知结果，不写异常表。
- 未知异常统一 500 与通用文案，不把堆栈返回客户端。
- 异常信息（模块、异常类、堆栈、traceId）写入 `error_log`，管理员后台可检索定位。
- `error_log` 按月分表或按时间分区，默认保留 30 天，定时清理；清理任务可重入。
- 日志与异常表不得写入密码、token、完整译文。

### 3.7 安全边界

- `/api/admin/**` 服务端要求 `ROLE_ADMIN`；前端隐藏不算权限。
- 公网部署前：管理员启用 2FA，并限制管理入口来源（独立子域或 IP 白名单）。
- 认证采用双 token：
  - access token 为 10–15 分钟短期 JWT，只存前端内存，通过 `Authorization: Bearer` 发送。
  - refresh token 为不透明值，存 Spring Session JDBC，HttpOnly + Secure + SameSite=Strict + `Path=/api/auth`；刷新时轮换 session id，登出/改密时吊销该用户全部 refresh 会话。
  - 不使用 access token 黑名单；access token 过期后由 refresh 换新，refresh 失效才要求重新登录。
  - `YILIAN_JWT_SECRET` 在 prod 只来自环境变量或 secret；local 可用 `application-local.yml` 中明确的 `local-dev-only` 开发值，禁止复用到其他环境。
- 会话吊销、限流、幂等按 [docs/GAP-ANALYSIS.md](docs/GAP-ANALYSIS.md) 的 P0 执行。

### 3.8 缓存

- **L1 Caffeine**：进程内热点，题库元数据、模型目录、系统配置；小容量、短 TTL。
- **L2 Redis**：跨实例共享，限流计数、Prompt 版本、句包；必须带过期时间。refresh 会话当前存 Spring Session JDBC。
- 键名统一 `yilian:{env}:{module}:{key}:{version}`，版本变化即失效。
- 写操作先落库再删缓存；禁止只写缓存不写库。
- Redis 不可用时回源数据库，主链路不阻塞；所有缓存必须可重建。
- 答题原文、密钥、完整译文不进缓存。

### 3.9 日志页面

- 后台分两个独立页面：**操作日志**与**异常日志**。
- 操作日志记录管理员动作，含操作者、IP、对象、原因，保留 1 年。
- 异常日志记录运行期异常，含模块、异常类、堆栈、traceId、操作者、IP，默认保留 30 天。
- 两页都支持按时间、模块、关键字筛选；不做处理状态流转。

## 4. 目录

```
yilian/
├─ AGENTS.md          # 本文件
├─ README.md
├─ docs/              # 需求、设计、缺口、命名
├─ prototype/         # 原型，仅作参照，不直接当生产代码
├─ server/            # Spring Boot 后端（yilian-server）
└─ web/               # React PWA 前端（@yilian/web）
```

## 5. 工作流

流程与门禁以 [docs/WORKFLOW-PROMPTS.md](docs/WORKFLOW-PROMPTS.md) 为准：需求 → 设计 → 开发 → 测试 → 发布 → 复盘，**每阶段人工确认后才进入下一阶段**。

1. 先读 `docs/PRD.md`，再读与任务相关的设计决策/待办章节。
2. **改行为先改文档**：PRD、RESEARCH、GAP-ANALYSIS 至少更新一处。
3. 写代码前说明影响面：涉及哪些表、哪些接口、是否破坏兼容。
4. 小步提交；单次改动能独立回滚。
5. 完成后更新验收清单与变更记录。

### 分支与提交

- **trunk-based**：只保留 `main`；需要实验时用短生命周期分支，合并后立即删除。
- 提交信息用 **Conventional Commits**：`feat:` / `fix:` / `docs:` / `refactor:` / `test:` / `chore:`，标题一行，正文可选。
- 不提交生成物、密钥、IDE 配置；提交前跑格式化与 lint。

## 6. 完成定义（DoD）

一个功能算完成，必须同时满足：

- [ ] 符合 PRD 中对应的验收标准
- [ ] 判分、SRS、权限、幂等有单元测试
- [ ] 登录 → 答题 → 批改主链路有集成测试
- [ ] 前端关键交互有手工验收清单；能自动化的用 Playwright
- [ ] 格式化与 lint 通过
- [ ] 无硬删除、无明文密钥、无越权路径
- [ ] 移动端键盘弹起不遮挡输入
- [ ] 失败路径有降级与提示
- [ ] 文档同步更新
- [ ] 演示数据可重置

## 7. 明确禁止

- 硬删题目、级联删答题记录
- 自动合并近似重复题目
- 静默 `skipWaiting`、静默丢弃离线答案
- 用 JWT + 黑名单冒充会话吊销
- 原地 UPDATE 覆盖历史分数或 Prompt
- 在没实测慢查询前上全文索引、向量库
- 为「专业感」引入 DDD 分层、事件溯源、服务网格
- 未经确认扩大范围（加模块、加依赖、加页面）

## 8. 决策索引

| 主题 | 文档 |
|---|---|
| 六阶段流程与提示词 | [docs/WORKFLOW-PROMPTS.md](docs/WORKFLOW-PROMPTS.md) |
| 产品范围与验收 | [docs/PRD.md](docs/PRD.md) |
| 设计决策 | [docs/RESEARCH.md](docs/RESEARCH.md) |
| 待办与优先级 | [docs/GAP-ANALYSIS.md](docs/GAP-ANALYSIS.md) |
| 命名与包结构 | [docs/NAMING.md](docs/NAMING.md) |
| 教学法原始调研 | [docs/中译英刻意练习_调研结论.md](docs/中译英刻意练习_调研结论.md) |

## 9. 给 AI 会话的指令

- 不要自作主张扩大范围；不确定先问。
- 按任务所处阶段执行：设计阶段不写业务代码，开发阶段按切片实现并附测试。
- 不要重构与当前任务无关的代码。
- 新增依赖必须说明理由与替代方案。
- 保持「稿纸与朱笔」的视觉语言：暖白纸底、衬线正文、单一朱红强调、克制的动效。
- 涉及删除、权限、密钥、迁移时，先停下来确认。

## 10. 已定决策

1. **bootstrap 管理员**：`local` 允许默认值，`prod` fail-fast；忘记密码用一次性重置开关。
2. **第一个切片**：登录 → 拉题 → 填空 → 判分。
