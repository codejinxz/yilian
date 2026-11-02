# 译练 · API 契约（V1 / M1 + M2）

| 项 | 内容 |
|---|---|
| 版本 | v0.1.1（讨论稿，待确认） |
| 日期 | 2026-10-24 |
| 字段来源 | `docs/DATA-MODEL.md` v0.1（唯一字段来源） |
| 上游约束 | `AGENTS.md`、`docs/PRD.md` §4/§5/§8/§11/§12、`docs/NAMING.md` |
| 下游 | `docs/openapi.yaml`（仅导出已实现端点）、`web/src/lib/api.ts` |

> 本文件只描述**目标契约**。未实现的端点标 `planned`，不能在 OpenAPI 中伪装成已实现。
>
> 与任务书早期要求的差异：新 `AGENTS.md` §3.6 已把错误响应从 `ProblemDetail` 改为 `ApiResponse<T>` + `ApiCode`。本契约按新 AGENTS 执行；`ProblemDetail` 不再使用（见 `DATA-MODEL.md` D-013）。

---

## 1. 全局约定

### 1.1 Base URL 与 Content-Type

- 所有业务接口前缀：`/api`。
- 请求/响应 JSON：`Content-Type: application/json; charset=utf-8`。
- 文件上传：`multipart/form-data`，字段名 `file`；单文件 ≤ 5MB；仅 `.json` / `.csv`。
- 健康检查：`GET /actuator/health`，不套 `ApiResponse`，不属于业务契约。

### 1.2 统一响应信封

成功：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

失败：

```json
{
  "code": 40000,
  "message": "请求参数不合法",
  "data": {
    "errors": [
      { "field": "email", "message": "邮箱格式不正确" }
    ]
  }
}
```

规则：

- `code=0` 是唯一成功判据；HTTP 2xx 但 `code!=0` 仍按失败处理。
- `message` 是可直接展示的中文；未知异常固定为通用文案，不返回堆栈。
- `data` 始终存在；无数据为 `null`；校验失败可带 `errors`。
- HTTP 状态表示类别（2xx/4xx/5xx），细分支看 `code`。
- 不使用 `ProblemDetail`、不返回 HTML 错误页。

### 1.3 错误码总表

| 业务码 | HTTP | 枚举 | message | 使用场景 |
|---|---|---|---|---|
| `0` | 200 | `SUCCESS` | `success` | 成功 |
| `40000` | 400 | `VALIDATION_FAILED` | 请求参数不合法 | Bean Validation/参数级校验 |
| `40001` | 400 | `MALFORMED_REQUEST` | 请求体格式不正确 | JSON 解析失败/类型不匹配 |
| `40002` | 400 | `INVALID_INVITE_CODE` | 邀请码无效或已过期 | 注册 |
| `40003` | 400 | `WEAK_PASSWORD` | 密码强度不足 | 注册/改密 |
| `40004` | 400 | `INVALID_PAGINATION` | 分页参数不合法 | page/size/limit 越界 |
| `40005` | 400 | `INVALID_SINCE_VERSION` | 版本号不合法 | 增量拉取 `sinceVersion` 超过银行版本 |
| `40100` | 401 | `AUTH_REQUIRED` | 请先登录 | access/refresh 失效 |
| `40101` | 401 | `AUTH_INVALID_CREDENTIALS` | 用户名或密码错误 | 登录失败、账号停用（统一文案） |
| `40300` | 403 | `ACCESS_DENIED` | 没有访问权限 | 角色不足、访问他人资源 |
| `40301` | 403 | `CSRF_INVALID` | CSRF 令牌无效，请刷新页面后重试 | CSRF 缺失/不匹配 |
| `40302` | 403 | `REGISTRATION_CLOSED` | 当前未开放注册 | 邀请制关闭 |
| `40303` | 403 | `PASSWORD_CHANGE_REQUIRED` | 请先修改初始密码 | 首次登录强制改密 |
| `40400` | 404 | `NOT_FOUND` | 请求的资源不存在 | 通用路由/资源 |
| `40401` | 404 | `BANK_NOT_FOUND` | 题库不存在 | 题库 |
| `40402` | 404 | `SENTENCE_NOT_FOUND` | 题目不存在 | 题目 |
| `40403` | 404 | `ATTEMPT_NOT_FOUND` | 答题记录不存在 | attempt |
| `40404` | 404 | `REVIEW_NOT_FOUND` | 复习记录不存在 | review |
| `40405` | 404 | `IMPORT_BATCH_NOT_FOUND` | 导入批次不存在 | import |
| `40500` | 405 | `METHOD_NOT_ALLOWED` | 请求方法不被支持 | 方法错误 |
| `40900` | 409 | `IDEMPOTENT_CONFLICT` | 请求正在处理或已处理，请勿重复提交 | 通用幂等冲突 |
| `40901` | 409 | `EMAIL_ALREADY_EXISTS` | 邮箱已被使用 | 注册 |
| `40902` | 409 | `USERNAME_ALREADY_EXISTS` | 用户名已被使用 | 注册 |
| `40903` | 409 | `ATTEMPT_ID_REUSED` | 该答题 id 已被其他内容使用 | 同 attemptId 不同载荷 |
| `40904` | 409 | `IMPORT_NOT_COMMITTABLE` | 当前批次不可提交 | 预览未完成/已提交/已回滚 |
| `40905` | 409 | `INVITE_ALREADY_USED` | 邀请码已被使用 | 注册 |
| `41300` | 413 | `FILE_TOO_LARGE` | 文件超过 5MB 上限 | 导入 |
| `41500` | 415 | `UNSUPPORTED_MEDIA_TYPE` | 请求的媒体类型不被支持 | Content-Type |
| `42200` | 422 | `IMPORT_VALIDATION_FAILED` | 导入校验未通过 | 提交含非法行 |
| `42900` | 429 | `RATE_LIMITED` | 操作过于频繁，请稍后再试 | 限流；带 `Retry-After` |
| `50000` | 500 | `INTERNAL_ERROR` | 服务器暂时无法处理该请求 | 未知异常 |

新增错误码规则：

- 号段固定：400xx 参数、401xx 认证、403xx 权限、404xx 资源、409xx 冲突、413/415/422 请求体、429 限流、500 内部。
- 发布后不改号、不复用；删除枚举值视为破坏性变更。
- 前端按 `code` 分支，`message` 只展示；不要把 `message` 当协议。

### 1.4 认证与 CSRF

认证采用双 token（新 AGENTS §3.7）：

- **access token**：JWT，10–15 分钟，只存前端内存；请求头 `Authorization: Bearer <token>`。
- **refresh token**：不透明值，存 Spring Session JDBC；Cookie 名默认 `YILIAN_REFRESH`，`HttpOnly + SameSite=Strict + Path=/api/auth`；prod `Secure`。
- 刷新：`POST /api/auth/refresh` 带 refresh Cookie，服务端轮换 session id。
- 登出/改密：吊销该用户全部 refresh 会话；旧 access token 最多再存活 15 分钟，不做黑名单。
- CSRF：非 GET 请求必须带 `X-XSRF-TOKEN`；token 来自 `GET /api/auth/csrf` 或可读 Cookie `XSRF-TOKEN`。
- 登录/刷新/登出虽公开，但仍受 CSRF 保护。
- 首次登录 `passwordChanged=false` 时，除 `/api/auth/me`、`/api/auth/password`、`/api/auth/logout`、`/api/auth/csrf` 外的业务接口返回 `40303 PASSWORD_CHANGE_REQUIRED`。

鉴权级别：

| 级别 | 含义 |
|---|---|
| `PUBLIC` | 无需 access token；公开题库仅返回公开数据 |
| `USER` | 必须登录，且只能访问自己的数据 |
| `ADMIN` | 必须 `ROLE_ADMIN`；服务端强制，前端隐藏不算权限 |
| `PUBLIC_OR_USER` | 匿名可用，登录后返回额外私有数据（如自己的题库） |

### 1.5 分页

管理端/列表用 page-based：

请求参数：

| 参数 | 类型 | 必填 | 默认 | 规则 |
|---|---|---|---|---|
| `page` | int | 否 | 1 | ≥1 |
| `size` | int | 否 | 20 | 1–100 |
| `keyword` | string | 否 | — | 关键字，最长 100 |
| `sort` | string | 否 | 端点定义 | 只允许白名单字段 |

响应：

```json
{
  "items": [],
  "page": 1,
  "size": 20,
  "total": 0,
  "totalPages": 0,
  "hasNext": false
}
```

规则：筛选条件变化回到第 1 页；`total` 是满足筛选的总数；空列表 `items=[]`，不是 `null`。

### 1.6 增量拉取

内容接口用 version cursor，不用 page：

请求：`GET /api/banks/{bankId}/sentences?sinceVersion=0&limit=100`

| 参数 | 类型 | 必填 | 默认 | 规则 |
|---|---|---|---|---|
| `sinceVersion` | long | 否 | 0 | ≥0；不能大于 `sentence_banks.version`，否则 40005 |
| `limit` | int | 否 | 100 | 1–200 |

响应：

```json
{
  "version": 123,
  "items": [
    {
      "id": "01J...",
      "version": 123,
      "sentenceVersionId": "1001",
      "questionVersion": 3,
      "status": "PUBLISHED",
      "deleted": false,
      "zh": "他越来越重视这件事。",
      "ref": "He is paying more and more attention to this.",
      "note": "「越来越」用 more and more 或 increasingly。",
      "chunks": [],
      "blank": [1, 2],
      "tags": ["副词结构", "搭配"],
      "level": "TEM8"
    }
  ],
  "nextSinceVersion": 123,
  "hasMore": false
}
```

规则：

- `version` 是当前题库游标；`items[].version` 是该题最后一次变更的银行级修订号。
- 删除/停用也返回 item，`deleted=true`，只带 `id`/`version`；客户端据此移除本地缓存。
- `nextSinceVersion` 取本页最大 `version`；无数据时等于 `sinceVersion`。
- 银行修订号按「每题递增」：导入提交与回滚中每行获得独立 version（不共享同批号），分页不丢行。
- 不可练状态（DRAFT/DISABLED/RECYCLED/ARCHIVED）一律返回 `deleted=true` 的 item（未知 id 客户端直接忽略）。
- `hasMore=true` 时继续用 `nextSinceVersion` 拉下一页；不要用 page。
- 没有“全量快照”接口；首次拉取 `sinceVersion=0`，循环到 `hasMore=false`。

### 1.7 时间、ID、空值

- 时间：UTC ISO-8601，带 `Z`，如 `2026-10-24T05:25:00Z`；毫秒可选，统一到秒/毫秒由 `DATETIME(6)` 序列化决定。
- 日期：仅 `user_daily_stats.stat_date` 用 `YYYY-MM-DD`，且是用户本地日期。
- ID：`attemptId` 为 UUID 字符串；`sentenceId` 为 ULID 字符串；其余 BIGINT 在 API 中序列化为字符串（`users.id`、`bankId`、`sentenceVersionId` 等），避免 JS 精度问题。
- 空值：字段可空时显式 `null`，不省略；数组空为 `[]`；对象不存在为 `null`。
- 枚举：JSON 用大写英文，如 `CLOZE`、`LEARNING`；与 `DATA-MODEL.md` §7 一致。

### 1.8 幂等

- `attemptId` 由客户端生成 UUID，提交时放在 body。
- 同一个 `attemptId`：
  - 同一 `user_id` + 同一 `answer_hash`：返回已有结果，HTTP 200，`replayed=true`。
  - 不同 `user_id` 或不同 `answer_hash`：HTTP 409 `40903 ATTEMPT_ID_REUSED`。
- 并发重复以数据库 `attempts.id` 唯一约束为准；应用层只做友好返回。
- 导入提交以 `batchId` 幂等；重复 commit 返回当前批次，不重复写题。

