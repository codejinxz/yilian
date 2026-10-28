# PR：002-mybatis-wiring MyBatis 正确接线与首个复杂查询落地

> 对应提交 `63460a9` / `8023bca`（基线 `f3fd34e` → HEAD `8023bca`）。用户直接指示的任务：MyBatis starter 声明后完全休眠（0 @Mapper / 0 MapperScan / 无配置），按 AGENTS §3.1 契约接线并落地首个「复杂查询」消费者。

## 监督摘要（solo ≤10 行）
- **运行方式**：**无人值守**（原因：自主会话执行用户直接指示；批准通道：unattended；profile=solo）——批准与裁决留痕 `.workflow/state/main.json`。
- **计划 vs 实际偏差**：① S4 隔离审查 blocker=0，7 条非 blocker 中 3 条主动修复（测试 visibility 参数化、limit 守卫、旧两步查询死代码清理），修复在 S5 前落地、未触发强制重审，由人裁量；② 集成测试清理需临时摘 V7 不可变触发器并重建（`sentence_versions` append-only 触发器 + 双向 RESTRICT 外键导致无法常规清表），测试库自洽性保持；③ 发现并保留一个存量语义事实：练习候选不过滤题库可见性（PRIVATE 发布题库的句子参与候选，可见性由调用方约束）——与被替换的 JPA 实现逐字一致，未擅自改语义，**建议作为独立议题评估**。
- **新增/变更 AC**：无（R4 未触发，ac-hash 6f60721d 全程有效）。

## 验收标准（AC 勾选）
- [x] AC-1: 候选查询语义对拍（可见性/排序/动态过滤/limit/快照缺失丢弃）→ PracticeCandidateQueryDaoIntegrationTest 3 个 ac1_* 用例 [AC-1]
- [x] AC-2: MapperScan + XML + 驼峰/record 映射在 Boot 4.1 全量上下文可用 → 同上集成测试上下文加载与断言同证 [AC-2]
- [x] AC-3: ContentQueryServiceImplTest 22 用例语义保持（候选测试改为 JOIN 行映射断言，等价替换）[AC-3]
- [x] AC-4: 全量 0 Failures / 0 Errors → mvnw test 248 run / 0 / 0（245 + 3 新增）[AC-4]
- [x] AC-5: 前端零改动，三件套基线保持（evidence build/typecheck exit 0）[AC-5]

## 覆盖率
- 增量覆盖率：新增 Dao/映射器由集成测试 3 用例 + 服务单测 1 用例覆盖；JaCoCo 工具已接入（`mvn test` 产出 target/site/jacoco）。
- 全量测试：0 失败（后端 248/0/0，前端 50/50 未动）。

## 审查
- S4 隔离子代理审查（`review-63460a95`）：**blocker = 0**，AC 8/8 相关项 pass（002 的 5 条）。
- 非 blocker 遗留：7 条，3 修 4 留痕，逐条见计划 002 退回记录（私有题库可见性语义、limit 边界差、测试口令与既有先例同源、AGENTS.md 指针行为前波已裁决项）。

## 安全
- 依赖变更：无（mybatis-spring-boot-starter 4.1.0 为既有声明，本次仅接线启用；无 lockfile 变更）。
- 密钥扫描：通过（diff 无新增密钥；集成测试明文 root/123456 与 test-only JWT 为 yilian_test/local 测试值，与 AuthFlowIntegrationTest 同源先例，不复用于真实环境）。
- 触发原因：不涉及敏感路径变更；XML 动态 SQL 全部 `#{}` 参数化、无 `${}` 注入面。

## 批准
- S2 批准：std（通道：unattended，原因：自主会话执行用户直接指示；条件：无；AC hash：6f60721d）
- 高风险格确认人：不涉及（无迁移改动、无删数据、无权限变更）

## 豁免留痕（如有）
| 豁免项 | 批准人 | 理由 |
|--------|--------|------|
| 无 | — | — |

## 更正（2026-10-29，独立分析复核后）
- 计划 002 与本 PR 原表述「首个消费者/有真实生产消费端（练习拉题）」**不准确**：`ContentQueryService.findPracticeCandidates` 当前无任何 Controller 调用方（原两步 JPA 实现同样无调用方，系练习切片预埋）。准确表述：**首个 MyBatis 承载的复杂查询（端到端接线 + 语义对拍测试），其 HTTP 消费者随波次三 practice/next 落地**。`@MapperScan`/配置/集成测试的接线价值不受影响。
- 独立盘点同时发现两处与本案相关的遗留死代码（非本任务引入）：`ContentAdminService`（无实现无调用）与 `common/concurrent` 异步设施（全仓无业务消费者）——已登记 docs/retro/analysis-fe-be-20261029.md，处置待项目主人定夺。
