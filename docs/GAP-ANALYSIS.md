# 待办与优先级

## 当前状态

- **022 AI 配置升级完成（2026-11-05，计划 [docs/plans/022-ai-config-and-markdown.md](plans/022-ai-config-and-markdown.md)）**：CC Switch 式多 provider 目录 + 答疑 Markdown 渲染。①provider 目录（`app_settings` 键 `llm.providers`/`llm.active`，激活唯一）+ 管理端五端点 `GET/POST /api/admin/llm/providers`、`PUT/DELETE /api/admin/llm/providers/{id}`、`PUT .../{id}/activate`（审计 LLM_CONFIG_UPDATE；id 限 `[a-z0-9-]+`、激活中不可删、协议白名单 fail-closed）；**apiKey 仍不入库**——按 id 走 `YILIAN_LLM_KEY_<大写ID>`（连字符→下划线），UI 只显 hasKey；目录空且 `YILIAN_LLM_*` 齐备时回退 legacy 单 provider（读路径兼容 017/021）。②`LlmChatClient` 三协议统一接口：anthropic-messages（021 网关轨，022 改官方 anthropic-java SDK 直调——桩实测 Spring AI `getText()` 会把 thinking 块当正文返回 text 块被丢，手写按块类型提取兜底）、openai-chat（SpringAiLlmClient 收编更名 `OpenAiChatLlmClient`）、openai-responses（新，手写 `java.net.http`——spring-ai 2.0.0 无 Responses 支持，侦察确认）；`LlmClientRegistry` 按激活条目 protocol 装配 + 目录指纹运行时缓存（变更即失效无需重启）；maxTokens 三协议同源 768→**2048**（思考模型 thinking 计入 max_tokens，主人实测 32 全被吃掉）。③ModelsPage 升级 provider 目录管理（列表卡协议/hasKey 徽标与激活高亮、新增/编辑抽屉、ZCode 网关预置模板、删除确认、legacy 形态卡），AI 功能清单按激活 provider 联动；`llm/config` 视图扩 `providerId`/`protocol`/`credentialsComplete`（anthropic 轨 key 可空的齐备性口径）。④AskPanel 回答 react-markdown 10.1.0 + remark-gfm 4.0.1 渲染（GFM 表格/代码块/列表/粗体；不引 rehype-raw，原始 HTML 按纯文本展示默认安全；用户问句仍纯文本）；**白名单外新依赖披露**：react-markdown（MIT，纯渲染无传递 runtime 依赖问题，替代方案=手写解析器/DOMPurify+dangerouslySetInnerHTML 均否——后者引入 XSS 面）与 remark-gfm（MIT，GFM 表格/删除线扩展），AGENTS §3.3 登记待主人追认。质量门禁：后端 `mvnw test` 710 run / 0 Failures / 0 Errors（基线 678，+32）、`fmt:check` 0 不合规；前端 `vitest run` 126/126（基线 118，+8）、`tsc --noEmit` 0 错误、`vite build` 成功、`biome check` 本次改动文件 0 问题。
- **021 LLM provider 抽象与 ZCode 本地网关适配完成（2026-11-05，计划 [docs/plans/021-llm-gateway.md](plans/021-llm-gateway.md)）**：答疑机器人双轨接入——`YILIAN_LLM_PROVIDER`（`openai` 缺省 | `anthropic`）选择 `LlmChatClient` 实现：openai 轨沿用 `SpringAiLlmClient`（017 语义不变，022 收编更名 `OpenAiChatLlmClient`）；anthropic 轨新增 `AnthropicMessagesLlmClient`（spring-ai-anthropic 2.0.0，BOM 已含，官方 anthropic-java-core 底座仅依赖 okhttp/micrometer 无新传递面，starter 自动配置随 `spring.ai.model.chat=none` 一并关闭），服务主人的 ZCode 本地网关（GLM 套餐额度，Anthropic Messages 协议）。拼接口径：`YILIAN_LLM_BASE_URL` 填到网关前缀、客户端拼 `/v1/messages`；网关本机无鉴权故 anthropic 轨凭证口径=仅 base-url（apiKey 可空，非空以 x-api-key 头携带）；网关 429 错误体为非标准形状（{"error":"ZCode 网关:所有账号尝试均失败",attempts:[...]}），识别按 HTTP 状态码（SDK 状态码驱动抛 RateLimitException/InternalServerException），429/5xx 退避 30s 重试一次（冷却特性；最坏 15s+30s+15s≈60s），仍败沿 `TutorServiceImpl` 50000「答疑暂时不可用」降级。护栏三件套（maxTokens 768/超时 15s/免责常驻）两轨同源（022 起 maxTokens 提至 2048 并扩三协议）。主用途切换：022 起首选 provider 目录（管理端 UI 激活切换）；legacy 路径=env 设 `YILIAN_LLM_PROVIDER=anthropic` + `YILIAN_LLM_BASE_URL=http://127.0.0.1:47860/gateway/zcode`（+可选 `YILIAN_LLM_MODEL=glm-5.3`）重启即走网关；删掉 provider 或设回 openai 恢复智谱直连。质量门禁：见 021 PR 文档。
- **017 题目答疑 AI 机器人完成（2026-11-04，计划 [docs/plans/017-ai-tutor.md](plans/017-ai-tutor.md)）**：被动题目答疑（PRD §5.4），非「AI 外教」；LLM 接入走 Spring AI OpenAI 兼容协议（spring-ai-bom 2.0.0 对齐 Boot 4.1，starter 自动配置关闭、TutorLlmConfiguration 按凭证惰性装配），主人智谱套餐经 `YILIAN_LLM_BASE_URL`/`YILIAN_LLM_API_KEY`（`YILIAN_LLM_MODEL` 缺省 glm-4.7）即接入。三态：未配 key=功能不存在（答疑端点 40400、前端无入口）；配 key 未开=40304「答疑未开放」（注：计划原文写 40301，但 40301 已是 CSRF 稳定契约，按「发布后不改号不复用」新增 40304）；开启=可用。管理员配置 `GET/PUT /api/admin/llm/config`（审计 LLM_CONFIG_UPDATE；enabled 存 `app_settings` 键 `llm.tutor`（V5 已建表，fail-closed 解析）；base-url/model/apiKey 环境变量只读、PUT 传非空值 40000 拒绝——AGENTS §3.4 密钥不入库，计划「apiKey 只写不回读」落地为「apiKey 拒收并提示走环境变量」，GET 回 hasKey）。`POST /api/practice/{sentenceId}/ask` 复用 016 accessibleForPractice 口径（PracticeService 新增 requirePracticeableSentence），限流 5 次/分钟（账号），超时 15s，异常降级 50000「答疑暂时不可用」；prompt 只喂题目中文/考点/本人最近批注，不喂参考译文，问题定界 + 500 字上限。`llm_usage` 表未建（DATA-MODEL §5.18 M3 预留「不建」），用量记结构化日志，落库待批（API §10.4）。前端练习页批注栏「问一问」入口 + AskPanel（免责常驻、会话内历史、40304/40400 后隐藏入口；复习页重练走同一练习页批注栏，不另设入口）。质量门禁：后端 `mvnw test` 649 run / 0 Failures / 0 Errors（基线 621，+28）、`fmt:check` 0 不合规、JaCoCo 门禁通过；前端 `vitest run` 99/99（基线 94，+5）、`tsc --noEmit` 0 错误、`vite build` 成功、`biome check` 本次改动文件 0 问题（src/mocks 存量 warning 不变）。
- **018 二轮评审修复 + 原型小项完成（2026-11-04，计划 [docs/plans/018-review2-and-prototype-fixes.md](plans/018-review2-and-prototype-fixes.md)；本行 019 补登）**：复评 70 条经独立核实（[docs/analysis/review-verify-2.md](analysis/review-verify-2.md)，P1×2 全真、P2 真缺陷 27 条、误报 2 条为 ULID 字母表与 utf8mb4 判断）。P1×2：登录时序侧信道（用户名不存在时执行假 BCrypt matches 对齐耗时）与 prod 加固旁路（判定扩为 profile=prod OR 显式 env 标记，别名部署不再静默用默认口令）。P2 择要：`reset-password-on-startup` 一次性持久化标记（用后重启不覆盖已改口令）、`review_card` @Version 乐观锁（并发 lost update 拒绝）、分片上传超时会话定时清扫（并入 PartitionMaintenanceJob）。原型小项：管理员重置用户密码端点（`POST /api/admin/users/{id}/reset-password`，一次性临时口令只随响应返回一次）、删除账号端点（`DELETE /api/admin/users/{id}` 软删+吊销会话+审计、不能删自己）、整句批改要点命中 chips（前端对照原型）、复习顺延口径统一为 3 天。质量门禁：后端 `mvnw test` 621 run / 0 Failures / 0 Errors（+24，含 JaCoCo 门禁）；前端 `vitest run` 94/94（+6）；API.md 新端点已挂 §10.1 矩阵。
- **016 外部评审消化完成（2026-11-04，计划 [docs/plans/016-external-review.md](plans/016-external-review.md)；本行 019 补登）**：两步消化——先独立核实再修复。核实（[docs/analysis/review-verify.md](analysis/review-verify.md)）：112 条评审中 P0×2+P1×9 全部真缺陷、零误报（含 API 实证与 SQL 实证）。P0×2：练习整链题库可见性校验补齐（候选 SQL `NOT EXISTS` 下推 + 交卷/回看/答疑复用 `requirePracticeableSentence` 校验，练习史豁免为本人可见口径）；测试库口令明文收敛（application-test.yml env 三层分层=本机默认回落文档化 dev 值/开发者 env 覆盖/CI 必须注入随机 + TestDatabases 幂等建库 + 干净检出等价验证）。P1×9：新题召回 `NOT EXISTS` 下推（LIMIT 20 作过滤后窗口）、reviews 建卡 `REQUIRES_NEW`+输家读回复用、`external_key` 批次隔离（`gen_<batchId8>_%04d`）、停用/降级吊销 refresh 会话（真实 HTTP 测试零残留）、rollback 事务化（与 commit 同构）、`spring_session` BLOB 孤儿清理（V11 append-only）、CSV RFC4180 子集解析（引号/逗号/换行/BOM）、集成测试建库策略（9 类单类可跑）、登录→拉题→提交→批改 E2E 主链路集成测试（DoD 缺口）。P2 择要 10 项（prod 反代默认收紧、回收归属校验、编辑不隐式发布、page 上限、会话纪元护栏、reset 开关一次性、Redis 超时、40904 翻译、JaCoCo 门禁 LINE≥85/BRANCH≥65、fmt:check 转阻断），其余登记 [docs/analysis/review-p2-backlog.md](analysis/review-p2-backlog.md)。质量门禁：后端 `mvnw test` 597 run / 0 Failures / 0 Errors（534 基线，+63，含 JaCoCo 门禁）；前端 `vitest run` 88/88。