### 1.9 状态标记

| 状态 | 含义 |
|---|---|
| `implemented` | 当前代码已实现且与本契约一致 |
| `planned` | 契约已定，尚未实现；OpenAPI 不导出 |
| `transitional` | 当前代码存在但契约将替换，**不写入 OpenAPI**，仅在本文件 §10 说明 |

> 当前仓库的 `/api/admin/uploads` 上传/轮询接口属于 `transitional`；目标契约是 §5.3 的 preview/commit。

---

## 2. 鉴权矩阵

| 端点 | 方法 | 级别 | 说明 |
|---|---|---|---|
| `/api/auth/csrf` | GET | `PUBLIC` | 播种 CSRF |
| `/api/auth/login` | POST | `PUBLIC` | 登录；CSRF |
| `/api/auth/refresh` | POST | `PUBLIC` | refresh Cookie；CSRF |
| `/api/auth/logout` | POST | `PUBLIC` | 可带 Bearer；CSRF |
| `/api/auth/register` | POST | `PUBLIC` | 邀请码；CSRF |
| `/api/auth/me` | GET | `USER` | 当前用户 |
| `/api/auth/password` | PUT | `USER` | 改密；吊销 refresh |
| `/api/auth/guest-upgrade` | POST | `USER` | 游客本地记录升级 |
| `/api/me/settings` | GET/PUT | `USER` | 用户设置 |
| `/api/banks` | GET | `PUBLIC_OR_USER` | 公开题库 + 自己的私有题库 |
| `/api/banks/{bankId}` | GET | `PUBLIC_OR_USER` | 题库详情 |
| `/api/banks/{bankId}/sentences` | GET | `PUBLIC_OR_USER` | 增量拉句 |
| `/api/practice/next` | GET | `USER` | 取下一题 |
| `/api/practice/attempts` | POST | `USER` | 提交 attempt |
| `/api/practice/attempts/{attemptId}` | GET | `USER` | 结果详情 |
| `/api/practice/attempts/{attemptId}/annotations/{key}/appeal` | POST | `USER` | 我觉得也对 |
| `/api/practice/attempts/{attemptId}/annotations/{key}/dispute` | POST | `USER` | 不认同，排除批注 |
| `/api/practice/wrong-questions` | GET | `USER` | 错题列表 |
| `/api/practice/reviews` | GET | `USER` | 复习队列 |
| `/api/practice/reviews/{reviewId}/reset` | POST | `USER` | 重置为 new |
| `/api/practice/today` | GET | `USER` | 今日启动台数字 |
| `/api/analytics/accuracy` | GET | `USER` | 正确率（M3） |
| `/api/analytics/error-tags` | GET | `USER` | 错误类型分布（M3） |
| `/api/admin/users` | GET | `ADMIN` | 用户列表 |
| `/api/admin/users/{id}/status` | PATCH | `ADMIN` | 启停 |
| `/api/admin/users/{id}/role` | PATCH | `ADMIN` | 角色 |
| `/api/admin/audit-logs` | GET | `ADMIN` | 操作日志 |
| `/api/admin/error-logs` | GET | `ADMIN` | 异常日志 |
| `/api/admin/error-logs/{id}` | GET | `ADMIN` | 异常详情 |
| `/api/admin/attempts/{attemptId}/raw` | POST | `ADMIN` | 查看原文（必填原因 + 审计） |
| `/api/admin/banks` | GET/POST | `ADMIN` | 题库管理 |
| `/api/admin/banks/{bankId}` | GET/PATCH/DELETE | `ADMIN` | 题库管理 |
| `/api/admin/banks/{bankId}/sentences` | GET | `ADMIN` | 管理端题目列表 |
| `/api/admin/sentences/{sentenceId}/status` | PATCH | `ADMIN` | 停用/恢复/回收 |
| `/api/admin/uploads/preview` | POST | `ADMIN` | 导入预览 |
| `/api/admin/uploads/{batchId}/commit` | POST | `ADMIN` | 导入提交 |
| `/api/admin/uploads/{batchId}` | GET | `ADMIN` | 批次查询 |
| `/api/admin/uploads` | GET | `ADMIN` | 批次列表 |
| `/api/admin/uploads/{batchId}/rollback` | POST | `ADMIN` | 批次回滚 |

---

## 3. 端点总表

| 分组 | 端点 | 方法 | 鉴权 | 里程碑 | 状态 |
|---|---|---|---|---|---|
| account | `/api/auth/csrf` | GET | PUBLIC | M1 | implemented |
| account | `/api/auth/login` | POST | PUBLIC | M1 | implemented |
| account | `/api/auth/refresh` | POST | PUBLIC | M1 | implemented |
| account | `/api/auth/logout` | POST | PUBLIC | M1 | implemented |
| account | `/api/auth/me` | GET | USER | M1 | implemented |
| account | `/api/auth/register` | POST | PUBLIC | M1 | planned |
| account | `/api/auth/password` | PUT | USER | M1 | planned |
| account | `/api/auth/guest-upgrade` | POST | USER | M2 | planned |
| account | `/api/me/settings` | GET/PUT | USER | M1 | planned |
| admin | `/api/admin/error-logs/{id}` | GET | ADMIN | M2 | implemented |
| content | `/api/banks` | GET | PUBLIC_OR_USER | M1 | planned |
| content | `/api/banks/{bankId}` | GET | PUBLIC_OR_USER | M1 | planned |
| content | `/api/banks/{bankId}/sentences` | GET | PUBLIC_OR_USER | M1/M2 | planned |
| content | `/api/admin/uploads/preview` | POST | ADMIN | M1 | planned |
| content | `/api/admin/uploads/{batchId}/commit` | POST | ADMIN | M1 | planned |
| content | `/api/admin/uploads/{batchId}` | GET | ADMIN | M1 | planned |
| content | `/api/admin/uploads` | GET | ADMIN | M1 | planned |
| content | `/api/admin/uploads/{batchId}/rollback` | POST | ADMIN | M1 | planned |
| practice | `/api/practice/next` | GET | USER | M2 | planned |
| practice | `/api/practice/attempts` | POST | USER | M2 | planned |
| practice | `/api/practice/attempts/{attemptId}` | GET | USER | M2 | planned |
| practice | `/api/practice/attempts/{attemptId}/annotations/{key}/appeal` | POST | USER | M2/M3 | planned |
| practice | `/api/practice/attempts/{attemptId}/annotations/{key}/dispute` | POST | USER | M2 | planned |
| practice | `/api/practice/wrong-questions` | GET | USER | M2 | planned |
| practice | `/api/practice/reviews` | GET | USER | M2 | planned |
| practice | `/api/practice/reviews/{reviewId}/reset` | POST | USER | M2 | planned |
| practice | `/api/practice/today` | GET | USER | M2 | planned |
| analytics | `/api/analytics/accuracy` | GET | USER | M3 | planned |
| analytics | `/api/analytics/error-tags` | GET | USER | M3 | planned |
| admin | `/api/admin/users` | GET | ADMIN | M2 | implemented |
| admin | `/api/admin/users/{id}/status` | PATCH | ADMIN | M2 | implemented |
| admin | `/api/admin/users/{id}/role` | PATCH | ADMIN | M2 | implemented |
| admin | `/api/admin/overview` | GET | ADMIN | M2 | implemented |
| admin | `/api/admin/invites` | GET/POST | ADMIN | M2 | implemented |
| admin | `/api/admin/audit-logs` | GET | ADMIN | M2 | implemented |
| admin | `/api/admin/error-logs` | GET | ADMIN | M2 | implemented |
| admin | `/api/admin/generation/import` | POST | ADMIN | M2 | implemented |
| admin | `/api/admin/audit-events` | POST | ADMIN | M2 | implemented |
| admin | `/api/admin/attempts/{attemptId}/raw` | POST | ADMIN | M2 | planned |
| admin | `/api/admin/banks` | GET/POST | ADMIN | M1 | implemented |
| admin | `/api/admin/banks/{bankId}` | GET/PATCH/DELETE | ADMIN | M1 | implemented |
| admin | `/api/admin/banks/{bankId}/sentences` | GET | ADMIN | M1 | implemented |
| admin | `/api/admin/sentences` | GET | ADMIN | M1 | implemented |
| admin | `/api/admin/sentences/bulk-status` | POST | ADMIN | M1 | implemented |
| admin | `/api/admin/sentences/bulk-move` | POST | ADMIN | M1 | implemented |
| admin | `/api/admin/sentences/{sentenceId}` | PATCH | ADMIN | M1 | implemented |
| admin | `/api/admin/sentences/{sentenceId}/status` | PATCH | ADMIN | M1 | implemented |

> `implemented` 只覆盖当前认证骨架；其余在 M1/M2 实现后再改为 `implemented`。`transitional` 的 upload 上传接口见 §10，不纳入本表。

---

## 4. Account

### 4.1 `GET /api/auth/csrf`（implemented）

用途：播种/读取 CSRF token，供后续非 GET 请求回填。

请求：无参数。

响应 `data`：

```json
{
  "token": "a1b2c3...",
  "headerName": "X-XSRF-TOKEN",
  "parameterName": "_csrf"
}
```

副作用：`Set-Cookie: XSRF-TOKEN=...; Path=/; SameSite=Strict`；local 非 Secure，prod Secure；HttpOnly=false（前端需读取）。

错误：`50000`。

字段回溯：`token` 派生（CSRF 仓库）；无表列。

### 4.2 `POST /api/auth/login`（implemented）

用途：用户名/邮箱 + 密码换取 access token，并建立 refresh 会话。

请求：

```json
{
  "username": "admin",
  "password": "change-me-now"
}
```

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| `username` | string | 是 | 1–64；允许用户名或邮箱；首尾空格不自动去 |
| `password` | string | 是 | 1–72 字符；UTF-8 字节数 ≤72 |

响应 `data`：

```json
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": {
    "id": "1",
    "username": "admin",
    "role": "ADMIN",
    "passwordChanged": true
  }
}
```

副作用：`Set-Cookie: YILIAN_REFRESH=...; Path=/api/auth; HttpOnly; SameSite=Strict; Max-Age=2592000`；session id 轮换。

错误：`40000`、`40001`、`40101`、`40301`、`42900`、`50000`。

字段回溯：

| API 字段 | 来源 | 说明 |
|---|---|---|
| `accessToken` | 派生 | JWT 签发 |
| `expiresIn` | 派生 | `yilian.security.jwt.access-token-ttl` |
| `user.id` | `users.id` | 当前实现为 number，M1 收口为 string |
| `user.username` | `users.username` | — |
| `user.role` | `users.role` | — |

### 4.3 `POST /api/auth/refresh`（implemented）

用途：用 refresh Cookie 换新 access token，并轮换 session id。

请求：无 body；必须带 `YILIAN_REFRESH` Cookie 和 `X-XSRF-TOKEN`。

响应：同登录 `AuthResponse`。

错误：`40100`、`40301`、`50000`。

字段回溯：同登录；refresh token 不进入响应体。

### 4.4 `POST /api/auth/logout`（implemented）

用途：吊销该用户全部 refresh 会话并清空 Cookie。

请求：无 body；可带 Bearer；必须带 CSRF。

响应：

```json
{ "code": 0, "message": "已退出登录", "data": null }
```

错误：`40301`、`50000`。

字段回溯：无业务表；会话由 Spring Session 管理。

### 4.5 `GET /api/auth/me`（implemented）

用途：读取当前登录用户。

请求：`Authorization: Bearer`。

响应 `data`：

```json
{ "id": "1", "username": "admin", "role": "ADMIN", "passwordChanged": true }
```

错误：`40100`、`50000`。

字段回溯：`users.id`、`users.username`、`users.role`、`users.password_changed`。

### 4.6 `POST /api/auth/register`（implemented）

