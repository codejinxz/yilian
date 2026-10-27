# 译练（Yilian）

面向中文母语者的**中译英刻意练习**工具。核心循环：中文题干 → 语块填空或整句翻译 → 即时批改 → 错题复习。

当前阶段：**需求、设计、原型完成；工程基线已收口（登录 / JWT 双 token / 统一响应 / 审计与限流骨架 + 前端全部页面与路由就绪，质量门禁双端实测通过）；业务切片「练习主链路」尚未开始。**

## 目录

```
yilian/
├─ AGENTS.md                  # 项目契约（先读这一份）
├─ README.md
├─ docs/
│  ├─ PRD.md                 # 产品需求文档 v1.0
│  ├─ WORKFLOW-PROMPTS.md    # 六阶段流程与提示词规范
│  ├─ RESEARCH.md            # 设计决策
│  ├─ GAP-ANALYSIS.md        # 待办与优先级
│  ├─ NAMING.md              # 命名与工程结构
│  ├─ BOOTSTRAP.md           # 本地启动与验收
│  ├─ API.md                 # 接口契约（含 §10 实现状态矩阵）
│  ├─ DATA-MODEL.md          # 数据模型与迁移约定
│  ├─ ACCEPTANCE.md          # 验收与手工检查清单
│  └─ 中译英刻意练习_调研结论.md
├─ server/                   # Spring Boot 4 后端
├─ web/                      # React 19 PWA 前端
└─ prototype/
   └─ index.html             # 可点击原型（单文件）
```

## 文档

- [项目契约](AGENTS.md)：后续开发必须遵守的规则、完成定义、禁止事项
- [六阶段流程](docs/WORKFLOW-PROMPTS.md)：总控与各阶段提示词、人工确认门禁
- [PRD](docs/PRD.md)：定位、范围、练习模式、数据模型、验收标准
- [设计决策](docs/RESEARCH.md)：练习、复习、内容、模型与权限的取舍
- [待办与优先级](docs/GAP-ANALYSIS.md)：上线前必须补、之后再做、不做
- [命名与工程结构](docs/NAMING.md)：`io.github.codejinxz.yilian`、`yilian-server`、`@yilian/web`
- [接口契约](docs/API.md)：端点契约与实现状态矩阵（§10）
- [数据模型](docs/DATA-MODEL.md)：表结构、迁移约定与已定决策
- [验收清单](docs/ACCEPTANCE.md)：DoD、手工检查项与变更记录

## 原型

双击 `prototype/index.html` 即可打开，不需要起服务。

覆盖页面：今日启动台、练习（填空/整句）、复习、统计、管理（概览/用户/题目/题库/出题/模型/操作日志/异常/运维）、设置、登录、三题定级。

说明：

- 全部为演示数据，刷新后重置
- 语音朗读用浏览器 `speechSynthesis`，音标为示例数据
- 模型生成、备份演练、判分重放等为模拟，不调用真实接口
- 首次打开会进入三题定级，可点「先开始，稍后再测」跳过

## 计划技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21 + Spring Boot 4.1.x + MySQL 8 |
| 鉴权 | Spring Security + JWT access token + HttpOnly refresh 会话 |
| 缓存 | Caffeine 一级 + Redis 二级 |
| 语法 | LanguageTool 内嵌 |
| 模型 | Spring AI（OpenAI 兼容 / Ollama） |
| 前端 | React 19 + TypeScript + Vite |
| 样式 | Tailwind CSS 4 + shadcn/ui |
| 本地库 | Dexie（IndexedDB） |
| 部署 | 本机 Docker Compose，后续 VPS |

## 下一步

1. 练习主链路切片（题库 → 拉题 → 填空 → 判分；`V8__practice_core` 已建表）
2. upload→imports preview→commit 重构收尾，消除 7 个存量 Error（见 [docs/GAP-ANALYSIS.md](docs/GAP-ANALYSIS.md)「当前状态」）
3. 补安全负向测试（沿用原计划；登录/刷新限流骨架已落地）

## 本地开发

脚手架启动、验证命令与常见错误见 [docs/BOOTSTRAP.md](docs/BOOTSTRAP.md)。
