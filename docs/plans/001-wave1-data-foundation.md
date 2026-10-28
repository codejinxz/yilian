# 计划：001-wave1-data-foundation（波次一·数据层地基）

## 元信息
- 任务：修 AuthFlowIntegrationTest 使全量测试恢复绿（含 T3 实体部分提前）+ 接入覆盖率工具 + 文档登记
- 类型：fix（混合 refactor/chore/docs）
- 通道：full（S0 guard：2000 行 / 30 文件双超限；命中 `**/auth/**` 敏感路径）
- 计划文件：docs/plans/001-wave1-data-foundation.md

## 背景（why）
全量测试 7 Errors 的根因已实测坐实：`ImportBatch` 实体仍映射旧表 `import_batch`，而恢复的 V6 迁移已 `DROP import_batch` 建 `import_batches` → Hibernate validate 报 `missing table [import_batch]` → Spring 上下文加载失败 → `AuthFlowIntegrationTest` 7 用例全部 Error，CI 以 `-Dtest=!AuthFlowIntegrationTest` 排除（ci.yml 注释自证）。**仅修 M7（测试清理 SQL）无法让上下文加载**，必须把切片设计 T3 的实体部分提前到波次一；波次二（T4）专心做 preview→commit 新端点与前端改造。M 清单增补项核对结论：M2/M3/M4/M5/M6 均已在恢复的 V1–V8 内（V4 建 idempotency_record + V5 补 owner_id 复合唯一键、V1 spring_session、V5 分区 p_future、created_at 默认值齐备），仅剩文档登记（M3 后半）与 M7/M8。

## 目标与非目标（what）
- 目标：`mvnw test` 全量 0 Failures / 0 Errors（DROP 重建 yilian_test 后）；旧过渡机制按设计文档 §4.1/§4.7 删除；JaCoCo 与 @vitest/coverage-v8 可出覆盖率报告；DATA-MODEL/GAP 登记。
- 非目标：preview/commit/rollback 新端点（T4，波次二）；banks P1×2（T5）；前端导入改造（T6）；CI 移除排除（波次四）；practice 模块（波次三）；Q1 一句一卡的 V9 迁移（随波次三 T7）。

## 方案说明（≤5 行）
1. M7：AuthFlowIntegrationTest 清理 SQL `app_user`→`users`、`operation_log`→`admin_audit_log`，删 `spring.flyway.out-of-order=true`。
2. T3 提前：`ImportBatch` 重写为 `import_batches` 21 列（状态机 PREVIEWING→…），新建 `ImportBatchRow`，Dao 适配，状态迁移集中实体内并配单测；同时删除旧过渡机制（旧端点/service/recovery/async/DTO/旧测试，均为设计文档已批准删除项）。
3. 覆盖率：pom.xml 加 jacoco（prepare-agent+report 绑 test）；web 加 @vitest/coverage-v8 + `test:coverage` 脚本 + vitest coverage 配置。
4. 文档：DATA-MODEL §2 基础设施表补登记 idempotency_record；GAP 当前状态行同步。
5. M8：DROP 重建本机 `yilian_test`（本地开发/测试库，无生产库；设计文档 §2.2 M8 已预授权）。