用途：邀请码注册，创建用户并直接登录。

请求：

```json
{
  "email": "learner@example.com",
  "username": "learner",
  "password": "correct-horse-battery",
  "inviteCode": "YL-AB12-CD34"
}
```

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| `email` | string | 是 | 合法邮箱，≤255；唯一 |
| `username` | string | 否 | 3–64，字母数字 `._-`；为空时由邮箱本地部分派生并保证唯一 |
| `password` | string | 是 | 8–72 字节；至少 8 字符；不能等于用户名/邮箱 |
| `inviteCode` | string | 条件 | `registration.invite_required=true` 时必填；≤64 |

响应：`AuthResponse`，同登录。

错误：`40000`、`40002`、`40003`、`40302`、`40901`、`40902`、`40905`、`42900`、`50000`。

副作用：写 `users`、`invite_codes.used_by/used_at/used_count`；建立 refresh 会话；写 `admin_audit_log`? 注册不是管理员操作，不写审计；写结构化日志。

字段回溯：

| API 字段 | 来源 | 说明 |
|---|---|---|
| `email` | `users.email` | 唯一 |
| `username` | `users.username` | 可派生 |
| `password` | `users.password_hash` | 派生 BCrypt |
| `inviteCode` | `invite_codes.code_hash` | 派生 SHA-256 比对 |

### 4.7 `PUT /api/auth/password`（implemented）

用途：改密并吊销全部 refresh 会话。

请求：

```json
{ "currentPassword": "old", "newPassword": "new-correct-horse" }
```

响应：`{ "code": 0, "message": "密码已更新", "data": null }`

错误：`40000`、`40003`、`40100`、`40101`、`42900`、`50000`。

副作用：更新 `users.password_hash/password_updated_at/password_changed=true`；吊销 refresh 会话；写 `admin_audit_log`（actor 自己，action=`PASSWORD_CHANGE`）。

字段回溯：`users.password_hash`、`users.password_updated_at`、`users.password_changed`。

### 4.8 `POST /api/auth/guest-upgrade`（implemented）

用途：注册后把本地未同步的 attempt 批量提交到账号；不迁移服务端游客记录（游客只落本地）。

请求：

```json
{
  "attempts": [
    {
      "attemptId": "uuid",
      "sentenceId": "01J...",
      "sentenceVersionId": "1001",
      "mode": "CLOZE",
      "kind": "FIRST",
      "retryOfId": null,
      "answer": { "schemaVersion": 1, "mode": "CLOZE", "slots": [] },
      "hintsUsed": 0,
      "durationMs": 12000,
      "clientCreatedAt": "2026-10-23T10:00:00Z"
    }
  ]
}
```

规则：最多 100 条/次；逐条幂等；`kind`/`retryOfId` 规则同 §6.2；已存在同 `attemptId` 同载荷跳过；不同载荷返回 409 并整体回滚。

响应：

```json
{ "imported": 10, "skipped": 2, "conflicts": 0 }
```

错误：`40000`、`40100`、`40903`、`50000`。

字段回溯：`attempts.*`；`user_id` 由当前登录用户写入，不回填旧本地记录。

### 4.9 `GET/PUT /api/me/settings`（implemented）

用途：读取/更新用户设置。

`GET` 响应：

```json
{
  "timezone": "Asia/Shanghai",
  "dailyGoal": 3,
  "ratingStandard": "TEM8",
  "desiredRetention": 0.9,
  "theme": "paper",
  "notificationsEnabled": false
}
```

`PUT` 请求同结构（全部可选，至少一个）；校验：

- `timezone` 必须是 IANA 时区；后端 `ZoneId.of` 校验。
- `dailyGoal ∈ {1,3,5}`。
- `ratingStandard ∈ {TEM8,CATTI,IELTS}`。
- `desiredRetention ∈ [0.80,0.97]`。
- `theme ∈ {paper,night}`。

错误：`40000`、`40100`、`50000`。

字段回溯：`users.timezone/daily_goal/rating_standard/desired_retention/theme/notifications_enabled`。

---

## 5. Content

### 5.1 `GET /api/banks`（implemented）

用途：题库列表；匿名只返回公开题库，登录后附自己的私有题库。

请求：`page`、`size`、`keyword`、`visibility`（可选）、`status`（可选，默认 PUBLISHED）。

响应 `PageResponse<BankView>`：

```json
{
  "items": [
    {
      "id": "1",
      "ownerId": null,
      "name": "官方题库 · TEM8 翻译",
      "description": "专八翻译语块",
      "visibility": "PUBLIC",
      "status": "PUBLISHED",
      "version": 12,
      "sentenceCount": 48
    }
  ],
  "page": 1,
  "size": 20,
  "total": 1,
  "totalPages": 1,
  "hasNext": false
}
```

错误：`40004`、`50000`。

字段回溯：`sentence_banks.*`；`sentenceCount` 派生 `COUNT(sentences WHERE status='PUBLISHED' AND deleted_at IS NULL)`。

### 5.2 `GET /api/banks/{bankId}`（implemented）

用途：题库详情 + 当前版本。

响应 `BankView`；错误 `40401`、`40300`（私有题库非本人）、`50000`。

字段回溯：`sentence_banks.*`；`ownerId` 为 null 表示官方。

### 5.3 `GET /api/banks/{bankId}/sentences`（implemented）

用途：按版本增量拉取题目，支持离线缓存。

请求：`sinceVersion`、`limit`（见 §1.6）。

响应 `VersionedResponse<SentenceView>`：

```json
{
  "version": 12,
  "items": [
    {
      "id": "01J...",
      "version": 12,
      "sentenceVersionId": "1001",
      "questionVersion": 3,
      "status": "PUBLISHED",
      "deleted": false,
      "zh": "他越来越重视这件事。",
      "ref": "He is paying more and more attention to this.",
      "note": "「越来越」用 more and more 或 increasingly。",
      "chunks": [
        { "index": 0, "zh": "他", "answers": ["He"], "tag": "代词", "note": null },
        { "index": 1, "zh": "越来越", "answers": ["more and more", "increasingly"], "tag": "副词结构", "note": null }
      ],
      "blank": [1, 2],
      "tags": ["副词结构", "搭配"],
      "level": "TEM8"
    }
  ],
  "nextSinceVersion": 12,
  "hasMore": false
}
```

删除 tombstone：

```json
{ "id": "01J...", "version": 13, "deleted": true }
```

错误：`40004`、`40005`、`40100`（私有题库匿名）、`40300`、`40401`、`50000`。

字段回溯：

| API 字段 | 来源 | 说明 |
|---|---|---|
| `id` | `sentences.id` | ULID |
| `version` | `sentences.version` | 银行级修订号 |
| `sentenceVersionId` | `sentences.current_version_id` | 当前快照 |
| `questionVersion` | `sentence_versions.version_no` | 单题内容版本 |
| `status` | `sentences.status` | — |
| `deleted` | `sentences.deleted_at` | 派生 |
| `zh/ref/note/chunks/blank/tags/level` | `sentence_versions.*` | 当前快照 |
| `hasMore/nextSinceVersion` | 派生 | 分页游标 |

### 5.4 `POST /api/admin/uploads/preview`（planned，M1）

用途：上传 JSON/CSV，逐行校验并生成可提交/可回滚的预览批次；**不写题目**。

请求：`multipart/form-data`

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `file` | file | 是 | `.json`/`.csv`；≤5MB；UTF-8 |
| `bankId` | string | 是 | 目标题库；管理员可见 |
| `format` | string | 是 | `JSON`/`CSV` |

响应 `data`：

```json
{
  "batchId": "uuid",
  "bankId": "1",
  "status": "PREVIEWED",
  "format": "JSON",
  "totalRows": 50,
  "validRows": 47,
  "invalidRows": 3,
  "rows": [
    { "rowNo": 3, "status": "INVALID", "externalKey": "s_0003", "errors": [
      { "code": "CHUNK_REF_MISMATCH", "field": "chunks", "message": "chunks[].answers[0] 按序拼接与 ref 不一致" }
    ]}
  ],
  "errorSummary": { "schemaVersion": 1, "code": "ROW_VALIDATION_FAILED", "message": "3 行未通过校验", "sampleRows": [3,7,9] }
}
```

规则：校验项按 PRD §6 五条 + `zh/ref/note` 长度 + 精确指纹去重；`rows` 只返回前 100 条错误，完整逐行结果通过批次查询分页。`invalidRows>0` 仍返回 200（预览成功，不是请求失败）；提交时才用 422。

错误：`40000`、`40004`、`40300`、`40401`、`41300`、`41500`、`42900`、`50000`。

字段回溯：`import_batches.*`、`import_batch_rows.*`；`file` 写 `storage_key`；`bankId` 写 `bank_id`。

### 5.5 `POST /api/admin/uploads/{batchId}/commit`（planned，M1）

用途：把预览通过的批次提交入库；单事务，失败整批回滚。

请求：`{ "reason": "首次导入" }`（可选；管理员操作审计用）。

响应 `data`：`ImportBatchView`

```json
{
  "batchId": "uuid",
  "bankId": "1",
  "status": "COMMITTED",
  "totalRows": 50,
  "validRows": 47,
  "invalidRows": 3,
  "importedRows": 47,
  "committedAt": "2026-10-24T05:30:00Z"
}
```

规则：

- 只接受 `PREVIEWED`；其他状态 40904。
- 有效行写 `sentence_versions` + `sentences`；`sentences.version` 使用新银行修订号。
- 失败整批回滚，批次 `FAILED`，不留下半批题目。
- 重复 commit：已 `COMMITTED` 返回当前批次（幂等），不重复写。

错误：`40000`、`40300`、`40405`、`40904`、`42200`、`50000`。

字段回溯：`import_batches.*`、`sentences.*`、`sentence_versions.*`、`import_batch_rows.sentence_id/sentence_version_id`。

### 5.6 `GET /api/admin/uploads/{batchId}`（planned，M1）

用途：批次详情 + 行级错误分页。

请求：`page`、`size`、`status`（`VALID`/`INVALID`/`IMPORTED`/`SKIPPED`，可选）。

响应：

```json
{
  "batch": {
    "batchId": "uuid",
    "bankId": "1",
    "format": "JSON",
    "originalFilename": "questions.json",
    "status": "PREVIEWED",
    "totalRows": 50,
    "validRows": 47,
    "invalidRows": 3,
    "importedRows": 0,
    "createdAt": "2026-10-24T05:00:00Z",
    "previewedAt": "2026-10-24T05:01:00Z",
    "committedAt": null,
    "errorSummary": { "schemaVersion": 1, "code": "ROW_VALIDATION_FAILED", "message": "3 行未通过校验" }
  },
  "rows": {
    "items": [
      {
        "rowNo": 3,
        "externalKey": "s_0003",
        "status": "INVALID",
        "errors": [
          { "code": "CHUNK_REF_MISMATCH", "field": "chunks", "message": "..." }
        ],
        "sentenceId": null,
        "sentenceVersionId": null
      }
    ],
    "page": 1,
    "size": 20,
    "total": 3,
    "totalPages": 1,
    "hasNext": false
  }
}
```

错误：`40405`、`40300`、`50000`。

字段回溯：`import_batches.*`、`import_batch_rows.*`；`rows` 分页派生。

### 5.7 `GET /api/admin/uploads`（planned，M1）

用途：批次列表；按题库/状态/时间筛选。

请求：`page`、`size`、`bankId`、`status`、`from`、`to`。

响应 `PageResponse<ImportBatchView>`；错误 `40004`、`40300`、`50000`。

字段回溯：`import_batches.*`。

### 5.8 `POST /api/admin/uploads/{batchId}/rollback`（planned，M1）

用途：回滚已提交批次；题目进回收站并 bump 版本，不硬删、不级联删 attempt。

请求：

