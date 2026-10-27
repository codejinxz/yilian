# 译练（Yilian）

> 面向中文母语者的**中译英刻意练习** PWA：一屏一句，语块填空，本地判分，离线可用。

## 特性

**真实可用**

- 三题定级：首次打开即可测，可跳过，不贴等级标签（「先从哪档开始，10 题后自动校准」）
- 语块填空：归一化判分、逐词下划线框、三级提示阶梯、改对才过、看答案记为学习中
- 三档复习：答错自动入队，简化 SRS 排程，积压降载（到期 >8 只推 5 句，可顺延）
- 统计：连续天数、累计句数、平均分、14 天趋势、错误考点 Top5
- 离线：断网续练、草稿自动保存、联网自动补交离线答卷
- 账号：邀请码注册、登录、设置跨设备同步；题库匿名拉取

**演示态**（mock 数据，代码带 `TODO(mock)` 标记，服务端接口落地后删除）

- 管理端单页九页签：概览 / 用户 / 题目 / 题库（含导入）/ 出题 / 模型 / 操作日志 / 异常 / 运维
- 模型复核按钮（本地占位，不调模型）

## 快速开始

**路径 A · 演示模式**（不需要后端与数据库，全部演示数据）

```powershell
pnpm --dir web install
$env:VITE_USE_MOCK="true"; pnpm --dir web dev
```

**路径 B · 完整启动**（需要本机 MySQL 8 + Redis；前置工具与首次配置见 [docs/BOOTSTRAP.md](docs/BOOTSTRAP.md)）

```powershell
# 后端（必须显式 local profile，凭据在 server/config/application-local.yml）
cd server
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.arguments=--spring.config.additional-location=optional:file:./config/"

# 前端（另开终端）
pnpm --dir web install
pnpm --dir web dev
```

- 后端 <http://localhost:8080> · 前端 <http://localhost:5173>
- local 管理员默认 `admin / change-me-now`（仅本机；prod 必须环境变量覆盖）
- 游客不登录即可练习；数据存本机 IndexedDB，不上传

## 使用说明

- **游客与账号**：游客数据只存本机；登录后主题/目标/时区等设置跨设备同步（练习记录的账号迁移接口 guest-upgrade 在路线图上）。
- **离线行为**：页面已打开时断网可继续作答（本地队列 + 本地判分），联网后自动补交；整页离线冷启动暂不支持。
- **管理端**：顶栏「管理」进入（仅管理员）；当前为演示数据模式，服务端接口属后续切片。
- **提交/下一句**快捷键：`Ctrl/⌘ + Enter`；模式切换：`1 / 2`。

## 架构概览

```
web/src/
├─ app/            # TanStack 路由（壳层布局、定级门、管理门）
├─ features/       # 按领域分目录的页面（today/practice/review/stats/settings/admin/...）
├─ lib/
│  ├─ api.ts       # 请求分发：mock → 本地引擎 → 真实后端（三层适配，开关一处切换）
│  ├─ local-engine/ # 本地练习引擎（判分/SRS/统计，IndexedDB 持久化）
│  └─ schemas/     # Zod 契约（与 docs/API.md 对齐）
└─ mocks/          # 演示数据层（VITE_USE_MOCK 控制）

server/src/main/java/io/github/codejinxz/yilian/
├─ auth/ me/ content/ upload/ system/  # 业务模块（controller → service → dao）
└─ common/          # ApiResponse、双 token 安全、审计 AOP、限流、幂等、error_log
```

数据访问三层适配是核心设计：mock（演示）/ 本地引擎（游客与离线）/ 服务端（#8 落地后切换）
共用同一套页面与 Zod 契约，切换只改 `lib/api.ts` 的 `LOCAL_ROUTE_GROUPS` 开关。

## 测试与质量

```powershell
.\server\mvnw.cmd test        # 257 run / 0 Failures（7 个存量 Error 见已知限制）
pnpm --dir web test           # vitest 50/50
pnpm --dir web build          # 含 tsc --noEmit
pnpm --dir web biome check .
```

CI（`.github/workflows/ci.yml`）在 push 时自动执行后端测试与前端构建。

## 已知限制

- 服务端练习/统计接口（#8）未实现：登录用户的练习数据也在本机，跨设备同步待 guest-upgrade
- 管理端为演示态；深度批改（M3）与 FSRS 排程（V2）未实现
- 整句模式当前只有本地要点覆盖粗判，深度批改未接入
- 后端测试 7 个存量 Error：upload→preview/commit 重构半成品，详见 [docs/GAP-ANALYSIS.md](docs/GAP-ANALYSIS.md)

## FAQ

**为什么游客不用注册？** 产品原则「本地优先」：游客数据只存本机，注册是为跨设备与长期留存，注册时将通过 guest-upgrade 把本机记录迁入账号（开发中）。

**为什么练习数据不上传？** 同上——当前服务端练习接口未实现；本地引擎保证离线可用与低延迟，接口落地后由架构预留的三层适配开关统一切换。

**定级为什么没有等级标签？** 产品红线：只给起点与「N 题后自动校准」，避免标签焦虑。

**JSON 日志在哪里？** 仅 prod 输出结构化 JSON（供采集器解析）；local 控制台是人类可读格式。

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。安全类问题见 [SECURITY.md](SECURITY.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 项目契约：规范、完成定义、禁止事项（先读） |
| [docs/PRD.md](docs/PRD.md) | 产品需求与验收标准 |
| [docs/API.md](docs/API.md) | 接口契约 + §10 实现状态矩阵 |
| [docs/DATA-MODEL.md](docs/DATA-MODEL.md) | 数据模型与迁移约定 |
| [docs/RESEARCH.md](docs/RESEARCH.md) | 设计决策（教学法/架构取舍） |
| [docs/GAP-ANALYSIS.md](docs/GAP-ANALYSIS.md) | 待办与优先级、当前状态 |
| [docs/BOOTSTRAP.md](docs/BOOTSTRAP.md) | 新机从零启动手册 |
| [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) | 验收与手工检查清单 |
| [docs/NAMING.md](docs/NAMING.md) | 命名与工程结构 |
| [docs/WORKFLOW-PROMPTS.md](docs/WORKFLOW-PROMPTS.md) | 六阶段流程与门禁 |
| [docs/中译英刻意练习_调研结论.md](docs/中译英刻意练习_调研结论.md) | 教学法原始调研 |

## 许可证

[MIT](LICENSE)