## AC 清单（简式一行，映射并入）
- AC-1: GIVEN DROP 重建后的 yilian_test WHEN 运行 AuthFlowIntegrationTest THEN 7 用例全过（Spring 上下文加载成功） → server/src/test/java/.../auth/AuthFlowIntegrationTest.java 既有 7 用例 [AC-1]
- AC-2: GIVEN ImportBatch 实体重写为 import_batches 21 列 WHEN Spring 上下文启动（ddl-auto=validate）THEN 无 missing table 校验错误 → AuthFlowIntegrationTest 上下文加载 [AC-2]（与 AC-1 同证）
- AC-3: GIVEN 新状态机 WHEN 非法迁移（如 PREVIEWED→PREVIEWING、COMMITTED→COMMITTED、ROLLED_BACK→COMMITTED）THEN 抛 IllegalStateException；合法迁移（PREVIEWING→PREVIEWED→COMMITTING→COMMITTED→ROLLED_BACK、→FAILED、→EXPIRED）THEN 成功且字段落位 → 新增 ImportBatchStateTransitionTest，用例名含 [AC-3]
- AC-4: GIVEN 旧过渡机制删除 WHEN 全仓库编译 + 全量测试 THEN 无引用残留、编译通过 → mvnw test 全量 [AC-4]
- AC-5: GIVEN 波次一完成 WHEN DROP 重建测试库后跑全量 THEN Tests run ≥250 / 0 Failures / 0 Errors → evidence capture test [AC-5]
- AC-6: GIVEN JaCoCo 接入 WHEN mvnw test THEN server/target/site/jacoco/jacoco.xml 生成且含覆盖率计数 → evidence capture（构建产物检查命令）[AC-6]
- AC-7: GIVEN @vitest/coverage-v8 接入 WHEN pnpm vitest run --coverage THEN coverage/ 报告生成且既有用例全绿 → web 测试 evidence [AC-7]
- AC-8: GIVEN 文档核对 WHEN 检查 DATA-MODEL §2 与 GAP 当前状态 THEN idempotency_record 已登记、状态行与代码实态一致 [视觉-人工+审查确认] [AC-8]