```json
{ "reason": "内容有误" }
```

规则：只接受 `COMMITTED`；重复回滚返回当前 `ROLLED_BACK`（幂等）。

响应 `ImportBatchView`；错误 `40000`、`40300`、`40405`、`40904`、`50000`。

字段回溯：`import_batches.status/rolled_back_at/rolled_back_by/rollback_reason`；`sentences.status/deleted_at/version`。

---

## 6. Practice

### 6.1 `GET /api/practice/next`（implemented）

用途：取下一题；优先到期复习，其次新题；返回当前 review 状态。

请求：`bankId`（可选）、`mode`（可选 `CLOZE`/`FREE`）、`excludeSentenceId`（可选）。

D7 参数（契约全量，服务端与前端本地引擎/mock 的差异如实标注）：

| 参数 | 语义 | 服务端 | 本地引擎 / mock |
|---|---|---|---|
| `bankId` | 限定题库 | 支持 | 支持 |
| `mode` | 模式筛选 | 支持（校验 CLOZE/FREE；Q1 一句一卡下不参与选题） | 支持 |
| `excludeSentenceId` | 单题排除（跳句） | 支持 | 支持（并进 `excludeIds`） |
| `sentenceId` | 指定句重练（复习页「重练」），优先级最高 | 未实现（M2.1 补齐） | 支持 |
| `errors=1` | 只在错题里取下一题（今日页「错题」chip） | 未实现（M2.1 补齐） | 支持 |
| `excludeIds` | 多题排除（热身排除已练） | 未实现（M2.1 补齐） | 支持（逗号分隔） |
| `tag` | 考点筛选 | 未实现（M2.1 补齐） | 支持 |

响应：

```json
{
  "sentence": {
    "id": "01J...",
    "version": 12,
    "sentenceVersionId": "1001",
    "questionVersion": 3,
    "status": "PUBLISHED",
    "deleted": false,
    "zh": "他越来越重视这件事。",
    "ref": "He is paying more and more attention to this.",
    "note": "「越来越」用 more and more 或 increasingly。",
    "chunks": [],
    "blank": [1, 2],
    "tags": ["副词结构", "搭配"],
    "level": "TEM8"
  },
  "review": {
    "id": "10",
    "state": "LEARNING",
    "fsrsState": "LEARNING",
    "dueAt": "2026-10-25T00:00:00Z",
    "stability": 3.2,
    "difficulty": 5.1,
    "reps": 2,
    "lapses": 1,
    "scheduledDays": 1
  },
  "selection": "DUE"
}
```

规则：`selection ∈ {DUE, NEW, MANUAL}`；没有可练题时 `data=null`，HTTP 200（不是 404）；客户端显示空状态。

错误：`40100`、`40401`、`50000`。

字段回溯：`sentences.*`、`sentence_versions.*`、`reviews.*`；`selection` 派生。

### 6.2 `POST /api/practice/attempts`（implemented）

用途：提交一次作答；幂等；判分；更新 FSRS；返回参考译文/解析/批注。

请求：

```json
{
  "attemptId": "3f0e...",
  "sentenceId": "01J...",
  "sentenceVersionId": "1001",
  "mode": "CLOZE",
  "kind": "FIRST",
  "retryOfId": null,
  "answer": {
    "schemaVersion": 1,
    "mode": "CLOZE",
    "slots": [
      { "chunkIndex": 1, "value": "more and more" },
      { "chunkIndex": 2, "value": "pay attentions to" }
    ]
  },
  "hintsUsed": 0,
  "durationMs": 12000,
  "clientCreatedAt": "2026-10-23T10:00:00Z"
}
```

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `attemptId` | string(uuid) | 是 | 客户端生成；幂等键 |
| `sentenceId` | string | 是 | 必须属于可访问题库 |
| `sentenceVersionId` | string | 是 | 必须是该题版本 |
| `mode` | string | 是 | `CLOZE`/`FREE` |
| `kind` | string | 是 | `FIRST`/`RETRY`/`REVEAL` |
| `retryOfId` | string | 条件 | `kind=RETRY` 必填，必须同一用户/题/模式 |
| `answer` | object | 是 | 见 DATA-MODEL §6.4 |
| `hintsUsed` | int | 否 | 0–3，默认 0 |
| `durationMs` | int | 否 | 0–86400000 |
| `clientCreatedAt` | string | 否 | UTC；仅离线排序，统计用服务端时间 |

响应：

```json
{
  "attemptId": "3f0e...",
  "sentenceId": "01J...",
  "sentenceVersionId": "1001",
  "mode": "CLOZE",
  "kind": "FIRST",
  "score": 80,
  "maxScore": 100,
  "result": {
    "schemaVersion": 1,
    "score": 80,
    "maxScore": 100,
    "hitCount": 8,
    "totalCount": 10,
    "annotations": [
      {
        "key": "slot:2",
        "chunkIndex": 2,
        "tag": "搭配",
        "expected": ["pay attention to", "attach importance to"],
        "received": "pay attentions to",
        "correct": false,
        "message": "搭配错误",
        "minimalFix": "pay attention to"
      }
    ],
    "summary": "还差 1 处"
  },
  "grading": {
    "id": "100",
    "sequence": 1,
    "grader": "LOCAL",
    "normalizerVersion": "norm-1",
    "rubricVersion": "rubric-1",
    "createdAt": "2026-10-24T05:30:00Z"
  },
  "review": {
    "id": "10",
    "state": "LEARNING",
    "fsrsState": "LEARNING",
    "dueAt": "2026-10-25T00:00:00Z",
    "stability": 2.4,
    "difficulty": 5.3,
    "reps": 1,
    "lapses": 1,
    "scheduledDays": 1,
    "rating": 2
  },
  "reference": {
    "ref": "He is paying more and more attention to this.",
    "note": "「越来越」用 more and more 或 increasingly。"
  },
  "nextAction": {
    "retryRequired": true,
    "canReveal": true
  },
  "replayed": false
}
```

规则：

- 判分本地完成；服务端按 `normalizer_version`/`rubric_version` 复核并落库。
- `score<100` 时 `retryRequired=true`；必须重打或 `REVEAL` 才能下一题。
- 只有 `FIRST`（或没有 FIRST 的 `REVEAL`）触发 FSRS；`RETRY` 不排程。
- `REVEAL` 的 `score=null`；`review.rating=1`。
- 幂等重复：同载荷返回同一响应，`replayed=true`；不同载荷 40903。**幂等口径（payloadHash）**：参与哈希的字段为 `sentenceId`、`sentenceVersionId`、`mode`、`kind`、`retryOfId`、`answer`、`hintsUsed`；**不含** `durationMs` 与 `clientCreatedAt`（耗时与本机时间不影响「同一次作答」的判定，重放/补交都按同一载荷幂等）。

错误：`40000`、`40001`、`40100`、`40402`、`40903`、`50000`。

字段回溯：

| API 字段 | 来源 | 说明 |
|---|---|---|
| `attemptId` | `attempts.id` | — |
| `sentenceId` | `attempts.sentence_id` | — |
| `sentenceVersionId` | `attempts.sentence_version_id` | — |
| `mode/kind/retryOfId` | `attempts.*` | — |
| `score/maxScore/result` | `attempt_gradings.*` | 最新 `sequence` |
| `grading.*` | `attempt_gradings.*` | — |
| `review.*` | `reviews.*` | `rating` 来自 `review_logs.rating` |
| `reference.*` | `sentence_versions.ref/note` | 派生 |
| `nextAction.*` | 派生 | `score<100 && kind=FIRST` |
| `replayed` | 派生 | 幂等命中 |

### 6.3 `GET /api/practice/attempts/{attemptId}`（implemented）

用途：批改结果详情/刷新恢复；只能看自己的。

响应：同 §6.2 的响应体；错误 `40100`、`40300`、`40403`、`50000`。

字段回溯：同上。

### 6.4 `POST /api/practice/attempts/{attemptId}/annotations/{key}/appeal`（implemented，M2 记录 PENDING；LLM 裁决 M3）

用途：提交“我觉得也对”；M2 记录 `PENDING`，M3 接 LLM 裁决。

请求：

```json
{ "proposedAnswer": "attach importance to", "reason": "更书面" }
```

实现口径（M2 已落地）：`key` 必须存在于该提交最新判分结果的批注列表（`slot:N` / `free:0`），未命中按 40000；归属校验（只能对自己提交）40300；同 `(attemptId, key, kind)` 重复提交 40900（预检 + `uk_appeal_annotation` 唯一约束兜底）；申诉不改判分、不影响统计。

响应：

```json
{
  "appealId": "20",
  "status": "PENDING",
  "kind": "APPEAL",
  "annotationKey": "slot:2"
}
```

错误：`40000`、`40100`、`40300`、`40403`、`40900`、`50000`。

字段回溯：`appeals.*`；`sentence_id/chunk_index` 从 result 批注解析。

### 6.5 `POST /api/practice/attempts/{attemptId}/annotations/{key}/dispute`（implemented）

用途：“不认同”该批注；立即排除该条错题/统计，不改题目。

请求：

```json
{ "reason": "我认为这个搭配也可接受" }
```

响应：

```json
{ "appealId": "21", "status": "ACCEPTED", "kind": "DISPUTE", "annotationKey": "slot:2" }
```

规则：`status=ACCEPTED`、`decided_by=USER`；不改 `sentence_versions`；错题列表/统计按 overlay 排除。

实现口径（M2 已落地）：与 §6.4 共用落库链路——`key` 未命中 40000、非本人提交 40300、同键重复 40900；`DISPUTE` 即时生效（`ACCEPTED`），错题列表（`includeDisputed=false` 默认隐藏该批注）与 error-tags 计数同步排除；`APPEAL` 与 `DISPUTE` 各自可存一条（kind 隔离）。

错误：`40000`、`40100`、`40300`、`40403`、`40900`、`50000`。

字段回溯：`appeals.*`。

### 6.6 `GET /api/practice/wrong-questions`（implemented）

用途：错题列表；按到期/最近错误排序。

请求：`page`、`size`、`includeDisputed`（默认 false）、`bankId`、`tag`。

响应 `PageResponse<WrongQuestionView>`：

```json
{
  "items": [
    {
      "sentenceId": "01J...",
      "mode": "CLOZE",
      "state": "LEARNING",
      "dueAt": "2026-10-25T00:00:00Z",
      "lastScore": 80,
      "lastAttemptId": "3f0e...",
      "wrongCount": 2,
      "tags": ["搭配"],
      "zh": "他越来越重视这件事。",
      "annotations": [
        { "key": "slot:2", "tag": "搭配", "message": "搭配错误", "disputed": false }
      ]
    }
  ],
  "page": 1,
  "size": 20,
  "total": 1,
  "totalPages": 1,
  "hasNext": false
}
```

规则：`includeDisputed=false` 时过滤已被用户 DISPUTE 的批注；批注全部被排除且无其他错误的题不出现在错题列表。`wrongCount` 派生最近 N 次未全对次数。

错误：`40100`、`50000`。

字段回溯：`reviews.*`、`attempts.*`、`attempt_gradings.result`、`appeals.*`；`zh` 来自当前 `sentence_versions`；`annotations` 派生。

### 6.7 `GET /api/practice/reviews`（implemented）

用途：复习队列；按 `dueAt` 升序。

请求：`page`、`size`、`state`（`NEW`/`LEARNING`/`MASTERED`）、`mode`、`due`（`true` 只看到期）。

响应 `PageResponse<ReviewView>`：

