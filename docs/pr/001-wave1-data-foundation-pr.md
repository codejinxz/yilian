# PR：001-wave1-data-foundation 波次一·数据层地基

> 波次范围：修 AuthFlowIntegrationTest 使全量测试恢复绿（含切片 T3 实体部分提前）、删除旧 upload 过渡机制、接入覆盖率工具、文档登记。对应提交 `35359aa` / `4e61c74` / `2f257d8` / `ed4f9db` / `222ac9a` / `ed3703e`（基线 `0d42cf3` → HEAD `ed3703e`；S4 重审绑定 `ed4f9db`，其后两笔为 PR 文档与 javadoc 纯文书笔）。

## 监督摘要（solo ≤10 行）
- **运行方式**：**无人值守**（原因：自主会话执行用户预先批准的波次一任务书，无真人在回路；批准通道：unattended；ask 模式未触发，profile=solo）——S2 批准与全部裁决已落 `.workflow/state/main.json` 留痕，可 revert。
- **计划 vs 实际偏差**：① 7 Errors 根因实测为「ImportBatch 实体映射已 DROP 的 import_batch 表」（ci.yml 注释与切片设计 §2.1 修正均证实），故切片 T3 的实体部分提前到波次一执行（波内顺序调整，非扩范围）；② 迁移链核对结论：M2–M6 增补项已在恢复的 V1–V8 内（V4+V5 含 idempotency owner 修复），仅剩文档登记；③ 实测还挖出被首个 validate 错误掩盖的存量错配 `sentence_versions.schema_version`（实体 int vs 契约/DDL SMALLINT），已随本波修复；④ AC-5 写的「≥250」是计划期错误估算，实测 245（−18 旧用例/+6 新用例），任务书硬要求 0F/0E 已满足，已在计划退回记录调和。
- **新增/变更 AC**：无（R4 未触发，ac-hash 0de5baed 全程有效）。

## 验收标准（AC 勾选）
- [x] AC-1: AuthFlowIntegrationTest 7 用例全绿（上下文加载成功）→ server/src/test/java/io/github/codejinxz/yilian/auth/AuthFlowIntegrationTest.java [AC-1]
- [x] AC-2: ImportBatch 对齐 import_batches 21 列，validate 无 missing table → 同上上下文加载 + 21 个 @Column [AC-2]
- [x] AC-3: 状态机合法迁移字段落位 / 非法迁移拒绝 → ImportBatchStateTransitionTest 6 个 ac3_* 用例 [AC-3]
- [x] AC-4: 旧过渡机制删除、无悬空引用 → 全量编译 + 测试通过 [AC-4]
- [x] AC-5: 全量 0 Failures / 0 Errors → mvnw test 245 run / 0 / 0（≥250 估算已调和，见退回记录 major-2）[AC-5]
- [x] AC-6: JaCoCo 报告生成 → server/target/site/jacoco/jacoco.xml（class 覆盖 89.9%）[AC-6]
- [x] AC-7: vitest coverage 报告生成且既有用例全绿 → web/coverage/ + 50/50 [AC-7]
- [x] AC-8: idempotency_record 登记与 GAP 状态同步 → DATA-MODEL §2 / §1.2、GAP 当前状态 [AC-8]

## 覆盖率
- 增量覆盖率：S3 门禁工具本期为接入轮（无工具前无基线可比）；首测基线——后端 JaCoCo class 89.9% / instruction ≈71%（`mvnw test` → target/site/jacoco/jacoco.xml），前端 v8 lines 28.7% / branches 73.5%（含大量未测 UI 页面，首次记录非回归）。CI 门禁工作流适配待用户安排（涉及保护路径 .github/**）。
- 全量测试：0 失败（后端 245/0/0，前端 50/50）。

## 审查
- S4 隔离子代理审查：**两轮**（初轮 2f257d8c：1 blocker → 核实为早于本波的 `0d42cf3` kit 指针行，任务书预授权，放行披露；minor-5/7 修复后新 HEAD 全量重审）→ 终轮 `ed4f9db6`：**blocker = 0**，8/8 AC pass。
- 非 blocker 遗留：5 条（旧上传 HTTP 用例随预声明删除、覆盖空窗波次二 T4 回补、AC-5 阈值调和、lockfile 引号重排放大 diff、AGENTS.md 指针行来源披露）——全部已在计划 001 退回记录逐条留痕。

## 安全
- 依赖变更：有（新增 devDep @vitest/coverage-v8@^3.2.7，vitest 官方配套；传递依赖经隔离审查逐包核对无可疑混入）。`pnpm audit` 因本机 registry 无 audit 端点不可用（代理亦同）——**环境限制，未得出漏洞结论**，建议 CI 侧补跑 audit。
- 密钥扫描：通过（`git diff 0d42cf3..HEAD` 无私钥头/AKIA/generic token 命中；测试内既有 `test-admin-password` 等为 local 测试值）。
- 触发原因：依赖变更 + 命中 `**/auth/**` 敏感路径（AuthFlowIntegrationTest）→ 全流程通道。

## 批准
- S2 批准：std（通道：unattended，原因：自主会话无人可问，任务书为用户预先批准的波次一范围；条件：无；AC hash：0de5baed）
- 高风险格确认人：不涉及（无迁移改动、无删数据、无权限变更；本地 yilian/yilian_test 库 DROP 重建为切片设计 M8 预授权项，已执行）

## 豁免留痕（如有）
| 豁免项 | 批准人 | 理由 |
|--------|--------|------|
| 无 | — | — |

## 视觉材料（如触发过视觉 AC）
未触发。