- **防护缺口批次完成（2026-11-03，计划 [docs/plans/013-protection-gaps.md](plans/013-protection-gaps.md)，analysis-protection-gaps Top10 之 #4/#5/#6/#8/#9）**：
  - **幂等清理（#8）**：`PartitionMaintenanceJob` 并入 `@Scheduled(fixedDelay=30min)` 清理任务，`DELETE ... WHERE expires_at <= now LIMIT 500` 分批循环（单轮 ≤200 批、失败仅记日志），过期口径与惰性清理的含边界判定一致；`JdbcIdempotencyService` 链路不变。
  - **缓存接线（#5）+ 三防护（#6）**：`CacheConfig` 改显式缓存名清单（`content:bank:detail:v1` / `content:bank:list:v1`，版本段进缓存名，禁动态建缓存）；落点为匿名公开读 `GET /api/banks` 列表与详情（登录/管理端不缓存，理由见 `BankMetadataCache` 类注释）；击穿=`@Cacheable(sync=true)`、穿透=`Absent` 负缓存条目（60s 短 TTL）、雪崩=`JitteredTtlExpiry` ±10% TTL 抖动（基准 10min）；写后删缓存经 `BankMetadataCache.evictWrittenBanks` 事务提交后执行（详情逐键、列表整体清），失效路径覆盖题库 CRUD/删除/批量状态/批量移动/单题状态/行内编辑/导入/导入回收。
  - **安全响应头（#9）**：`SecurityConfig` 增加 nosniff、`X-Frame-Options: DENY`、`Referrer-Policy: strict-origin-when-cross-origin`、HSTS（仅 HTTPS 请求写入，max-age 1 年含子域）、CSP（prod 从紧：自托管 + Google Fonts 两域 `fonts.googleapis.com`/`fonts.gstatic.com`；local 放宽内联脚本供 swagger-ui；来源盘点记录于 `SecurityConfig` 常量注释）。
  - **最后管理员锁（#4）**：`AppUserJpaRepository.lockActiveAdminIds` 以 `@Lock(PESSIMISTIC_WRITE)` 锁定启用管理员行（ORDER BY id 防死锁）后计数，`adminSetRole`/`adminSetStatus` 两处接入；新增真实 MySQL 两线程并发互降回归测试（仅一人成功、败者 40900）。
  - 质量门禁：`mvnw test` 525 run / 0 Failures / 0 Errors（基线 490，+35）；`fmt:check` 0 不合规。前端无改动；无新增端点，API.md §10.1 不动。