```json
{
  "items": [
    {
      "id": "10",
      "sentenceId": "01J...",
      "mode": "CLOZE",
      "state": "LEARNING",
      "fsrsState": "LEARNING",
      "dueAt": "2026-10-25T00:00:00Z",
      "stability": 3.2,
      "difficulty": 5.1,
      "reps": 2,
      "lapses": 1,
      "scheduledDays": 1,
      "lastRating": 2,
      "lastReviewedAt": "2026-10-24T05:30:00Z"
    }
  ],
  "page": 1,
  "size": 20,
  "total": 1,
  "totalPages": 1,
  "hasNext": false
}
```

错误：`40004`、`40100`、`50000`。

字段回溯：`reviews.*`；`state` 是 FSRS 投影（DATA-MODEL §8.4）。

### 6.8 `POST /api/practice/reviews/{reviewId}/reset`（implemented）

用途：用户把某张卡重置为 `NEW`（“重新学”），保留历史日志。

请求：`{ "reason": "想从头练" }`（可选）。

响应：`ReviewView`。

规则：`state=NEW`、`fsrs_state=NEW`、`due_at=now`、`stability/difficulty=0`、`reps/lapses=0`；写 `review_logs`（rating? 不写评分，写 `review_events`? 当前表只有 review_logs；用 `review_logs` 需要 rating，不合适。**修正**：`review_logs` 是 FSRS 排程日志，不接受 reset；reset 写 `admin_audit_log`? 用户操作不是 admin。需要 `review_reset_events` 或 `review_logs` 增加 `event_type`。当前 DATA-MODEL 没有。为避免新增表，`reset` 不写 review_logs，仅更新 reviews；审计写结构化日志。按 AGENTS “所有自动化可见”这是用户手动，不是自动化。可接受。
- 该端点是否必要？任务要求三档流转；保留，但说明不写 FSRS 日志。

错误：`40000`、`40100`、`40300`、`40404`、`50000`。

字段回溯：`reviews.state/fsrs_state/due_at/stability/difficulty/reps/lapses`。

### 6.9 `GET /api/practice/today`（implemented）

用途：今日启动台数字；一屏一句，不做仪表盘。

响应：

```json
{
  "date": "2026-10-24",
  "timezone": "Asia/Shanghai",
  "dueCount": 5,
  "doneCount": 2,
  "goal": 3,
  "correctRate": 50,
  "streakDays": 12
}
```

规则：`date` 是用户本地日期；`dueCount`/`doneCount` 按本地日界；`streakDays` 由 `attempts` 或 `user_daily_stats` 派生（M2 查 attempts，M3 查聚合）；`correctRate` 是今日有分数提交中全对占比，今日无判分提交时为 `null`。

错误：`40100`、`50000`。

字段回溯：`users.timezone/daily_goal`；`reviews.due_at`；`attempts.created_at`；`streakDays` 派生。

### 6.10 `GET /api/practice/reviews/overload`（implemented）

用途：积压降载状态；到期数超过阈值时客户端只推最紧急 `pushLimit` 道，其余可顺延（所有降载可见、可撤销、有理由）。

响应：

```json
{
  "dueCount": 9,
  "threshold": 20,
  "pushLimit": 5,
  "overload": true
}
```

错误：`40100`、`50000`。

字段回溯：`reviews.due_at` 派生；阈值/推送上限为服务端配置（当前 20/5，与本地引擎 8/5 不同，登录态页面以本端点为准）。

### 6.11 `POST /api/practice/reviews/postpone`（implemented）

用途：顺延到期卡——保留最早到期的 `pushLimit` 道，其余整体推迟一天；用户手动动作，不写 `review_logs`，理由进结构化日志。

请求：`{ "reason": "今天没时间" }`（可选；无请求体时用缺省理由）。

响应：

```json
{ "postponed": 4, "kept": 5 }
```

错误：`40100`、`50000`。

字段回溯：`reviews.due_at/scheduled_days` 派生。

---

## 7. Analytics（implemented）

### 7.1 `GET /api/analytics/accuracy`（implemented）

请求：`from`、`to`（用户本地日期，缺省最近 14 天）、`bankId`（可选）、`mode`（可选）。

响应（含 D8 扩展 `daily14`/`recent`）：

```json
{
  "from": "2026-10-01",
  "to": "2026-10-24",
  "totalAttempts": 100,
  "correctRate": 72.5,
  "averageScore": 81.3,
  "byMode": [
    { "mode": "CLOZE", "attempts": 80, "correctRate": 75.0 },
    { "mode": "FREE", "attempts": 20, "correctRate": 62.5 }
  ],
  "daily14": [
    { "date": "2026-10-11", "attempts": 6, "correctRate": 66.7 }
  ],
  "recent": [
    {
      "attemptId": "3f0e...",
      "sentenceId": "01J...",
      "mode": "CLOZE",
      "kind": "FIRST",
      "score": 80,
      "createdAt": "2026-10-24T05:30:00Z"
    }
  ]
}
```

规则：

- 口径为「最新判分的有分数记录」；区间内无判分时 `correctRate`/`averageScore` 为 `null`（页面按 0 展示）。
- `daily14` 补零输出完整 14 天（`end-13..end`，用户本地日界）；每档无判分时 `correctRate=null`。
- `recent` 按提交时间倒序最多 10 条；`score` 为该提交最新判分（REVEAL 为 `null`）；**不含中文句**，展示层用题库缓存 join（前端 `ensureCachedSentences`）。

字段回溯：派生 `attempts` + `attempt_gradings`；`user_daily_stats` M3 聚合。

### 7.2 `GET /api/analytics/error-tags`（implemented）

请求：`from`、`to`（用户本地日期，缺省最近 14 天）。

响应：

```json
{
  "from": "2026-10-01",
  "to": "2026-10-24",
  "items": [
    { "tag": "搭配", "count": 18, "ratio": 42.9 },
    { "tag": "副词结构", "count": 12, "ratio": 28.6 }
  ]
}
```

字段回溯：派生 `attempt_gradings.result.annotations[].tag`；已 DISPUTE 的批注不计。

---

## 8. Admin

### 8.1 `GET /api/admin/users`（implemented，011-a）

用途：用户列表；单人后台“找到人”。

请求：`page`、`size`、`keyword`（用户名/邮箱）、`role`、`status`（`active`/`disabled`/`deleted`）。

响应 `PageResponse<AdminUserView>`：

```json
{
  "items": [
    {
      "id": "2",
      "username": "learner",
      "email": "learner@example.com",
      "role": "USER",
      "enabled": true,
      "deleted": false,
      "createdAt": "2026-10-01T00:00:00Z",
      "lastLoginAt": "2026-10-24T05:00:00Z",
      "disabledReason": null
    }
  ],
  "page": 1,
  "size": 20,
  "total": 1,
  "totalPages": 1,
  "hasNext": false
}
```

错误：`40100`、`40300`、`40004`、`50000`。

字段回溯：`users.*`；`deleted` 派生 `deleted_at`。

### 8.2 `PATCH /api/admin/users/{id}/status`（implemented，011-a）

用途：停用/恢复账号；停用必填原因。

请求：

```json
{ "enabled": false, "reason": "用户申请" }
```

规则：不能停用自己；不能停用最后一个 ADMIN；启停统一写 `admin_audit_log`（action=`USER_STATUS_CHANGE`，reason/target 随审计记录）。停用后 refresh 校验拒绝（账号 enabled=false），access token 到期自然失效（无黑名单，契约 §3.7）。

响应：`AdminUserView`；错误 `40000`、`40100`、`40300`、`40400`、`40900`、`50000`。

字段回溯：`users.enabled/disabled_reason/disabled_at`；`admin_audit_log.*`；Spring Session 删除。

### 8.3 `PATCH /api/admin/users/{id}/role`（implemented，011-a）

用途：变更角色；不能降级自己/最后一个 ADMIN。

请求：`{ "role": "ADMIN", "reason": "团队调整" }`。

响应：`AdminUserView`；错误 `40000`、`40100`、`40300`、`40400`、`40900`、`50000`。

字段回溯：`users.role`；`admin_audit_log.*`。

### 8.4 `GET /api/admin/audit-logs`（implemented，012）

用途：操作日志检索；只读，不做状态流转，`admin_audit_log` 原样出（无 `detail` JSON 列，参数与请求体不进检索页）。

请求：`page`（1 起，默认 1）、`size`（默认 20，**上限 100，越界归 100**）、`keyword`、`module`、`action`、`actorId`、`from`、`to`。

- `keyword` 对 `action/actorName/targetType/targetId/reason` 拼串 contains（大小写不敏感；SQL LIKE 通配符已转义，检索语义为纯 contains）。
- `module`/`action` 精确匹配，`actorId` 为数字（非数字 `40000`）。
- `from`/`to` 为 `YYYY-MM-DD`（`to` 含当日）；**缺省兜底近 30 天**——`admin_audit_log` 是按月分区表，WHERE 恒含 `created_at` 范围。
- 排序 `created_at` 倒序、同刻按 `id` 倒序保证分页稳定。

响应 `PageResponse<AdminAuditLogView>`：

```json
{
  "items": [
    {
      "id": "100",
      "module": "admin",
      "actorId": "1",
      "actorName": "admin",
      "actorRole": "ADMIN",
      "clientIp": "127.0.0.1",
      "action": "USER_DISABLE",
      "targetType": "USER",
      "targetId": "2",
      "reason": "用户申请",
      "outcome": "SUCCESS",
      "durationMs": 12,
      "createdAt": "2026-10-24T05:30:00Z"
    }
  ],
  "page": 1,
  "size": 20,
  "total": 1,
  "totalPages": 1,
  "hasNext": false
}
```

> 原 planned 版本列出的 `targetType`/`targetId` 查询参数不实现（检索走 keyword 拼串 contains，与前端 AuditPage 一致）。

错误：`40000`（from/to/actorId 非法）、`40004`（page/size 越界）、`40100`、`40300`、`50000`。

字段回溯：`admin_audit_log.*`。

### 8.5 `GET /api/admin/error-logs`（implemented，012）

用途：异常日志检索；显示模块、异常类、traceId、操作者、IP。**列表项剥离 `stackTrace`**（堆栈最长 16KB，只进详情）。

请求：`page`（1 起，默认 1）、`size`（默认 20，**上限 100，越界归 100**）、`keyword`、`module`、`from`、`to`。

- `keyword` 对 `exceptionClass/message/traceId/operator` 拼串 contains（LIKE 通配符已转义）。
- `module` 精确匹配；`from`/`to` 为 `YYYY-MM-DD`（`to` 含当日），**缺省兜底近 30 天**（分区表 WHERE 恒含 `created_at` 范围）。
- 排序 `created_at` 倒序、同刻按 `id` 倒序。

响应 `PageResponse<AdminErrorLogView>`：

```json
{
  "items": [
    {
      "id": "200",
      "traceId": "abc123",
      "module": "practice",
      "exceptionClass": "java.lang.IllegalStateException",
      "message": "boom",
      "requestMethod": "POST",
      "requestPath": "/api/practice/attempts",
      "userId": "2",
      "operator": "learner",
      "clientIp": "127.0.0.1",
      "createdAt": "2026-10-24T05:30:00Z"
    }
  ],
  "page": 1,
  "size": 20,
  "total": 1,
  "totalPages": 1,
  "hasNext": false
}
```

错误：`40000`（from/to 非法）、`40004`、`40100`、`40300`、`50000`。

字段回溯：`error_log.*`。

### 8.6 `GET /api/admin/error-logs/{id}`（implemented，012）

用途：异常详情；返回堆栈与 traceId，供管理员定位。

请求：`id` 路径参数；错误 `40100`、`40300`、`40400`（缺失，与 mock/前端消费一致）、`50000`。

响应 `data`：

```json
{
  "id": "200",
  "traceId": "abc123",
  "module": "practice",
  "exceptionClass": "java.lang.IllegalStateException",
  "message": "boom",
  "stackTrace": "java.lang.IllegalStateException: boom\n\tat ...",
  "requestMethod": "POST",
  "requestPath": "/api/practice/attempts",
  "userId": "2",
  "operator": "learner",
  "clientIp": "127.0.0.1",
  "createdAt": "2026-10-24T05:30:00Z"
}
```

