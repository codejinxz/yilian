# 变更日志

本文件记录面向使用者的显著变更。各文档内部的细则变更见对应文档的变更记录章节。

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