- **波次一完成（2026-10-29）**：V1–V8 迁移链已由 commit `857e644` 从构建产物恢复（下文 2026-10-28「迁移全部缺失」的更正已过时，以本条为准）。`AuthFlowIntegrationTest` 恢复 7/7 全绿——根因链：`ImportBatch` 实体仍映射已 DROP 的旧 `import_batch` 表（叠加 `sentence_versions.schema_version` 实体 `int` 与 DDL/契约 `SMALLINT` 错配，两者都是被首个 validate 错误掩盖的存量问题）。已按 preview→commit 契约重写 `ImportBatch`/`ImportBatchRow` 实体与状态机（切片 T3 实体部分提前），删除旧 upload→PROCESSING→resume 过渡机制（旧端点/service/recovery/async/DTO 及其测试）；全量 `mvnw test` 245 run / 0 Failures / 0 Errors。覆盖率工具接入：后端 JaCoCo（`mvn test` 产出 `target/site/jacoco/`）、前端 @vitest/coverage-v8（`pnpm test:coverage`，既有 50/50 不变）。CI 的 `-Dtest=!AuthFlowIntegrationTest` 排除按计划随波次四移除；preview→commit 新端点与前端导入改造为波次二（T4/T6），banks P1×2 为 T5。

截至 2026-10-24 实测收口：