规则：`stackTrace` 只在此详情返回；入库前已做敏感字段遮盖与长度截断；仍不得包含密码/token/完整译文。

字段回溯：`error_log.*`。

### 8.7 `POST /api/admin/attempts/{attemptId}/raw`（planned，M2）

用途：管理员查看答题原文；必须选原因并写审计；不批量导出。

请求：

```json
{ "reason": "评分申诉" }
```

规则：`reason` 必填；写 `admin_audit_log`（action=`VIEW_ATTEMPT_RAW`, targetType=`ATTEMPT`）；返回 `answer` 原文 + 最新 grading；不返回密码/token。

响应：

```json
{
  "attemptId": "3f0e...",
  "userId": "2",
  "sentenceId": "01J...",
  "answer": { "schemaVersion": 1, "mode": "CLOZE", "slots": [] },
  "grading": { "id": "100", "score": 80, "result": {} },
  "auditId": "101"
}
```

错误：`40000`（缺原因）、`40100`、`40300`、`40403`、`50000`。

字段回溯：`attempts.answer`、`attempt_gradings.*`、`admin_audit_log.*`。

### 8.8 `/api/admin/banks`、`/api/admin/sentences`（implemented，011-b）

题库视图复用 §5.1 的 `BankView` 形状（id 字符串化/ownerId/name/description/visibility/status/version/sentenceCount）， 管理端不附加 `deleted` 字段（回收站以 `status=RECYCLED` 表达）；题目视图复用 §1.6 的 `SentenceView` 形状， 管理端列表恒带完整快照（回收站行不做 tombstone 裁剪，供恢复预览），跨题库列表额外带 `bankId`（前端 `AdminSentence`）。 visibility/status 服务端白名单校验，非法值回 `40000`。

- `GET /api/admin/banks`：管理端题库列表，含 DRAFT/DISABLED/RECYCLED；query `keyword/visibility/status/page/size`， keyword 匹配名称与说明；按 id 倒序（新建在前）；`PageResponse<BankView>`。
- `GET /api/admin/banks/{bankId}`：题库详情（含回收站）；不存在回 `40401`。
- `POST /api/admin/banks`：新建题库；请求 `{name(1–128), description?(≤500), visibility}`；owner 为空（官方题库）、初始 DRAFT、版本 0；响应 `BankView`。审计 `BANK_CREATE`。
- `PATCH /api/admin/banks/{bankId}`：部分更新 `{name?/description?/visibility?/status?/reason?}`，null 字段不修改（record 反序列化不区分缺省与显式 null——清空说明请传空串）；status 流转含恢复语义（移出回收站清 `deleted_at`）；reason 可选，仅作审计。审计 `BANK_UPDATE`。
- `DELETE /api/admin/banks/{bankId}`：软删进回收站；请求 `{reason(1–255 必填)}`；响应 `ApiResponse<Void>`（HTTP 200，`data=null`）；不硬删题目。审计 `BANK_DELETE`。
- `GET /api/admin/banks/{bankId}/sentences`：题库内管理端题目列表，含 DRAFT/RECYCLED；query `keyword/status/page/size`；`PageResponse<SentenceView>`。
- `GET /api/admin/sentences`：跨题库题目列表；query `keyword/bankId/level/status/page/size`， keyword 匹配中文/参考译文；**缺省隐藏回收站**，显式传 `status`（含 RECYCLED）时按精确状态过滤；`PageResponse<SentenceView+bankId>`。
- `POST /api/admin/sentences/bulk-status`：请求 `{ids(1–500 去重), status, reason(1–255 必填)}`；响应 `{updated}`。 单事务；不存在的 id 与已处于目标状态的 id 跳过（`updated` 只计实际变更），每条变更按「每题递增」推进所属题库修订号， 草稿题库随内容变更发布（与导入同语义）。审计 `SENTENCE_BULK_STATUS`。
- `POST /api/admin/sentences/bulk-move`：请求 `{ids(1–500 去重), bankId, reason(必填)}`；响应 `{moved}`。 目标题库必须存在且未进回收站（否则 `40401`）；目标库已有相同内容（指纹）时整批拒绝 `40000`； 不存在的 id 与已在目标库的 id 跳过；目标库修订号按「每题递增」推进，源库各推进一次。审计 `SENTENCE_BULK_MOVE`。
- `PATCH /api/admin/sentences/{sentenceId}/status`：停用/恢复/回收；请求 `{status(白名单), reason(1–255 必填)}`；响应 `SentenceView+bankId`。 已处于目标状态时幂等不推进修订号；恢复清 `deleted_at`（回收站恢复语义）。题目缺失回 `40402`（题目不存在， 非题库的 40401）。审计 `SENTENCE_STATUS`。
- `PATCH /api/admin/sentences/{sentenceId}`：行内编辑 `{zh?/level?/tags?/note?/bankId?/status?/reason?}`， null 字段不修改；响应 `SentenceView+bankId`。**字段分两类落地**： zh/level/tags/note 是版本字段——内容快照 `sentence_versions` 不可变（DB 触发器禁止 UPDATE）， 服务端新增 `version_no=最新+1` 的版本行并回填指纹（与导入共用 canonical 算法，改题后再导入同内容仍被去重拦截）与 content_hash（导入同式）， 所属题库按「每题递增」推进修订号，ref/chunks/blank 本端点不可改； bankId/status 是元字段——移动复用批量移动语义（目标库 40401/指纹冲突 40000/目标库每题递增、源库推进一次）， 状态流转含恢复语义、与当前状态相同则不推进。编辑先于移动：移动预检使用回填后的新指纹。审计 `SENTENCE_UPDATE`。

错误：`40000`（字段/ids/枚举非法，zh 需 4–255、note ≤1000）、`40001`、`40004`、`40100`、`40300`、`40401`（题库相关）、`40402`（题目不存在）、`50000`。

字段回溯：`sentence_banks.*`、`sentences.*`、`sentence_versions.*`、`admin_audit_log.*`。

---

### 8.9 `GET /api/admin/overview`（implemented，011-a）

用途：管理端概览聚合；纯读。

响应 `data`：

```json
{
  "metrics": { "totalUsers": 12, "activeToday": 3, "totalAttempts": 456, "estimatedCost": 0 },
  "active7d": [{ "date": "2026-10-31", "count": 2 }],
  "todos": [{ "id": "import", "title": "导入题库", "meta": "uploads 页签", "href": "/admin?tab=banks" }],
  "activity": [{ "id": "9", "who": "admin", "what": "USER_STATUS_CHANGE · user 7", "at": "2026-10-31T02:00:00Z" }]
}
```

口径：`totalUsers`=注册用户总数（含软删）；`activeToday`/`active7d`=当日（UTC 日界）`attempts` 有提交记录的独立用户数；`totalAttempts`=`attempts` 总数；`estimatedCost` 恒 0（无模型调用，011-b 接入）。`todos` 为静态引导项，011-b 接真实待办。`activity` 取 `admin_audit_log` 倒序 5 条，`at` 为 ISO UTC。

字段回溯：`users`/`attempts` 聚合；`admin_audit_log.*`（分区表，MyBatis 只读）。

错误：`40100`、`40300`、`50000`。

### 8.10 `/api/admin/invites`（implemented，011-a）

`POST`（无请求体）：生成一次性邀请码；明文 `code` 只随本响应返回一次，库中只存 SHA-256（与注册核销同算法）与前缀。

响应 `data`：`{ "code": "YL-AB2C-DE3F", "codePrefix": "YL-AB2C", "createdAt": "2026-10-31T03:00:00Z" }`

`GET`：列表（非分页，创建时间倒序，上限 50，不含软删）；只给展示前缀，不给明文。

响应 `data`：`{ "items": [{ "codePrefix": "YL-AB2C", "used": true, "usedBy": "learner", "createdAt": "2026-10-31T03:00:00Z" }] }`

字段回溯：`invite_codes.code_hash/code_prefix/used_count/used_by/created_by/created_at`；`users.username`（回填 `usedBy`）。

写操作审计：action=`INVITE_CREATE`（targetType=invite，targetId=前缀，明文不落日志）。前端邀请码列表展示 `codePrefix`（`lib/schemas/admin.ts` `InviteSummary`）。

错误：`40100`、`40300`、`50000`。

### 8.11 `POST /api/admin/generation/import`（implemented，012）

用途：出题页签把 AI 生成、前端校验通过的句子整批入库；复用 content 导入接缝（`ContentImportService.importSentences`），**指纹去重语义与文件导入完全一致**（批内/库内指纹重复整批 `40904` 回滚），目标题库修订号按「每题递增」推进。

请求：

```json
{
  "bankId": "5",
  "items": [
    { "zh": "他越来越胖。", "ref": "He is getting fatter and fatter.", "note": "主语 + 系表结构；现在进行时描述变化趋势。",
      "level": "CET-4", "tags": ["搭配"],
      "chunks": [{ "zh": "他", "answers": ["He"] }, { "zh": "越来越胖", "answers": ["is getting fatter and fatter"] }],
      "blank": [1] }
  ],
  "reason": "AI 生成导入"
}
```

- `items` 1–500 行，任一行校验失败整批 `40000`（fail-fast，错误带行号）；校验口径与文件导入校验器一致（zh 4–60 字、ref 8–300、note 20–300、chunks 非空且每块至少一个非空答案、blank 下标必须命中语块、tags 1–5），拼接比对按「小写+仅保留字母数字」宽松归一（与出题 Prompt 的「去掉标点后等于 ref」口径一致，即前端 `gen.ts` normLoose）。
- `chunk.index` 可省略，服务端按数组位置补齐；`externalKey` 服务端按 `gen_` + 行号生成。
- 题库不存在或不可导入（已回收/停用）回 `40401`；`bankId` 非数字 `40000`。
- **不创建 upload 批次记录**：批次 id 是随机 UUID，只写入 `sentence_versions.import_batch_id` 供溯源；响应的批次视图仅供前端展示（`originalFilename` 固定「AI 生成.json」），不能走批次回滚——撤销已导入句子走题目状态流转（回收站）。「最近导入」列表（§5.7）只含文件导入批次。

响应 `data`（`ImportBatchView`，与 §5.5 同形状）：

```json
{
  "batchId": "9f0b-…",
  "bankId": "5",
  "status": "COMMITTED",
  "format": "JSON",
  "originalFilename": "AI 生成.json",
  "totalRows": 1,
  "validRows": 1,
  "invalidRows": 0,
  "importedRows": 1,
  "createdAt": "2026-11-02T12:00:00Z",
  "previewedAt": "2026-11-02T12:00:00Z",
  "committedAt": "2026-11-02T12:00:00Z",
  "errorSummary": null
}
```

写操作审计：action=`GENERATION_IMPORT`（targetType=import-batch，targetId=合成批次 id，reason 缺省「AI 生成导入」）。

错误：`40000`（items 空/超 500/行校验/bankId 非数字）、`40001`、`40100`、`40300`、`40401`、`40904`（指纹重复）、`50000`。

字段回溯：`sentences.*`、`sentence_versions.*`、`sentence_banks.version`；批次视图字段为派生（无 upload 批次行）。

### 8.12 `POST /api/admin/audit-events`（implemented，012）

用途：演示动作的通用审计入口——重置密码、导出数据、模型配置、运维吊销等尚无专属写端点的动作，用它在操作日志留下可见、有理由的记录。

请求：

```json
{ "action": "RESET_PASSWORD", "targetType": "USER", "targetId": "7", "reason": "用户忘记密码" }
```