## 文件级改动清单
| 文件 | 改动 | 说明 |
|------|------|------|
| server/src/test/java/io/github/codejinxz/yilian/auth/AuthFlowIntegrationTest.java | 修改 | M7：清理 SQL 换 users/admin_audit_log，删 out-of-order |
| server/src/main/java/io/github/codejinxz/yilian/upload/entity/ImportBatch.java | 重写 | import_batches 21 列 + 状态机方法 markPreviewed/commit/rollback/fail/expire |
| server/src/main/java/io/github/codejinxz/yilian/upload/entity/ImportBatchStatus.java | 重写 | PREVIEWING/PREVIEWED/COMMITTING/COMMITTED/FAILED/ROLLED_BACK/EXPIRED |
| server/src/main/java/io/github/codejinxz/yilian/upload/entity/ImportBatchRow.java | 新增 | §5.7 行级校验结果实体 |
| server/src/main/java/io/github/codejinxz/yilian/upload/dao/ImportBatchDao.java | 修改 | 适配新实体 |
| server/src/main/java/io/github/codejinxz/yilian/upload/dao/jpa/ImportBatchJpaRepository.java、JpaImportBatchDao.java | 修改 | 适配新实体 |
| server/src/test/java/io/github/codejinxz/yilian/upload/entity/ImportBatchStateTransitionTest.java | 新增 | 状态机单测 [AC-3] |
| server/src/main/java/io/github/codejinxz/yilian/upload/bootstrap/ImportBatchRecoveryRunner.java | 删除 | §4.7 恢复钩子删除 |
| server/src/main/java/io/github/codejinxz/yilian/upload/config/ImportAsyncConfig.java | 删除 | §4.7 异步线程池删除 |
| server/src/main/java/io/github/codejinxz/yilian/upload/controller/ImportBatchController.java | 删除 | §4.1 旧过渡端点（新端点波次二 T4 建） |
| server/src/main/java/io/github/codejinxz/yilian/upload/service/ImportBatchService.java、impl/ImportBatchServiceImpl.java | 删除 | 旧上传→PROCESSING→resume 机制 |
| server/src/main/java/io/github/codejinxz/yilian/upload/dto/ImportBatchAccepted/ErrorsResponse/StatusResponse/RowError/UploadCommand.java | 删除 | 旧过渡 DTO |
| server/src/test/java/io/github/codejinxz/yilian/upload/controller/ImportBatchControllerTest.java、service/impl/ImportBatchServiceImplTest.java、support/InMemoryImportBatchDao.java | 删除 | 旧机制测试（波次二随新服务重建） |
| server/pom.xml | 修改 | jacoco-maven-plugin |
| web/package.json、web/pnpm-lock.yaml | 修改 | @vitest/coverage-v8 devDep + test:coverage 脚本 |
| web/vitest.config.ts | 修改 | coverage provider v8 |
| docs/DATA-MODEL.md、docs/GAP-ANALYSIS.md | 修改 | §2 登记 idempotency_record；当前状态行 |
| server/src/main/resources/application.yml | 修改 | 删 `yilian.upload.recovery.enabled`（如存在） |
| upload/support/*、validation/*、api/ImportExceptionHandler.java、config/ImportWebConfig.java | 可能微调 | 保留下层设施，随编译适配 |

## 预期产物清单
| 产物路径 | 类别 |
|---------|------|
| docs/plans/001-wave1-data-foundation.md | must-create |
| server/src/main/java/io/github/codejinxz/yilian/upload/entity/ImportBatchRow.java | must-create |
| server/src/test/java/io/github/codejinxz/yilian/upload/entity/ImportBatchStateTransitionTest.java | must-create |
| docs/retro/friction-wave1.md | must-create |
| server/pom.xml、web/package.json、web/vitest.config.ts、docs/DATA-MODEL.md、docs/GAP-ANALYSIS.md、server/src/test/java/io/github/codejinxz/yilian/auth/AuthFlowIntegrationTest.java、server/src/main/java/io/github/codejinxz/yilian/upload/entity/ImportBatch.java、ImportBatchStatus.java、upload/dao/ImportBatchDao.java、upload/dao/jpa/ImportBatchJpaRepository.java、JpaImportBatchDao.java、server/src/main/resources/application.yml | may-modify |
| upload/bootstrap/ImportBatchRecoveryRunner.java、upload/config/ImportAsyncConfig.java、upload/controller/ImportBatchController.java、upload/service/ImportBatchService.java、upload/service/impl/ImportBatchServiceImpl.java、upload/dto/ImportBatchAccepted.java、ImportBatchErrorsResponse.java、ImportBatchStatusResponse.java、ImportRowError.java、ImportUploadCommand.java、upload/controller/ImportBatchControllerTest.java（test）、upload/service/impl/ImportBatchServiceImplTest.java（test）、upload/support/InMemoryImportBatchDao.java（test） | may-delete |

## 风险与回滚
- 风险 1：实体列型与 DDL 不一致 → validate 失败。缓解：逐列对照 DATA-MODEL §5.6/§5.7 与 V6/V8 DDL；上下文加载即证明。
- 风险 2：删除旧机制破坏未知引用。缓解：已 grep 核实引用封闭于 upload 模块 + 3 个旧测试文件。
- 风险 3：coverage 依赖安装网络失败。缓解：失败则记录 docs/retro 并上报，不伪造报告。
- 回滚：各任务独立 commit，revert 即恢复；不触碰已执行迁移（append-only）。

## 任务分解（每任务 = 一个 commit 粒度）
1. [ ] W1-1 M7 测试修复（AC-1）
2. [ ] W1-2 T3 实体重写 + 状态机测试 + 删旧机制（AC-2/3/4）
3. [ ] W1-3 覆盖率工具（AC-6/7）
4. [ ] W1-4 文档登记（AC-8）
5. [ ] W1-5 全量验证 + evidence 四项（AC-5）

## 批准记录
- 批准通道：unattended（无人值守自主会话；任务书为用户预先批准的波次一范围，原因与决策点将在波次汇报与 PR 监督摘要披露）
- 批准模式：std
- 批准人 / 时间：gates.mjs 落状态留痕

## 退回记录
| 日期 | 从哪退回 | 原因 | 改了什么 |
|------|---------|------|---------|