- 已完成：需求、设计、可点击原型；工程基线收口——后端 auth / system / upload / content / common 模块（ApiResponse 信封、双 token JWT、@OperationLog 审计切面、@RateLimit Redis 滑动窗口、幂等、error_log、存储抽象）与 V1–V8 迁移；前端全部页面与路由注册（AppShell 壳层布局，`/` 即今日启动台，登录后落 `/today`；管理页独立注册）
- 本轮修复：导入审计 module 归一（`module="import"` 落库归一为 `content`，原 `"upload"` 不在 DATA-MODEL §7 枚举导致误落 system）+ actor 透传正向测试
- 质量门禁实测：后端 `mvnw test` 224 run / 0 Failures / 7 Errors / 0 Skipped；前端 `tsc --noEmit` 0 错误、`biome check` 0 错误（32 条 warning 级遗留，集中在 src/mocks，不阻塞）、`vitest run` 21/21（grading 14 + auth-flow 7）、`vite build` 成功；google-java-format（aosp）全量格式化后 `fmt:check` 0 不合规
- 已知存量：upload→imports preview→commit 重构进行到一半——V6 迁移已 DROP 旧 `import_batch` 并重建为 `import_batches`（DATA-MODEL §5.6/§5.7），但 `upload/entity/ImportBatch.java` 仍 `@Table(name="import_batch")`，Hibernate `ddl-auto=validate` 启动失败，`AuthFlowIntegrationTest` 上下文无法加载，即上述 7 Errors 的唯一根因；imports 切片完成后自然消除，非本轮引入
- 基线可比性：surefire 历史报告中的 imports 模块测试（如 ImportBatchServiceImplTest）连同实现都不在当前工作树中，「259+」等历史数字不可比
- git：仓库已初始化（main 分支、0 提交），首次提交由用户自行执行
- FSRS：契约与字段已预留（PRD / API / DATA-MODEL、后端 V5/V8 迁移与 UserSettings、前端 schemas/mocks），实现按计划在 V2（M2.1 优化器后置），无缺失
- 下一步：练习主链路切片（题库→拉题→填空→判分，V8__practice_core 已建表）；另有 upload 模块按新契约替换的存量收尾（见「已知存量」）
- 下一步（2026-10-28 修订）：切片执行稿见 [docs/练习主链路_切片设计.md](练习主链路_切片设计.md)。**重要更正**：基于 commit 33e625e 复核，仓库实际不存在任何 Flyway 迁移文件（上文「V1–V8 迁移已建表」与「V8__practice_core 已建表」均为失实记载，7 Errors 的根因即迁移缺失）；执行稿改为从零重建 V1–V5 迁移链，并给出 practice 全端点设计、upload 收尾与前端切换方案，以执行稿为准
- 本轮已完成（原型对齐，2026-10-24）：以原型为模板全量对齐前端——去掉强制登录（游客可逛全部主页面，仅 /admin/** 保留门）、新增三题定级（纯本地、可跳过、不贴等级标签）、练习/复习/统计/设置逐屏还原（双栏批注栏、会话条、改对才过、本轮总结弹窗、导出 JSON 等）、管理端九页签（概览/用户/题目/题库/出题/模型/操作日志/异常/运维，mock 演示层）；后端新增 `GET /api/banks/**` 匿名只读端点（PUBLIC_OR_USER，257 run / 0 Failures / 7 Errors 存量不变）；新增前端本地练习引擎（本地判分/排程/统计/降载，游客与登录用户过渡期共用，`lib/api.ts` 的 LOCAL_ROUTE_GROUPS 开关一处切回服务端）。游客真实后端全链路已浏览器实测（匿名拉公开题库 → 定级 → 练习 → 错题入队 → 统计聚合）；管理端在 `VITE_USE_MOCK=true` 演示模式下验收
- 原型覆盖交互与页面结构；标注「服务端」的事项在原型中只能示意

- **管理端 UI 并回主壳层（项目主人 2026-10-31 指示，下个计划执行）**：现 /admin 为独立路由 + AdminLayout 自带框架（router.tsx「管理页不并入壳层」），项目主人要求改为与 今日/练习/复习/统计 一致——主导航内加「管理」项，点击后内容在同一壳层内容区显示（AdminLayout 框架拆除、九页签逻辑保留、AdminGate 登录门保留）；同时管理端真实数据接口（/api/admin/** 日志检索、题库管理、用户管理等）未开发，真实模式下页签报 404 属已知缺口（当前以 VITE_USE_MOCK=true 演示），接口开发随管理端切片。

## P0：上线前必须补

### 账号与安全

| 事项 | 要求 |
|---|---|
| 会话吊销 | refresh 会话存 Spring Session JDBC；登出/改密/停用均按用户吊销全部 refresh 会话；access token 采用 15 分钟短 TTL，不做永久黑名单 |
| 限流 | 登录按 IP + 账号双维度（10 次/分钟，2026-11-04 与代码对齐，API §4.2）；邀请码与深度批改分别限流；`/api/banks**` 匿名读 120 次/分钟（IP）；`/api/admin/fsrs/optimize` 5 次/分钟（账号）；429 带 Retry-After |
| 幂等 | `attemptId`、邀请码、预算扣减以数据库唯一约束兜底 |
| 默认凭据 | 部署环境仍用默认密码时，启动即失败 |
| 密码存储 | 当前 BCrypt（strength 10）；Argon2id 作为后续升级项；公网部署的管理员启用 2FA |

### 数据与恢复

| 事项 | 要求 |
|---|---|
| 备份 | 每日全量 + binlog；异地保存；RPO ≤ 15 分钟，RTO ≤ 4 小时 |
| 恢复演练 | 每季度隔离恢复并跑一次登录 + 判分，记录实际耗时 |
| 保留策略 | 答题原文 90 天、聚合统计长期、审计 1 年；分批游标删除，可断点续跑 |
| 导出与删除 | 用户可导出 JSON；删号软删 30 天后匿名化 |

### 学习闭环

| 事项 | 要求 |
|---|---|
| 三题定级 | 三题自适应，不报等级，只给起点与「N 题后自动校准」；可跳过 |
| 积压熔断 | 到期超过阈值进入降载，只推 5 道；顺延有上限，超限转重学 |
| 判分可重放 | 每条答题绑定 normalizer / rubric / prompt / model 版本；改判写新记录 |
| 时区日界 | 全 UTC 存储，按用户 IANA 时区计算今日与连续天数 |
| 首败处理 | 答错时说明「还差几处」，并提供「看答案，记为学习中」出口 |

### 内容治理

| 事项 | 要求 |
|---|---|
| 句源与分级 | 记录来源、许可、难度；无来源只能存草稿 |
| 坏题探测 | 统计通过率与区分度；样本足够且通过率过低时暂停出题 |
| 多解库 | 每个语块可维护多个可接受译法；申诉通过后回写 |
| 生命周期 | 题库与题目五态（草稿/发布/停用/回收站/归档）；版本不可变，回滚生成新版本 |
| 去重与批次 | 精确指纹硬拦、近似相似度提示；导入批次可整体回滚 |

### 模型与 Prompt

| 事项 | 要求 |
|---|---|
| 模型目录 | 记录模型快照与上下线时间；生产不跟随 `latest` |
| 任务路由 | 按任务配置主模型、回退链、参数与灰度 |
| Prompt 注册表 | 不可变版本 + 生产/候选标签；发布前跑回归集 |
| 调用账本 | 记录 trace、Prompt 版本、模型快照、token、成本、耗时 |
| 注入防护 | 用户答案定界为数据，输出强校验，分数钳制 |
| 黄金集 | 50–100 条分层回归集，换模型或改 Prompt 前必跑 |

### 基础设施

| 事项 | 要求 |
|---|---|
| 缓存分层 | Caffeine 一级 + Redis 二级；键带环境与版本；写库后删缓存；Redis 故障时回源 |
| 日志页面 | 操作日志与异常日志两个独立后台页面；记录操作者与 IP；支持时间、模块、关键字筛选；异常页可看堆栈与 traceId |
| 日志保留 | 操作日志 1 年；异常日志 30 天，按时间分区清理 |

### 合规

- 隐私政策与用户协议
- 年龄声明；未成年人需监护人同意
- 题库来源与 license 字段
- 模型服务与数据出境说明

## 上线前硬门槛

1. 空库一键起、迁移可重放、备份恢复实测（RPO 24h / RTO 2h 以内）
2. 默认凭据无法启动；TLS、安全头、会话吊销、限流都有自动化测试；无 critical/high 漏洞
3. 并发幂等压测通过；断网 20 题同步零重复
4. 模型全挂演练：主链路不阻塞、降级可见、预算不穿透
5. 关键告警可触发；发版失败一分钟内换回上一版本；双真机冒烟通过

## P1：之后再做

- 题目浏览器的高级筛选、保存视图、批量撤销
- 工单收件箱与申诉结果回流
- 质量看板与人工抽检
- 灰度发布、影子流量、人审工作台
- 进步周报、语篇产出、考试模式、回译
- PWA 离线队列、更新提示、iOS 存储持久化
- 指标、告警、CI/CD

## 不做

- K8s / 微服务 / 多副本 / 分库分表
- 自研认证、加密、IDP、Prompt IDE、观测平台
- AI 批量造题自动发布
- 自动合并重复题
- 排行榜、惩罚性打卡、抽奖、AI 外教
- 开放 UGC 题库与社区
- 自建支付
- 无限题库与全科扩张

## 部署前必须确定

bootstrap 管理员策略：

| 环境 | 行为 |
|---|---|
| 本机 | 允许默认值，便于恢复 |
| 部署 | 默认值或未设置时启动失败 |
| 忘记密码 | 临时开启 `reset-password-on-startup=true`，用完关闭 |

## 原型与实现的边界

| 能力 | 原型 | 生产实现 |
|---|---|---|
| 判分 | 本地规则可跑 | 规则 + 可选模型 |
| 会话/限流/幂等 | 界面示意 | 服务端 |
| 备份/恢复 | 演练结果模拟 | 脚本 + 定期演练 |
| Prompt 版本/评测 | 版本展示 | 注册表 + 回归门禁 |
| PWA 离线 | 横幅模拟 | Service Worker + 队列 |
| 模型生成题库 | 固定样例 | 真实调用 + 计费 |