- `action` 必填且必须在白名单内（防任意串污染只增不改的审计表）：`RESET_PASSWORD`、`EXPORT_USER_DATA`、`SESSION_REVOKE`、`MODEL_CONFIG_UPDATE`、`MODEL_TOGGLE`、`PROMPT_UPDATE`（覆盖前端 `appendAuditEvent` 的全部调用点）；越界或缺失回 `40000`。未来动作上线专属端点后从白名单移除。
- `targetType`/`targetId`/`reason` 可空（`RESET_PASSWORD`/`EXPORT_USER_DATA` 前端不传 targetType）。
- 写入行：module=`admin`、outcome=`SUCCESS`、actor=当前管理员，traceId/clientIp/request_* 与 `@OperationLog` 切面同源；`created_at` 由数据库默认值生成。
- 本端点刻意不标 `@OperationLog`：它的产出就是一条审计行（action 随请求体动态变化），再标一次会产生重复行。

响应 `data`：`{ "id": "901" }`（id 字符串化，与前端 `z.string()` 解析契约一致）。

错误：`40000`（action 缺失/越界）、`40001`、`40100`、`40300`、`50000`。

字段回溯：`admin_audit_log.*`。

## 9. 字段回溯对照

> 规则：每个字段必须能落到 `DATA-MODEL.md` 的表列；找不到的标“派生”，并写来源。嵌套对象按 `parent.child` 列。

### 9.1 Account

| 字段 | 来源 | 类型/说明 |
|---|---|---|
| `AuthResponse.accessToken` | 派生 | JWT 签发；无表列 |
| `AuthResponse.tokenType` | 派生 | 固定 `Bearer` |
| `AuthResponse.expiresIn` | 派生 | JWT TTL 配置 |
| `AuthResponse.user.id` | `users.id` | API string |
| `AuthResponse.user.username` | `users.username` | — |
| `AuthResponse.user.role` | `users.role` | — |
| `AuthResponse.user.passwordChanged` | `users.password_changed` | 首次登录强制改密 |
| `RegisterRequest.email` | `users.email` | 唯一 |
| `RegisterRequest.username` | `users.username` | 可派生 |
| `RegisterRequest.password` | `users.password_hash` | 派生 BCrypt |
| `RegisterRequest.inviteCode` | `invite_codes.code_hash` | 派生 SHA-256 |
| `Settings.timezone` | `users.timezone` | IANA |
| `Settings.dailyGoal` | `users.daily_goal` | 1/3/5 |
| `Settings.ratingStandard` | `users.rating_standard` | TEM8/CATTI/IELTS |
| `Settings.desiredRetention` | `users.desired_retention` | 0.80–0.97 |
| `Settings.theme` | `users.theme` | paper/night |
| `Settings.notificationsEnabled` | `users.notifications_enabled` | — |

### 9.2 Content

| 字段 | 来源 | 说明 |
|---|---|---|
| `BankView.id` | `sentence_banks.id` | string |
| `BankView.ownerId` | `sentence_banks.owner_id` | null=官方 |
| `BankView.name/description` | `sentence_banks.name/description` | — |
| `BankView.visibility/status` | `sentence_banks.visibility/status` | — |
| `BankView.version` | `sentence_banks.version` | 增量游标 |
| `BankView.sentenceCount` | 派生 | `COUNT(sentences)` |
| `SentenceView.id` | `sentences.id` | ULID |
| `SentenceView.version` | `sentences.version` | 银行级修订号 |
| `SentenceView.sentenceVersionId` | `sentences.current_version_id` | string |
| `SentenceView.questionVersion` | `sentence_versions.version_no` | — |
| `SentenceView.status` | `sentences.status` | — |
| `SentenceView.deleted` | 派生 `sentences.deleted_at` | tombstone |
| `SentenceView.zh/ref/note` | `sentence_versions.zh/ref/note` | — |
| `SentenceView.chunks/blank/tags` | `sentence_versions.chunks/blank/tags` | JSON |
| `SentenceView.level` | `sentence_versions.level` | — |
| `VersionedResponse.version` | `sentence_banks.version` | — |
| `VersionedResponse.nextSinceVersion` | 派生 | 本页最大 version |
| `ImportBatchView.batchId` | `import_batches.id` | UUID |
| `ImportBatchView.bankId` | `import_batches.bank_id` | string |
| `ImportBatchView.format` | `import_batches.format` | JSON/CSV |
| `ImportBatchView.originalFilename` | `import_batches.original_filename` | — |
| `ImportBatchView.status` | `import_batches.status` | — |
| `ImportBatchView.totalRows/validRows/invalidRows/importedRows` | `import_batches.*` | — |
| `ImportBatchView.createdAt/previewedAt/committedAt` | `import_batches.*` | UTC |
| `ImportBatchView.errorSummary` | `import_batches.error_summary` | JSON |
| `ImportRow.rowNo` | `import_batch_rows.row_no` | — |
| `ImportRow.externalKey` | `import_batch_rows.external_key` | — |
| `ImportRow.status` | `import_batch_rows.status` | — |
| `ImportRow.errors` | `import_batch_rows.error_codes` | JSON |
| `ImportRow.sentenceId/sentenceVersionId` | `import_batch_rows.*` | — |

### 9.3 Practice

| 字段 | 来源 | 说明 |
|---|---|---|
| `AttemptRequest.attemptId` | `attempts.id` | 客户端 UUID |
| `AttemptRequest.sentenceId` | `attempts.sentence_id` | — |
| `AttemptRequest.sentenceVersionId` | `attempts.sentence_version_id` | string |
| `AttemptRequest.mode/kind/retryOfId` | `attempts.*` | — |
| `AttemptRequest.answer` | `attempts.answer` | JSON |
| `AttemptRequest.hintsUsed/durationMs` | `attempts.*` | — |
| `AttemptRequest.clientCreatedAt` | `attempts.client_created_at` | 仅离线 |
| `AttemptResponse.score/maxScore` | `attempt_gradings.score/max_score` | 最新 sequence |
| `AttemptResponse.result` | `attempt_gradings.result` | JSON |
| `AttemptResponse.grading.id/sequence/grader` | `attempt_gradings.*` | — |
| `AttemptResponse.grading.normalizerVersion/rubricVersion` | `attempt_gradings.*` | — |
| `AttemptResponse.review.id` | `reviews.id` | — |
| `AttemptResponse.review.state/fsrsState` | `reviews.state/fsrs_state` | — |
| `AttemptResponse.review.dueAt` | `reviews.due_at` | UTC |
| `AttemptResponse.review.stability/difficulty` | `reviews.stability/difficulty` | — |
| `AttemptResponse.review.reps/lapses/scheduledDays` | `reviews.*` | — |
| `AttemptResponse.review.rating` | `review_logs.rating` | 本次排程 |
| `AttemptResponse.reference.ref/note` | `sentence_versions.ref/note` | 派生 |
| `AttemptResponse.nextAction.retryRequired` | 派生 | score<100 && kind=FIRST |
| `AttemptResponse.nextAction.canReveal` | 派生 | 未 REVEAL |
| `AttemptResponse.replayed` | 派生 | 幂等命中 |
| `WrongQuestionView.sentenceId/mode/state/dueAt` | `reviews.*` | — |
| `WrongQuestionView.lastScore` | `attempt_gradings.score` | 最新 |
| `WrongQuestionView.lastAttemptId` | `reviews.last_attempt_id` | — |
| `WrongQuestionView.wrongCount` | 派生 | 最近未全对次数 |
| `WrongQuestionView.zh/tags` | `sentence_versions.*` | 当前版本 |
| `WrongQuestionView.annotations` | `attempt_gradings.result` + `appeals` | 派生/overlay |
| `ReviewView.*` | `reviews.*` | — |
| `Today.dueCount` | 派生 | `reviews.due_at` + 本地日界 |
| `Today.doneCount` | 派生 | `attempts.created_at` + 本地日界 |
| `Today.goal` | `users.daily_goal` | — |
| `Today.correctRate` | 派生 | attempts + gradings |
| `Today.streakDays` | 派生 | attempts 日期序列 |

### 9.4 Admin/Analytics

| 字段 | 来源 | 说明 |
|---|---|---|
| `AdminUserView.id/username/email/role` | `users.*` | — |
| `AdminUserView.enabled/deleted/disabledReason` | `users.*` | `deleted` 派生 |
| `AdminUserView.createdAt/lastLoginAt` | `users.*` | — |
| `AuditLogView.*` | `admin_audit_log.*` | 含 `module`；`detail` 仅详情 |
| `ErrorLogView.*` | `error_log.*` | `stackTrace` 仅详情 |
| `RawAttemptView.answer` | `attempts.answer` | 受控 |
| `RawAttemptView.grading` | `attempt_gradings.*` | — |
| `RawAttemptView.auditId` | `admin_audit_log.id` | — |
| `Accuracy.*` | 派生 | attempts + gradings |
| `ErrorTags.*` | 派生 | `result.annotations[].tag` |

---

## 10. 实现状态总览（2026-10-31）

> 状态分级：**已实现** = 后端真实可用；**前端 mock** = 前端演示层已按本契约实现、服务端待开发（前端代码带 `TODO(mock)` 注释，接口落地后删除）；**未开发** = 契约在案、两侧均未实现。

### 10.1 已实现（后端）

> 接口级测试列对应 `server/src/test/java/io/github/codejinxz/yilian/**` 下的测试类（DoD §6 强化条款）：
> `*ControllerTest` 为 WebMvcTest 切片（真实 `SecurityConfig`，锁鉴权边界/参数校验/错误码映射/信封形状），
> `AuthFlowIntegrationTest` 为 RANDOM_PORT 真实 HTTP（认证链路专用）；两者都断言统一 `ApiResponse` 信封。

