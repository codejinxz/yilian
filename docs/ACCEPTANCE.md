# 译练 · V1 验收与手工检查清单

> 用途：汇总启动方式、PRD §12 验收标准、DoD 与需要人工确认的项。
> 状态以最终实现为准；本文在 M1/M2 实现完成后更新为实测结果。

## 1. 启动

```powershell
# 后端（本机 MySQL/Redis 已运行）
cd server
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.arguments=--spring.config.additional-location=optional:file:./config/"

# 前端
pnpm --dir web install
pnpm --dir web dev
```

- 后端：<http://localhost:8080>
- 前端：<http://localhost:5173>
- 健康检查：`GET /actuator/health`
- 管理端：local 默认 `admin / change-me-now`（仅本机；prod 必须环境变量覆盖）

## 2. PRD §12 验收标准对照

| # | 验收标准 | 目标 | 验证方式 | 状态 |
|---|---|---|---|---|
| 1 | 空数据库启动 → Flyway 建表 → bootstrap 管理员 → 可登录 → 强制改密 | M1 | 清空 `yilian` 后启动；登录；`passwordChanged=false` 时跳改密 | 待实现/待验证 |
| 2 | 删库重启 → 仍可启动并生成管理员，历史数据清空但不报错 | M1 | 重建 `yilian`；启动；登录 | 待验证 |
| 3 | `reset-password-on-startup=true` 重启 → 新密码生效，旧密码失效 | M1 | 临时打开开关；旧密码 401；新密码 200 | 待验证 |
| 4 | 管理员被禁用 → 重启后被重新启用 | M1 | 手动 `enabled=0`；重启；登录 | 待验证 |
| 5 | 游客刷 10 题 → 注册 → 10 条记录迁移，本地队列清空 | M2 | 前端 mock/本地队列；注册后 `guest-upgrade`；服务端只记一次 | 待实现 |
| 6 | 断网 20 题填空 → 本地队列 → 联网同步无重复 | M2 | 浏览器 DevTools offline；恢复；检查 `attempts.id` | 待实现 |
| 7 | 同一 `attemptId` 重复提交 → 只记一次 | M2 | 连续两次 POST；第二次 200 + `replayed=true`；DB 1 行 | 待验证 |
| 8 | 整句提交 → 100ms 内本地批改 → 8s 内深度批改 | M3 | M3 实现；M2 只做本地/占位不伪造 | 推后 M3 |
| 9 | LLM 超时 → 保留本地结果并提示，可继续 | M3 | M3 | 推后 M3 |
| 10 | 导入 50 句，3 句 chunks 拼接与 ref 不符 → 3 行标红，47 句入库 | M1 | 调 preview；检查逐行原因；commit；47 条 published | 待验证 |
| 11 | iOS/Android 真机键盘不遮挡输入/提交 | M1/M2 | 真机手工验收 | 待手工 |
| 12 | 填空提交后刷新 → 答题记录与复习状态不丢 | M2 | 提交后刷新；`GET attempt`/`GET reviews` 仍存在 | 待验证 |
| 13 | 游客不登录 → 匿名拉公开题库 → 本地判分练习 → 记录留本机 | 本轮 | 真实后端 + 本地引擎；断网显示缓存或空态 | 已实测（浏览器冒烟：定级→练习→错题入队→统计） |
| 14 | 首次打开进入三题定级 → 可整体跳过 → 起点文案不出现等级标签 | 本轮 | 清本地存储首开；分别验证跳过与完成 | 已实测（起点文案 + 重新定级入口） |
| 15 | 管理端九页签按原型还原（mock 演示数据） | 本轮 | 对照原型逐页签核对交互 | 已实现（mock 模式验收；真实模式管理端点属后续切片） |

## 3. DoD 检查

- [ ] 判分、FSRS、权限、幂等有单元测试
- [ ] 登录 → 拉题 → 填空 → 判分有集成测试
- [x] `pnpm --dir web build` 通过（2026-10-24 实测）
- [x] `pnpm --dir web biome check .` 通过（2026-10-24 实测；32 条 warning 级遗留，集中在 src/mocks，不阻塞）
- [ ] `./mvnw test` 全绿（2026-10-24 实测：224 run / 0 Failures / 7 Errors / 0 Skipped；7 个 Error 为 upload→imports 半成品存量，见 §5）
- [x] `mvn fmt:check` 通过（2026-10-24 实测；google-java-format aosp 全量格式化后 0 不合规）
- [ ] `docker compose up -d mysql redis`（或本机 MySQL/Redis）后 local profile 启动
- [ ] Flyway 建表成功；bootstrap 管理员可登录
- [ ] 同一 `attemptId` 重复提交只记一次
- [ ] `docs/openapi.yaml` 已导出，与 API.md/DATA-MODEL.md 一致
- [ ] 无硬删、无明文密钥、无越权路径
- [ ] 移动端键盘不遮挡输入
- [ ] 文档同步（DATA-MODEL/API/GAP-ANALYSIS/README）

## 4. 需人工确认

1. **FSRS**：默认权重 + 用户 `desiredRetention` 的排程是否符合预期；M2.1 优化器暂不交付。
2. **导入**：JSON/CSV 各一份 50 行样例；3 行错误定位是否可读。
3. **移动端**：iOS Safari + Android Chrome 各一台，键盘弹起时输入框/提交按钮可见。
4. **视觉**：今日/练习/批改/复习四页与「稿纸与朱笔」一致；不做仪表盘。
5. **权限**：普通用户直接访问 `/api/admin/**` 返回 403；前端隐藏不算权限。
6. **原文查看**：管理员查看答题原文必须选原因并写审计；不能批量导出。
7. **引导管理员**：local 默认密码、prod fail-fast、reset 开关。
8. **演示数据可重置**：导入/练习/复习数据可清空后重建。

## 5. 当前已知边界

- M3：整句深度批改、LLM 复核/申诉裁决、统计聚合、模型/Prompt/用量后台。
- M2.1：FSRS 参数优化器；M2 只用默认/已启用权重。
- 过渡：当前 `upload` 上传/轮询接口将替换为 preview→commit；旧接口不纳入 OpenAPI。
- 存量：upload→imports preview→commit 重构进行到一半——V6 迁移已 DROP 旧 `import_batch` 并重建为 `import_batches`（DATA-MODEL §5.6/§5.7），但 `upload/entity/ImportBatch.java` 仍 `@Table(name="import_batch")`，Hibernate `ddl-auto=validate` 启动失败，`AuthFlowIntegrationTest` 上下文无法加载，即 `./mvnw test` 当前 7 个 Error 的唯一根因；imports 切片完成后自然消除，非本轮引入。
- `attempts` 90 天保留与 append-only 冲突，V1 先不清理（见 DATA-MODEL D-011）。

## 6. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-24 | 初稿：启动、PRD §12、DoD、手工项 |
| 2026-10-24 | 收口：前端路由全量注册与壳层布局、测试修复至前端全绿（21/21）、后端 0 Failures（7 存量 Error）、双端评审通过、google-java-format 全量格式化 |
