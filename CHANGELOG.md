# 变更日志

本文件记录面向使用者的显著变更。各文档内部的细则变更见对应文档的变更记录章节。

## [0.2.0] - 2026-10-31

### 后端（yilian-server，波次二/三）

- 导入收尾：preview → commit/rollback 状态机（行级错误预览、指纹去重、banks 分页 P1 修复）
- 练习主链路（#8 波次三）：`POST /practice/attempts`（norm-1/rubric-1 判分、answer_hash 幂等、40903 冲突、SRS 排程 + review_logs）、next / wrong-questions / reviews / overload / postpone / reset / today 查询端点、appeal / dispute（dispute 即时排除错题与统计）
- 统计端点：`/analytics/accuracy`（含 daily14/recent 扩展）、`/analytics/error-tags`
- `POST /auth/guest-upgrade`：游客本机记录批量迁入账号（≤100 条/批、同载荷 skip、异载荷整体回滚）
- 集成测试全量转绿，CI 移除 AuthFlowIntegrationTest 排除（0.2.0 起恢复全量把关）

### 前端（@yilian/web，波次四）

- 登录态切换：practice/analytics 本地路由只对游客生效（`mode:"guest"` + auth-state 探针），登录用户同页面请求直达服务端；冷启动竞态由 Query 失效兜底（最坏先本地后服务端，无破坏性）
- 数据源分流：统计页（accuracy 扩展 + error-tags + today streak）、今日页（续练行/周点阵走 accuracy 扩展）、复习页（契约分页 + 题库缓存 join + overload/postpone 端点）；游客保留本地引擎直连，全链路零网络写请求
- guest-upgrade 接线：注册/登录成功后自动迁移本机练习记录（≤100 条/批、clientCreatedAt 升序、失败保留本地），设置页提供「同步本地记录到账号」手动入口与欠账提示；注册页话术说明记录去向，不贴等级标签
- 文档收口：API.md §6/§7/§10 状态更新（含 D7 参数差异表、payloadHash 幂等口径、§6.10/§6.11 新端点、§7.1 daily14/recent 扩展）、DATA-MODEL §5.11 一句一卡措辞

### 已知限制

- `GET /practice/next` 的 `sentenceId`/`errors`/`excludeIds`/`tag` 四参数服务端未实现（D7，M2.1）；登录态错题 chip 与指定句重练暂不筛选（走常规取题）
- 管理端题库页签（`/admin?tab=banks`）真实模式下 404：服务端未实现 §8.8 Admin 题库端点（契约在案，属管理端切片范围）
- 管理端其余页签、深度批改（M3）、FSRS 排程真值（M2.1）仍为演示态/预留

## [0.1.0] - 2026-10-25

### 后端（yilian-server）

- Spring Boot 4.1.2 + Java 21 骨架：V1–V8 Flyway 迁移（账号/审计/导入/题库/练习核心）
- 认证：双 token JWT（access 内存 15 分钟 + refresh 会话 JDBC）、CSRF 轮换、邀请码注册
- 题库只读端点（匿名可拉公开题库）、me/settings、上传过渡框架
- 基础设施：ApiResponse 统一响应、@OperationLog 审计、@RateLimit 限流、幂等、error_log

### 前端（@yilian/web）

- 游客免登录：三题定级 → 今日 → 练习（本地判分、改对才过、提示阶梯）→ 复习（SRS、降载）→ 统计
- 本地练习引擎：判分/排程/统计/离线队列，数据存本机 IndexedDB
- 管理端单页九页签（mock 演示态，带 TODO(mock) 标记）
- PWA（Service Worker 不缓存 API、不静默 skipWaiting）
- 视觉与可点击原型逐值对齐

### 已知限制

- 练习/分析服务端接口（#8）、guest-upgrade、管理端写接口、深度批改（M3）、FSRS（V2）未实现
- 后端测试存在 7 个存量 Error（upload→preview/commit 重构半成品），详见 docs/GAP-ANALYSIS.md