| 组 | 端点 | 说明 | 接口级测试 |
|---|---|---|---|
| Account | `GET /api/auth/csrf`、`POST /api/auth/login`、`POST /api/auth/refresh`、`POST /api/auth/logout`、`POST /api/auth/register`、`GET /api/auth/me`、`POST /api/auth/password` | 双 token、CSRF 轮换、邀请码注册；`passwordChanged` 已返回 | `AuthFlowIntegrationTest`（真实 HTTP：登录/刷新/登出/me 全链路、注册即登录与重复邮箱 40901、改密吊销会话、错误密码 40101、缺 CSRF 40301、伪造 token 401、非管理员 403）；限流口径另由 `AuthRateLimitAnnotationTest` 锁定 |
| Account | `POST /api/auth/guest-upgrade` | 游客本机记录批量迁移：≤100 条/批、同 id 同载荷 skip、异载荷 40903 整体回滚；只落 attempts/attempt_gradings，不触发排程 | `AuthGuestUpgradeControllerTest`（切片：匿名 401/信封/限流注解） |
| Account | `GET /api/me/settings`、`PUT /api/me/settings` | 主题/目标/评分标准/时区/保留率等 | `MeControllerTest`（切片：匿名 401、信封、40000 字段明细、40001、首登 40303）；DTO 注解层另由 `UserSettingsRequestValidationTest` 锁定 |
| Content | `GET /api/banks`、`GET /api/banks/{bankId}`、`GET /api/banks/{bankId}/sentences` | PUBLIC_OR_USER：匿名仅公开题库；句子为 §5.3 增量游标契约 | `BankControllerTest`（切片：匿名放行与私有隔离、40004/40005/40401、tombstone 信封） |
| Practice | `GET /api/practice/next`（D7 参数见 §6.1 差异表）、`POST /api/practice/attempts`（幂等 + norm-1/rubric-1 判分 + SRS 排程）、`GET /api/practice/attempts/{attemptId}`、批注 `appeal`/`dispute`（§6.4/§6.5）、`GET /api/practice/wrong-questions`、`GET /api/practice/reviews`、`POST /api/practice/reviews/{reviewId}/reset`、`GET /api/practice/reviews/overload`、`POST /api/practice/reviews/postpone`、`GET /api/practice/today` | 练习主链路（#8 波次三）；幂等口径 §6.2；复习卡一句一卡（DATA-MODEL §5.11 Q1） | `PracticeControllerTest`（切片：全部 10 端点——匿名 401、mode 取值 40000、分页 40004、40903/40403/40404、信封形状、限流注解） |
| Analytics | `GET /api/analytics/accuracy`（含 `daily14`/`recent` 扩展）、`GET /api/analytics/error-tags` | 用户时区日界；disputed 批注排除 | `AnalyticsControllerTest`（切片：匿名 401、§7.1/§7.2 信封、参数透传、非法日期 40000、空 items=[]） |
| Upload | `POST /api/admin/uploads/preview`、`POST /api/admin/uploads/{batchId}/commit`、`GET /api/admin/uploads/{batchId}`、`GET /api/admin/uploads`、`POST /api/admin/uploads/{batchId}/rollback` | 导入 preview→commit→rollback（波次二）；§5.4–§5.8 | `ImportBatchControllerTest`（切片：匿名 401/非管理员 403、multipart 绑定与缺参 40000、缺请求体 40001、40405/40904、PageResponse 信封） |
| Admin | `GET /api/admin/overview`、`GET /api/admin/users`、`PATCH /api/admin/users/{id}/status`、`PATCH /api/admin/users/{id}/role`、`POST /api/admin/invites`、`GET /api/admin/invites`（011-a） | 概览聚合（活跃=attempts 当日去重用户，UTC 日界）、用户检索分页、启停/改角色（审计经 `OperationLogContext`）、邀请码生成（明文一次性）/前缀列表 | `AdminOverviewControllerTest`、`AdminUserControllerTest`、`AdminInviteControllerTest`（切片：匿名 401/非管理员 403、信封与 PageResponse 形状、reason 越界 40000、40001、审计上下文断言）；service 分支另由 `UserAccountServiceImplTest`（自己停自己/自己降自己/邀请码哈希落库）、`AdminOverviewServiceImplTest`（补 0 与活动组装）覆盖 |
| Admin | `GET/POST /api/admin/banks`、`GET/PATCH/DELETE /api/admin/banks/{bankId}`、`GET /api/admin/banks/{bankId}/sentences`、`GET /api/admin/sentences`、`POST /api/admin/sentences/bulk-status`、`POST /api/admin/sentences/bulk-move`、`PATCH /api/admin/sentences/{sentenceId}/status`、`PATCH /api/admin/sentences/{sentenceId}`（011-b） | 题库检索/新建/详情/部分更新/软删（状态流转 RECYCLED，恢复清 `deleted_at`）、题库内与跨题库题目检索（跨题库缺省隐藏回收站）、批量状态/移动（单事务；不存在的 id 与无变化 id 跳过、计数如实返回；每题递增银行修订号；移动预检指纹冲突 40000）、单题状态流转（幂等不推进）、行内编辑（zh/level/tags/note 走新版本行并回填指纹/content_hash，bankId/status 走元字段；编辑先于移动，预检用新指纹） | `AdminBankControllerTest`、`AdminSentenceControllerTest`（切片：匿名 401/非管理员 403、信封与 PageResponse/SentenceView 形状、reason/ids/zh 校验 40000、缺体 40001、40401/40402、审计上下文断言）；批量与列表/编辑语义另由 `ContentAdminServiceImplTest`（每题递增版本、悲观锁按题库 id 升序、跳过语义、恢复清软删、版本字段新版本行、非法枚举 40000/分页 40004）覆盖 |
| Admin | `GET /api/admin/audit-logs`、`GET /api/admin/error-logs`、`GET /api/admin/error-logs/{id}`、`POST /api/admin/generation/import`、`POST /api/admin/audit-events`（012） | 操作日志/异常日志检索（分区表强制时间兜底：from/to 缺省近 30 天；size 上限 100 越界归 100；keyword LIKE 转义纯 contains；异常列表剥离 stackTrace、详情含堆栈缺失 40400）、出题导入（复用 content 导入接缝，指纹去重 40904 整批回滚，行校验与文件导入同口径、拼接比对按前端 normLoose 宽松归一，不建 upload 批次）、审计事件（动作白名单防污染，返回字符串 id） | `AdminAuditLogControllerTest`、`AdminErrorLogControllerTest`、`AdminGenerationControllerTest`、`AdminAuditEventControllerTest`（切片：匿名 401/非管理员 403、信封与 PageResponse/批次视图形状、stackTrace 剥离与详情、审计上下文/字符串 id 断言）；服务层分支另由 `AdminLogServiceImplTest`（时间兜底/转义/size 归一/40000/40400）、`AdminGenerationServiceImplTest`（行校验 fail-fast/接缝透传/40904 上抛）、`AdminAuditEventServiceImplTest`（白名单/行形状）覆盖；题库「空串清空说明」契约由 `ContentAdminServiceImplTest` 补测（012 AC-5） |
| 运维 | `/actuator/health` | 非业务 | `AuthFlowIntegrationTest`（healthIsPublicAndUp） |

### 10.2 过渡实现（不纳入冻结契约）

（无——§10.2 原列的 `upload → PROCESSING → 轮询 → resume` 过渡端点已随导入切片下线，`preview → commit` 为主链路。）

命名决策（RESEARCH 2026-10-24）：模块/页面/路径**保留 `upload` 命名**（不改 imports）；数据表现名 `import_batches`，表名是否回改由导入切片定夺。

### 10.3 前端 mock（服务端待开发，演示层已按契约实现）

| 组 | 覆盖端点 |
|---|---|
| Admin（§8） | `attempts/{id}/raw`、模型/运维演示页签与出题页「用当前模型生成」演示样本（011-a 后概览/用户管理/邀请码、011-b 后题库管理与题目列表/批量/单题状态/行内编辑、012 后审计/异常检索与出题导入/audit-events 为真实接口；mock 的审计行已随真实契约去掉 targetLabel，出题导入 audit-events 动作走服务端白名单） |

说明：练习数据链路在真实模式下按登录态分流——**游客**由前端本地引擎承担（IndexedDB，RESEARCH 2026-10-24 增补），`lib/api.ts` 的 `LOCAL_ROUTE_GROUPS`（`mode:"guest"`）只对未登录访客本地应答、游客全链路零网络写请求；**登录用户**的 practice/analytics 请求直达服务端（波次四切换，冷启动竞态由 auth-state 探针 + Query 失效兜底）。mock 模式（`VITE_USE_MOCK=true`）仍优先于一切。

### 10.4 未开发（契约在案）

- §6.1 `sentenceId`/`errors`/`excludeIds`/`tag` 四个查询参数的服务端实现（M2.1，D7）。
- Admin 剩余写接口（§8.7 `attempts/{id}/raw` 查看原文、§8 模型配置；审计/异常检索与出题导入/audit-events 已随 012 落地，用户管理与概览/邀请码已随 011-a 落地，题库管理与题目检索/批量/单题状态/行内编辑已随 011-b 落地）。模型/运维页签保留演示态（012 观察项，随模型域切片真实化）。
- 深度批改（M3）、FSRS 排程真值（V2 字段已预留，M2.1 换函数体）、申诉 LLM 裁决（M3）。

### 10.5 待收口

- 登录「用户名或邮箱」语义：服务端需先查 `username` 再查 `email`。
- 部分错误码待补齐：40302/40303、40404–40405、40901–40905、41300、42200（40004/40005 已随 banks 落地；40402/40403/40903 已随 practice 落地）。
- `docs/openapi.yaml` 导出（§12）。

---

## 11. 变更记录

| 日期 | 版本 | 变更 | 作者 |
|---|---|---|---|
| 2026-10-24 | v0.1 | 初稿：全局约定、鉴权矩阵、P0 端点、字段回溯、过渡说明 | 技术负责人 |
| 2026-10-24 | v0.1.1 | 补异常详情端点、审计 `module` 筛选、设置端点与题库详情 | 技术负责人 |
| 2026-10-25 | v0.2 | §10 重写为实现状态总览（已实现/前端 mock/未开发/待收口）；登记 upload 命名决策 | 技术负责人 |
| 2026-10-31 | v0.3 | T18 文档收口：§6/§7/§4.6–4.9/§5.1–5.3 状态改 implemented；§6.1 补 D7 参数差异表；§6.2 补 payloadHash 幂等口径；§6.4/§6.5 补 appeal/dispute 实现口径；新增 §6.10/§6.11（overload/postpone）；§7.1 补 daily14/recent 扩展；§10 矩阵逐行更新 | 技术负责人 |
| 2026-11-01 | v0.4 | 011-b 内容域管理接口：§8.8 改 implemented 并补 `GET /api/admin/sentences` 与 bulk-status/bulk-move 语义（跳过缺失/无变化并暴露计数、每题递增修订号、恢复清 `deleted_at`、移动指纹预检）；§3 矩阵、§10.1/10.3/10.4 同步 | 实现工程师 |
| 2026-11-01 | v0.5 | 011-b 修复轮：§8.8 补 `PATCH /api/admin/sentences/{sentenceId}/status`（reason 必填、幂等不推进、40402）与 `PATCH /api/admin/sentences/{sentenceId}`（行内编辑：zh/level/tags/note 走新版本行+指纹回填，bankId/status 走元字段）实现语义；§3 矩阵、§10.1/10.3/10.4 同步 | 实现工程师 |
| 2026-10-31 | v0.3.1 | DoD 接口级测试补全：§10.1 新增「接口级测试」列（`AnalyticsControllerTest`、`ImportBatchControllerTest`、`MeControllerTest` 新建；`PracticeControllerTest` 补 next/getAttempt/reviews/reset/overload/postpone；`AuthFlowIntegrationTest` 补 register/password 真实 HTTP），矩阵与测试一一对应 | 测试工程师 |
| 2026-11-01 | v0.3.2 | 011-a 管理端用户域落地：§8.1–8.3 改 implemented（§8.2 审计 action 修正为 USER_STATUS_CHANGE、refresh 会话语义对齐实现），新增 §8.9 概览与 §8.10 邀请码（GET 列表改回 codePrefix，明文仅 POST 一次性返回）；§10.1 矩阵补 Admin 行（AdminOverview/User/Invite ControllerTest + UserAccountServiceImplTest/AdminOverviewServiceImplTest） | 实现工程师 |
| 2026-11-02 | v0.6 | 012 管理端收尾波次：§8.4–8.6 改 implemented（检索语义落地：分区表 from/to 缺省兜底近 30 天、size 越界归 100、keyword LIKE 转义、异常列表剥离 stackTrace、详情缺失 40400、原 planned 的 targetType/targetId 查询参数不实现）；新增 §8.11 出题导入（复用 content 导入接缝、指纹去重 40904、不建 upload 批次、审计 GENERATION_IMPORT）与 §8.12 audit-events（动作白名单、返回字符串 id）；§3 矩阵、§10.1/10.3/10.4 同步；题库「空串清空说明」契约随 AC-5 真实生效（§8.8 既有描述落地，前端不再把空说明归一为 null） | 实现工程师 |

---

## 12. OpenAPI 导出约定

- `docs/openapi.yaml` 只导出 `implemented` 端点；`planned` / `transitional` 不出现。
- 每个 `@Operation`/`@Schema` 与本文语义一致；Javadoc 与 OpenAPI 不允许两套说法。
- `ApiResponse<T>` 作为统一信封；错误码用 `ApiCode` 枚举，`oneOf`/`discriminator` 不另造。
- 时间字段 schema `format: date-time`；`date` 仅用于 `Today.date`/`user_daily_stats.stat_date`。
- 字段名严格用本文 camelCase；数据库 snake_case 只在 `DATA-MODEL.md` 出现。
- 导出后做三方对照：端点、字段名、必填项、错误码；结论写进交付总结。
