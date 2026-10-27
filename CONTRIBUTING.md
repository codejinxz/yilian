# 贡献指南

## 分支与提交

- trunk-based：只保留 `main`；实验用短生命周期分支，合并后立即删除。
- 提交信息遵循 Conventional Commits：`feat:` / `fix:` / `docs:` / `refactor:` / `test:` / `chore:`，标题一行，正文可选。
- 不提交生成物、密钥、IDE 配置；`server/config/application-local.yml` 与 `.zcode/` 已被忽略。

## 开发流程

1. 先读 `AGENTS.md`（项目契约）与 `docs/PRD.md`（对应验收标准）。
2. 改行为先改文档（PRD / RESEARCH / GAP-ANALYSIS 至少一处）。
3. 小步提交，单次改动能独立回滚。

## 本地验证（提交前必须全绿）

```powershell
.\server\mvnw.cmd test                 # 后端全量测试
pnpm --dir web install
pnpm --dir web build                   # 含 tsc --noEmit
pnpm --dir web test                    # vitest
pnpm --dir web biome check .
```

命令细节、常见错误与 IDE 配置见 `docs/BOOTSTRAP.md`。

## 代码规范

- 后端：阿里风格分层（controller → service 接口 → service.impl → dao）、google-java-format、中文 Javadoc 带 `@param`/`@return`。
- 前端：Biome、中文 TSDoc 带 `@param`/`@returns`。
- 新增依赖必须在提交说明中给出理由与替代方案。

## 报告问题

安全类问题见 `SECURITY.md`；其他问题请附复现步骤、期望/实际行为与环境信息。
