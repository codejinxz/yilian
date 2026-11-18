# 译练 · API 契约（V1 / M1 + M2）

| 项 | 内容 |
|---|---|
| 版本 | v2.4（对齐文末变更记录最新行，批次头部版本号随变更记录推进） |
| 日期 | 2026-11-19 |
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
| `40304` | 403 | `ASK_NOT_OPEN` | 答疑未开放 | 017：模型凭证已配置但管理员未开启答疑 |
| `40305` | 403 | `TOTP_REQUIRED` | 需要两步验证 | 密码正确但账号已启用 TOTP：`data` 携 5 分钟有效的 `challengeToken`，凭它走 `POST /api/auth/login/totp` 完成第二步（§4.10） |
| `40306` | 403 | `MODEL_BUDGET_EXHAUSTED` | 本月模型预算已用尽，请下月再试或联系管理员调整预算 | 033 预算护栏 100% 强制门：本月已用估算成本 ≥ 月度预算时答疑拒新调用（§6.12）；复核/初裁不走本码（各自既有降级语义，§6.13/§6.4） |
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
| `/api/auth/login/totp` | POST | `PUBLIC` | 登录第二步（challengeToken）；CSRF；限流对齐 login |
| `/api/auth/2fa/**` | GET/POST | `USER` | 两步验证自助（status/setup/activate/disable，§4.10） |
| `/api/me/settings` | GET/PUT | `USER` | 用户设置 |
| `/api/me/export` | GET | `USER` | 本人服务端账号数据导出（036，§4.11） |
| `/api/auth/delete-account` | POST | `USER` | 自助删号：软删 + 30 天冷静期（036，§4.12） |
| `/api/banks` | GET | `PUBLIC_OR_USER` | 公开题库 + 自己的私有题库 |
| `/api/banks/{bankId}` | GET | `PUBLIC_OR_USER` | 题库详情 |
| `/api/banks/{bankId}/sentences` | GET | `PUBLIC_OR_USER` | 增量拉句 |
| `/api/banks/{bankId}/sentences/list` | GET | `PUBLIC_OR_USER` | 练习选题句列表（020） |
| `/api/practice/next` | GET | `USER` | 取下一题 |
| `/api/practice/attempts` | POST | `USER` | 提交 attempt |
| `/api/practice/attempts/{attemptId}` | GET | `USER` | 结果详情 |
| `/api/practice/attempts/{attemptId}/review` | POST | `USER` | 模型复核（024） |
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
| `/api/admin/users/{id}/reset-2fa` | POST | `ADMIN` | 重置两步验证（必填原因 + 审计，§8.2d） |
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
| account | `/api/auth/login/totp` | POST | PUBLIC | M2 | implemented（032，§4.10） |
| account | `/api/auth/2fa/status` | GET | USER | M2 | implemented（032，§4.10） |
| account | `/api/auth/2fa/setup` | POST | USER | M2 | implemented（032，§4.10） |
| account | `/api/auth/2fa/activate` | POST | USER | M2 | implemented（032，§4.10） |
| account | `/api/auth/2fa/disable` | POST | USER | M2 | implemented（032，§4.10） |
| account | `/api/me/settings` | GET/PUT | USER | M1 | implemented |
| account | `/api/me/export` | GET | USER | M1 | implemented（036，§4.11） |
| account | `/api/auth/delete-account` | POST | USER | M1 | implemented（036，§4.12） |
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
| admin | `/api/admin/users/{id}/reset-2fa` | POST | ADMIN | M2 | implemented（032，§8.2d） |
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

限流：`login` 场景 **10 次/60 秒，IP + 账号双维度**（账号取请求体 `username`，2026-11-04 015 修复轮与代码对齐——此前文档/任务稿写 5/min 与实现不符；双维度独立计数，任一超限即 429 并带 `Retry-After`）。

两步验证分支（032）：密码正确且账号 `totp_enabled=true` 时**不建会话不发 token**，返回 **`40305`**（`data` 携 5 分钟有效 `challengeToken`），凭它走 `POST /api/auth/login/totp`（§4.10）完成第二步；challenge 与 access token 分 purpose 互不冒用。

错误：`40000`、`40001`、`40101`、`40301`、`40305`（需要两步验证，§4.10）、`42900`、`50000`。

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

规则：最多 100 条/次；逐条幂等；`kind`/`retryOfId` 规则同 §6.2；已存在同 `attemptId` 同载荷跳过；不同载荷返回 409 并整体回滚。并发同 `attemptId` 竞态（015 修复轮）：整批原子回滚、绝不 500——赢家同载荷回 `40900`（客户端原样重试即可，重试时按「已存在同载荷」跳过）、异载荷回 `40903`。

响应：

```json
{ "imported": 10, "skipped": 2, "conflicts": 0 }
```

错误：`40000`、`40100`、`40900`、`40903`、`50000`。

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

### 4.10 两步验证（TOTP，implemented，032）

TOTP 自研 RFC 6238（HMAC-SHA1、8 位数字、30 秒步长、验证窗口 ±1、常数时间比较），密钥 20 字节
SecureRandom 生成、RFC 4648 Base32 展示；密钥经 SecretCipher（AES-256-GCM，主密钥只走
`YILIAN_SECRET_MASTER_KEY`，与 027B 同纪律）加密落 `users.totp_secret_cipher`。机制对所有登录用户开放，
公网硬前置面是管理员（AGENTS §3.7）；本批不渲染二维码图片（需前端新依赖），验证器走「手动录入密钥」
或复制 otpauth URI 文本。

**登录两步流**：`POST /api/auth/login` 密码正确且 `totp_enabled=true` 时不建会话，返回
**`40305`**（`data.challengeToken` + `data.expiresIn=300`，5 分钟 TTL purpose 限定 challenge JWT，
复用 JwtTokenService 签发、过滤器链拒收带 purpose 的 token——challenge 不是凭据）。客户端凭
challengeToken 完成第二步；challenge 过期/ purpose 不符统一 `40101`「登录已过期，请重新登录」；
挑战有效但账号在两步间被停用/软删时按 `40100` 收敛（不走 40101，与「凭据失效」区分）。

#### `POST /api/auth/login/totp`（PUBLIC，CSRF）

请求：`{"challengeToken": "...", "code": "12345678"}`（`code` 为 8 位数字，同时接受 TOTP 动态码与
一次性恢复码；服务端先验 TOTP 再逐条比对未用恢复码）。

成功（200）：data 为 §4.2 同形 `AuthResponse`；恢复码登录时额外携带
`remainingRecoveryCodes`（剩余可用恢复码数，普通登录/刷新为 null）——恢复码使用即落
`user_2fa_recovery_codes.used_at`（append-only：不删行不重置），剩余数随响应告知。

错误：`40000`（验证码非 8 位数字/挑战缺失）、`40100`（挑战有效但账号在两步间被停用/软删——
`AUTH_REQUIRED` 收敛，回到完整登录）、`40101`（挑战无效/过期/动态码错误，统一文案不区分原因；
恢复码被并发消费时为「恢复码无效或已使用」）、`40301`、`42900`。

限流：`login-totp` 场景 **10 次/60 秒，IP + 账号双维度**（账号槽由 challengeToken 键控——挑战经密码步
签发且与用户一一绑定，请求体没有用户名字段）。

#### `GET /api/auth/2fa/status`（USER）

响应：`{"enabled": true, "enrolledAt": "2026-11-18T00:00:00Z", "remainingRecoveryCodes": 9}`。
响应不含任何密钥材料。错误：`40100`、`50000`。读端点不挂限流（仓库纪律）。

#### `POST /api/auth/2fa/setup`（USER，CSRF）

请求：`{"password": "当前密码"}`（密码确认防会话劫持后静默开启）。

响应（200）：`{"secret": "<Base32 32 字符>", "otpauthUri": "otpauth://totp/Yilian:{username}?secret=…&issuer=Yilian&algorithm=SHA1&digits=8&period=30"}`
——**密钥明文与 URI 仅随本响应出现一次**；密文落库、`totp_enabled` 不动；重复调用覆盖旧密文
（旧验证器绑定密钥即作废），激活时校验密文未被并发覆盖。

错误：`40000`、`40100`、`40101`（当前密码不正确）、`42900`。
限流：`totp-setup` 场景 **5 次/60 秒（账号）**。审计：`@OperationLog(module=account, action=TOTP_SETUP)`。

#### `POST /api/auth/2fa/activate`（USER，CSRF）

请求：`{"code": "12345678"}`（验证器当前动态码）。

响应（200）：`{"recoveryCodes": ["八个8位数字" ×10]}`——**10 个一次性恢复码明文仅随本响应出现一次**，
库内只存 BCrypt 哈希（`user_2fa_recovery_codes`，零物理外键）；通过后 `totp_enabled=true` +
`totp_enrolled_at` 留痕。错误：`40000`（未 setup）、`40100`、`40101`（验证码不正确）、`42900`。
限流：`totp-activate` 场景 **5 次/60 秒（账号）**。审计：`TOTP_ACTIVATE`。

#### `POST /api/auth/2fa/disable`（USER，CSRF）

请求：`{"password": "当前密码", "code": "12345678"}`（密码 + 当前动态码双确认；动态码也接受剩余
恢复码——验证器丢失时的自助逃生通道）。

响应（200）：`{"code":0,"message":"两步验证已关闭","data":null}`；三列清回未注册态且恢复码行删除
（凭据投影删除：残留旧码在重新注册后仍可登录，属安全要求而非历史抹除）。
错误：`40000`（未开启）、`40100`、`40101`（密码或验证码不正确）、`42900`。
限流：`totp-disable` 场景 **5 次/60 秒（账号）**。审计：`TOTP_DISABLE`。

#### `POST /api/admin/users/{id}/reset-2fa`（ADMIN，§8.2d）

请求：`{"reason": "必填 1–255 字符"}`。清空目标用户 TOTP 注册（三列 + 全部恢复码）并吊销其全部
refresh 会话（口径镜像 §8.2b 重置密码；允许管理员对自己执行——唯一管理员丢码时的解救通道；
未注册目标为无状态变化的幂等 200，审计照常留痕；目标不存在/已删按 `40400` 收敛）。
响应（200）：`{"code":0,"message":"已重置该用户的两步验证","data":null}`。
错误：`40000`、`40100`、`40300`、`40400`、`50000`。审计：`@OperationLog(module=admin, action=RESET_2FA, targetType=user)`，
原因经 `OperationLogContext` 落库。

`GET /api/admin/users` 行新增 `totpEnabled: boolean` 只读投影（§8.1），不含任何密钥材料。

### 4.11 `GET /api/me/export`（implemented，036）

本人服务端账号数据导出（AGENTS §2.6 允许单用户、禁批量；不做管理员单用户导出端点——那是 P1-05，不在 036 范围）。

**响应是本契约统一信封的唯一显式例外**：HTTP 200 时响应体为**导出 JSON 本身**（可归档的纯数据，不携带
`ApiResponse` 信封），并带 `Content-Disposition: attachment; filename="yilian-export-<UTC日期>.json"`
与 `Content-Type: application/json`。失败路径（401 等）仍返回统一信封。

整包结构（一次拉全，单人量级小）：

```json
{
  "schemaVersion": 1,
  "exportedAtUtc": "2026-11-19T00:00:00Z",
  "account": {
    "id": 7,
    "username": "learner",
    "email": "learner@example.com",
    "createdAtUtc": "2026-11-01T00:00:00Z",
    "settings": { "timezone": "Asia/Shanghai", "dailyGoal": 3, "ratingStandard": "TEM8",
                   "desiredRetention": 0.9, "theme": "paper", "notificationsEnabled": false }
  },
  "attempts": [ /* 本人答题提交（DATA-MODEL §5.8 列投影，answer 为解析后的 JSON） */ ],
  "attemptGradings": [ /* 本人提交关联的全部判分行（§5.9，含复核追加的高序号行） */ ],
  "appeals": [ /* 本人申诉/不认同记录（§5.10） */ ],
  "reviewCards": [ /* 本人复习卡 FSRS 当前投影（§5.11） */ ]
}
```

数据范围：仅本人（按 `user_id` 过滤）；**不含**密码哈希、TOTP 密钥材料、恢复码等凭据投影，不含他人数据
与题库内容（列名与 DATA-MODEL §5.8–§5.11 对齐，camelCase 投影）。匿名读 401（`40100`）；已删账号按
未登录收敛（`40100`）。读接口不标审计。前端入口：设置页删号卡片「先导出我的数据」（下载动作不强制
阻断删号）；本机 IndexedDB 导出（`exportLocalData`）与之互不替代。

### 4.12 `POST /api/auth/delete-account`（implemented，036；USER，CSRF）

请求：`{"password": "当前密码", "totpCode": "12345678"?}`——密码 BCrypt 确认必填；**开启两步验证的用户
必须附**当前 8 位动态码或剩余恢复码之一（镜像 §4.10 disable 双确认）。

成功（200）：软删置位（`deleted_at` 落值、`deleted_by`=本人，与 §8.2c 管理员删同口径）+ 吊销该用户全部
refresh 会话 + 清 TOTP 注册（三列 + 恢复码行——预披露裁决：密钥材料不留给已删账号）；学习记录
（attempts/attempt_gradings/appeals/review_logs）append-only 不动。响应回执：

```json
{ "code": 0, "message": "success", "data": {
  "deletedAtUtc": "2026-11-19T00:00:00Z",
  "anonymizeAtUtc": "2026-12-19T00:00:00Z" } }
```

`anonymizeAtUtc` = `deletedAtUtc` + 30 天（冷静期天数走配置键 `yilian.account.anonymize-grace-days`，
与匿名化日调度 `AccountAnonymizationJob` 同键读取，不漂移）。冷静期语义：**30 天内数据原样保留**
（管理员侧可查、争议期），到期由日调度匿名化（身份字段处置：username→`deleted_user_<id>`、email→NULL、
password_hash→无效常量、个人化设置回默认、TOTP 三列清；`anonymized_at` 落值，见 DATA-MODEL §5.1/§11）。
**无自助撤销**（删号后登录已不可达；PRD 未要求；管理员可通过既有管理面处置争议）。**被管理员停用（enabled=false）的账号在 access token 余命内仍可自助删号**——数据删除权不因停用状态丧失（合规向裁决，036 R1 登记）；停用不影响删除流程（密码/2FA 双确认照常校验）。

错误：`40000`（缺密码/动态码非 8 位）、`40101`（密码或动态码不正确）、`40900`（**账号已删除**——
幂等口径裁决为显式冲突而非幂等 200，access token 最长 15 分钟余命内的重复提交据此停手；以及
最后一个管理员不可自助删号）、`40100`、`42900`。
限流：`delete-account` 场景 **5 次/60 秒（账号）**。审计：`@OperationLog(module=account, action=SELF_DELETE,
targetType=user)`，reason 固定文案「用户自助删号」。

---

## 5. Content

> 限流（2026-11-04 015 修复轮）：`GET /api/banks**` 三个读端点按 **IP 维度 120 次/60 秒**限流（`banks-read` 场景）——匿名可读入口的滥用防护；限流切面在缓存判定之前执行，被限流的请求不消耗缓存与数据库，放行请求照常走 Caffeine 分层缓存。

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

### 5.3.1 `GET /api/banks/{bankId}/sentences/list`（implemented，020）

用途：练习选题句列表（020 AC-1）——练习页选题面板在题库内按页浏览并逐行携带当前用户的练过态。

与 §5.3 刻意不同形（登记差异）：§5.3 服务 PWA 离线缓存，版本游标 + tombstone；本端点服务选题面板，页码分页 + 动态筛选 + 练过态，已软删行恒不出现（无 tombstone）。

请求：`page`（默认 1，1–10_000）、`size`（默认 20，1–100，与 019 管理端分页口径一致）、`keyword`（可选，LIKE 纯 contains 匹配中文/参考译文）、`level`（可选，精确匹配）、`status`（可选；缺省隐藏 RECYCLED，显式传值时按精确状态过滤）。

响应 `PageResponse<PickerSentenceView>`：

```json
{
  "items": [
    {
      "id": "01J...",
      "zh": "他越来越重视这件事。",
      "level": "TEM8",
      "status": "PUBLISHED",
      "reviewState": "LEARNING",
      "reviewDueAt": "2026-11-06T00:00:00Z"
    }
  ],
  "page": 1,
  "size": 20,
  "total": 48,
  "totalPages": 3,
  "hasNext": true
}
```

规则：

- 可见性与 §5.2/§5.3 同口径：不存在/未发布/已软删题库 `40401`；匿名访问私有库 `40100`（提示登录后可能可见）；登录后非所有者 `40300`。
- 练过态按当前用户 LEFT JOIN 复习卡投影：`reviewState/reviewDueAt` 为 null 表示未练过（匿名恒为 null，即「游客=未学」）。
- 排序 `created_at` 升序、`id` 升序（题库录入序，与 §5.3 增量流一致）；`reviewState` 是 FSRS 三档投影（DATA-MODEL §8.4）。
- 只返回展示与跳转的最小字段；点选后由 §6.1 `next?sentenceId=` 取全量内容（MANUAL 语义）。

错误：`40004`、`40100`、`40300`、`40401`、`50000`。

字段回溯：

| API 字段 | 来源 | 说明 |
|---|---|---|
| `id` | `sentences.id` | ULID |
| `zh/level` | `sentence_versions.*` | 当前快照 |
| `status` | `sentences.status` | 缺省视图不含 RECYCLED |
| `reviewState/reviewDueAt` | `reviews.state/due_at`（按当前用户） | 未练过/匿名时为 null |

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
- **原子性（2026-11-04 015 修复轮）**：内容落库、行回写、状态推进在**同一个**数据库事务内完成，任一步失败整体回滚——不再出现「题目已入库而批次停在 COMMITTING」的死锁状态；并发重复 commit 靠批次行锁串行化，等锁后复检状态。历史版本残留的 `COMMITTING` 批次由定时对账任务按行数据收敛（行已关联题目→补 `COMMITTED`；否则→`FAILED`），对账动作写服务端日志。

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

用途：取下一题；指定句优先，其次错题（`errors=1`），再次到期复习与新题；返回当前 review 状态。

请求：`bankId`（可选）、`mode`（可选 `CLOZE`/`FREE`）、`excludeSentenceId`（可选）、`sentenceId`（可选）、`tag`（可选）、`errors`（可选）、`excludeIds`（可选，逗号分隔）。

D7 参数（契约全量，2026-11-03 M2.1 服务端补齐；与前端本地引擎/mock 的残余差异如实标注）：

| 参数 | 语义 | 服务端 | 本地引擎 / mock |
|---|---|---|---|
| `bankId` | 限定题库 | 支持（DUE 召回不按题库过滤为既有行为；新题与错题池生效） | 支持（DUE 召回同样过滤） |
| `mode` | 模式筛选 | 支持（校验 CLOZE/FREE；Q1 一句一卡下不参与选题） | 支持 |
| `excludeSentenceId` | 单题排除（跳句） | 支持（并进 `excludeIds`） | 支持（并进 `excludeIds`） |
| `sentenceId` | 指定句重练（复习页「重练」），优先级最高；命中且可练直接返回（selection=`MANUAL`，无卡则 `NEW`），忽略 bankId/tag/exclude/errors；已删/不可练静默退回常规取题，不 404 | 支持 | 支持 |
| `errors=1` | 只在错题里取下一题（今日页「错题」chip）；只认字面量 `1`，其余取值静默按 false | 支持（口径=错题列表 §6.6，`dueAt` 升序取最紧急、不看到期；见下方偏差注） | 支持（口径=错题列表） |
| `excludeIds` | 多题排除（热身排除已练） | 支持（逗号分隔；trim/去空/去重，与 `excludeSentenceId` 合并） | 支持（逗号分隔） |
| `tag` | 考点筛选（tags JSON 精确成员匹配） | 支持（到期召回/新题/错题池均生效） | 支持 |

静默归一（与本地引擎一致，非法值不返回 400）：`errors` 只认 `"1"`；空白 `sentenceId`/`tag` 视为未提供；`excludeIds` 内空白段丢弃、重复去重，合并后超过 100 个时只保留先出现的 100 个（URL 长度防御，2026-11-04 015 修复轮明确）。

`bankId` 前置校验（2026-11-04 015 修复轮，黑盒 D-1）：显式提供 `bankId` 且题库不存在/未发布/已软删/匿名访问他人私有库 → `40401`（登录访问他人私有库 → `40300`）；库可见但无题 → 保持 `data=null` 空态。校验在指定句分支之前，`bankId` 与 `sentenceId` 同给且库不可见时也回 `40401`。

服务端与本地引擎/mock 的已知偏差（均按服务端契约优先）：

1. `errors=1` 的错题池与错题列表（§6.6）共用同一口径：看答案（REVEAL）且无错批注的行不进池（本地引擎/mock 会保留该行）。
2. `mode` 不参与选题（Q1 一句一卡，见 `mode` 行）；`bankId` 不约束到期召回（既有行为，本地引擎/mock 会过滤）。

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
- 幂等重复：同载荷返回同一响应，`replayed=true`；不同载荷 40903。**幂等口径（payloadHash）**：参与哈希的字段为 `sentenceId`、`sentenceVersionId`、`mode`、`kind`、`retryOfId`、`answer`、`hintsUsed`；**不含** `durationMs` 与 `clientCreatedAt`（耗时与本机时间不影响「同一次作答」的判定，重放/补交都按同一载荷幂等）。并发同 `attemptId` 双发同样收敛到该口径（2026-11-04 015 修复轮）：数据库主键兜底，输家在独立事务里读回赢家行比对 payloadHash——同载荷按重放返回原结果（`replayed=true`），异载荷 40903，不再出现 500。

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

内嵌 `review` 是**排程投影**：只含上表 `id/state/fsrsState/dueAt/stability/difficulty/reps/lapses/scheduledDays/rating`，**不含** `sentenceId`/`mode`（题目归属由响应顶层 `sentenceId`/`mode` 携带）；复习队列卡的 `sentenceId`/`mode` 字段属于 §6.7 的 `ReviewView`，两者不同形（2026-11-05 020 AC-0 补记：此前前端 schema 误按队列卡形状校验提交响应，导致 200 已入库被误报「提交未确认」）。

### 6.3 `GET /api/practice/attempts/{attemptId}`（implemented）

用途：批改结果详情/刷新恢复；只能看自己的。

响应：同 §6.2 的响应体；错误 `40100`、`40300`、`40403`、`50000`。

字段回溯：同上。

### 6.4 `POST /api/practice/attempts/{attemptId}/annotations/{key}/appeal`（implemented；025 起提交即同步 LLM 初裁）

用途：提交“我觉得也对”。025 起：落库后**同步**调用 LLM 初裁，产出「AI 意见」（decision JSON）落 `appeals` 裁决列，**status 保持 PENDING**，人工终审才改 ACCEPTED/REJECTED（§8.16）。

请求：

```json
{ "proposedAnswer": "attach importance to", "reason": "更书面" }
```

实现口径：`key` 必须存在于该提交最新判分结果的批注列表（`slot:N` / `free:0`），未命中按 40000；归属校验（只能对自己提交）40300；同 `(attemptId, key, kind)` 重复提交 40900（预检 + `uk_appeal_annotation` 唯一约束兜底）；申诉不改判分、不影响统计。

初裁口径（025）：同步调用（复用答疑/复核护栏：超时 15s、maxTokens 2048、重试 1 次），提示词只含题目中文/语块与参考答案结构/本人该槽提交/本地批注理由/用户主张（`proposedAnswer`+`reason`），不喂其他用户数据；输出 strict JSON `{"verdict":"SUPPORT|OPPOSE|UNCERTAIN","reason"(≤300 字)}`。**输出契约双执行点（035，与 §6.13 复核同款）**：系统提示（V13 种子，语义不动）声明契约 + 用户消息末尾追加「输出格式要求」指令块强制（verdict 三值枚举与 reason 上限和解析端一字不差：「只输出一个 JSON 对象本身、无围栏无解释」）——现网网关不生效 top-level system 字段时契约仍经用户消息 wire 级可达（受控实验实证：只写在系统提示的契约模型收不到、回复自由文本、解析必败；尊重 system 的直连部署两处双重强调，无害）。指令块是 builder 代码侧静态文本，不进 `prompt_versions`（无迁移，DATA-MODEL §5.19 注）。成功 → `decision`/`decision_reason`/`decided_by='LLM'`/`decided_at`/`model_snapshot`/`prompt_version_id` 落列；失败/超时/非法输出/限流 → `decision` 落 `{"verdict":"UNAVAILABLE"}` + 占位理由（`decided_by` 保持 NULL=无 AI 意见）；预算用尽（033 预算强制门）→ 跳过初裁，`decision` 落 `{"verdict":"UNAVAILABLE","reason":"BUDGET"}` + 预算占位理由（零模型调用零记账，申诉仍 200+PENDING 不阻塞，读端凭 `reason=BUDGET` 归因）。任何初裁失败都**不阻塞提交**（响应仍 200、status 仍 PENDING）；初裁限流为独立 scene `appeal-adjudication`（5 次/分钟/账号，程序化判定，429 只跳过初裁不拒申诉）。llm_usage `scene='appeal'` 三终态双写。

响应（025 扩展）：

```json
{
  "appealId": "20",
  "status": "PENDING",
  "kind": "APPEAL",
  "annotationKey": "slot:2",
  "verdict": "SUPPORT",
  "decisionReason": "与规范答案语义等价，可以接受",
  "decidedBy": "LLM",
  "decidedAt": "2026-11-06T04:01:00Z"
}
```

回执字段口径（029 对齐，以存储 decision JSON 为真相）：初裁成功四字段为 `SUPPORT/OPPOSE/UNCERTAIN` + AI 理由 + `LLM` + 时间；**初裁不可用（失败/限流/未配置模型）时 verdict=`"UNAVAILABLE"`、`decisionReason`=占位理由「AI 初裁暂不可用，等待人工终审」、`decidedBy/decidedAt` 为 `null`**（与 `decision` 列落的 `{"verdict":"UNAVAILABLE"}` 同源，用户端「AI 初裁暂不可用」展示分支由此驱动）；DISPUTE 回执四字段恒为 `null`。**034 R2-01 窗口竞态口径**：初裁落列 UPDATE 的 WHERE 携带 `status='PENDING'`——终审在初裁外呼窗口内完成时行数=0，初裁结果整体丢弃（终审理由与 decided_by 不被覆盖，占位也不落列），回执**按存储真相投影为终审态**（`status=ACCEPTED/REJECTED`、`decisionReason`=终审理由、`decidedBy=ADMIN`、`decidedAt`=终审时间、verdict=null 不伪造 AI 意见），初裁调用真实发生以 llm_usage `ok=true` 留痕。

错误：`40000`、`40100`、`40300`、`40403`、`40900`、`50000`。

字段回溯：`appeals.*`；`sentence_id/chunk_index` 从 result 批注解析；`decision/decision_reason/decided_by/decided_at/model_snapshot/prompt_version_id` 为 V8 裁决列（025 启用）；`prompt_version_id` → `prompt_versions.id`（V13）。

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

用途：顺延到期卡——保留最早到期的 `pushLimit` 道，其余整体顺延 3 天（2026-11-04 018 AC-6d：与本地引擎 `postponeOverdue(3)` 与原型口径统一，此前登录态固定 1 天属偏差）；用户手动动作，不写 `review_logs`，理由进结构化日志。

请求：`{ "reason": "今天没时间" }`（可选；无请求体时用缺省理由）。

响应：

```json
{ "postponed": 4, "kept": 5 }
```

错误：`40100`、`50000`。

字段回溯：`reviews.due_at/scheduled_days` 派生。

### 6.12 `POST /api/practice/{sentenceId}/ask`（implemented，017）

用途：题目答疑（017，PRD §5.4）——针对当前句的**被动一问一答**，非 AI 外教；不判分、不改批注、不进学习闭环，回答必带免责标注。

上下文（服务端拼装）：题目中文原句 + 考点标签 + 该用户本人最近一次判分的错误批注摘要；**不把参考译文/解析喂给模型**，提示词要求引导思考而非直接给整句译文。用户问题以定界符包裹（≤500 字符），系统提示声明定界区内是数据不是指令（注入面缓解：无工具、无判分权限 + 长度上限 + 限流）。

三态（017 裁决）：

- 模型凭证未配置（口径按 provider 分：openai 轨要求 `YILIAN_LLM_BASE_URL`+`YILIAN_LLM_API_KEY` 都非空；anthropic 轨仅要求 `YILIAN_LLM_BASE_URL` 非空——ZCode 本地网关无鉴权）→ 功能不存在，返回 `40400`；前端隐藏入口。
- 凭证已配置但管理员未开启 → `40304`「答疑未开放」。
- 开启 → 可用。

**provider 目录与三协议（022）**：模型接入首选 provider 目录（CC Switch 式多 provider，§8.15 管理端 CRUD + 激活切换）——激活条目的 `protocol` 选择客户端实现，三协议共用同一三态门禁、降级语义、maxTokens（2048）与超时（15s）护栏（SDK 自动重试一律 0；anthropic 轨另有网关 429 退避重试一次，021 特性）：

- `anthropic-messages`（Anthropic Messages 协议，服务主人的 ZCode 本地网关，GLM 套餐额度）：`baseUrl` 填到网关前缀（如 `http://127.0.0.1:47860/gateway/zcode`），**客户端拼 `/v1/messages`**（请求带 `anthropic-version: 2023-06-01` 头；密钥 `YILIAN_LLM_KEY_<大写ID>` 可空，非空时以 `x-api-key` 头携带）；网关 429/5xx 时退避 30s 重试一次（多账号轮询冷却特性），重试仍败按 50000 降级。
- `openai-chat`（OpenAI Chat Completions，智谱/DeepSeek 等）：`baseUrl` 填到 `/v4` 级前缀（如 `https://open.bigmodel.cn/api/paas/v4`），路径由客户端补齐；密钥必填。
- `openai-responses`（OpenAI Responses API，022 新增，手写 `java.net.http` 客户端——spring-ai 2.0.0 无 Responses 支持）：`baseUrl` 填到 `/v1` 之前的前缀（如 `https://api.openai.com`），**客户端拼 `/v1/responses`**，请求 `{model, max_output_tokens, temperature, input:[{role,content}]}`（system 在首位），响应取 `output[]` 中 `type=message` 项的 `content[].text`（`reasoning` 等思考项不进正文）；密钥必填。

**legacy 单 provider 兼容（022）**：目录为空且 `YILIAN_LLM_*` 环境变量齐备时，视为一个隐式 provider（读路径兼容 017/021 部署）——`YILIAN_LLM_PROVIDER`（`openai` 缺省 | `anthropic`）映射 `openai-chat`/`anthropic-messages`，`YILIAN_LLM_BASE_URL`/`YILIAN_LLM_API_KEY`/`YILIAN_LLM_MODEL`（缺省 `glm-4.7`）沿用。

**思考模型注（022）**：思考型模型（glm-5.3-flash 等）的 `thinking` 块计入 `max_tokens`（实测 `max_tokens=32` 时输出全被 thinking 吃掉、`stop_reason=max_tokens`），故输出上限三协议同源提到 **2048**；anthropic 轨 content 提取只拼 `type=text` 块（Spring AI `getText()` 会把 thinking 块当正文返回，已改为官方 SDK 按块类型过滤，桩测试锁死），openai-responses 轨只取 `type=output_text`。无正文（如 token 被 thinking 吃尽）按「空回复降级」50000 处理。

请求：`{ "question": "这里为什么用 attach importance to？" }`（1–500 字符；`40000` 空白/超长、`40001` 缺请求体）。

响应：

```json
{ "answer": "先比较两个介词的搭配对象……", "disclaimer": "AI 生成，可能有误" }
```

- 预算强制门（033，三态门前置）：已设月度预算（app_settings 键 `llm.budget`）且本月已用估算成本 ≥ 100% 时一律 `40306`「本月模型预算已用尽，请下月再试或联系管理员调整预算」——预算门先于客户端三态与可见性校验，不触达 LLM；未设预算键=护栏不生效（现行为），80% WARN 只留痕不拦截（结构化 WARN 日志 + §8.17 用量视图状态）。
- 限流：`@RateLimit(scene="tutor-ask", ACCOUNT, 5 次/60 秒)`；`42900` 带 `Retry-After`。
- 模型调用超时 15s（`YILIAN_LLM_TIMEOUT`）；网络/超时/供应商错误统一降级 `50000`（文案「答疑暂时不可用，请稍后再试」），不外抛堆栈。
- 可见性复用 016 口径（`accessibleForPractice`）：`40402` 题目不存在/不可练、`40300` 库不可见且无练习史豁免。
- 用量观测（V11 起双写）：token 数/耗时/成败记结构化日志 **并落 `llm_usage` 表**（DATA-MODEL §5.18，V11 建表；017 的「落库待批」项已批）。内部表不暴露任何端点（§10.1 矩阵注），行 append-only 只记元数据，问题与回答内容不入日志也不入库；落库失败只记告警，不阻塞答疑主链路。033 起落库补估算成本列 `estimated_cost_micro_cny`（按当次模型定价推算，微元口径；无定价为 NULL=不计入预算，§8.17 消费）。
- 定级声明：答疑不贴等级、不改 FSRS 排程；会话历史仅存前端组件 state。

字段回溯：派生（LLM 输出）；`disclaimer` 为服务端常量。

### 6.13 `POST /api/practice/attempts/{attemptId}/review`（implemented，024）

用途：模型复核（024，PRD §5.1）——对本人的已判分提交**追加**一条 `grader=MODEL` 的判分记录（append-only），不覆盖首判；回看与统计按最新 sequence 取复核分，FSRS 排程与错题队列维持首判口径不变。

请求：无请求体；路径参数 `attemptId`（提交 UUID）。

响应 `data`（`ModelReviewView`）：

```json
{
  "score": 88,
  "reason": "整体可懂，介词搭配需要调整",
  "modelSnapshot": "zcode:glm-4.7",
  "latencyMs": 640,
  "createdAt": "2026-11-06T04:30:00Z",
  "sequence": 2,
  "originalScore": 61
}
```

规则：

- 归属与可见性复用作答详情（§6.3）口径：提交不存在 `40403`；非本人、或句子不可读（库转私且无练习史豁免，含已删/已回收——复核必须现场取原句/参考译文拼提示词，回看可容忍句子消失而复核不能）`40300`；REVEAL 提交没有判分 `40000`「该提交没有可复核的判分」。
- 幂等（有意取舍，非 409）：同一 attempt 已有 `grader=MODEL` 复核行时直接返回既有结果，**不再调模型**（省 token 防重复计费）。并发复核串行化（029）：整个复核在 `READ_COMMITTED` 事务内先对 attempts 父行 `SELECT ... FOR UPDATE`，后到者在行锁上等到先到者提交后再做幂等检查并按幂等回读返回——模型只调一次、`attempt_gradings` 落一条 MODEL 行；`uk_grading_attempt_sequence` 保留为数据库级兜底（正常并发路径不可达，命中即数据异常，按 `50000` 固定文案降级并整体回滚）。
- 提示词（`ModelReviewPromptBuilder`）：中文原句 + 参考译文 + 语块答案结构 + 本人提交 + 本地错误批注（复核即重新判分，与答疑「不给参考译文」口径刻意不同）；提交内容里的全角【】替换为『』（定界注入面缓解，同 §6.12 手法）。输出契约 strict JSON `{"score": 整数, "reason": "字符串"}`，非空但不可解析或空回复重试 1 次，仍败降级。**输出契约双执行点（035）**：系统提示（V13 种子，语义不动）声明契约 + 用户消息末尾追加「输出格式要求」指令块强制（字段清单与解析端一一对应：「只输出一个 JSON 对象本身、无围栏无解释」）——受控实验实证现网网关不生效 top-level system 字段（只写在系统提示的契约模型收不到、回复自由文本、解析必败），契约必须经用户消息 wire 级可达；尊重 system 的直连部署两处双重强调，无害。指令块是 builder 代码侧静态文本，不进 `prompt_versions`（无迁移，DATA-MODEL §5.19 注）。
- 钳制与截断：`score` 钳制 0–100（模型轻微越界纠偏而非整次降级）；`reason` 按 Unicode 码点截断到 200 字。
- 落行：`sequence=首判+1`、`grader=MODEL`、`model_snapshot=providerId:model`、`latency_ms=模型调用整体墙钟毫秒`（含重试）；`normalizer_version`/`rubric_version` 沿用首判行；`prompt_version_id` 本批不落（025 prompt 版本注册表）。复核行 `result` JSON 承载首判批注结构（score 与 summary 替换为复核值），回看解析与申诉批注定位照常工作。
- 排程与错题不变：复核不写 `review_logs`、不改复习卡；错题池/`wrongCount` 读首判行（`sequence=1`），复核分不参与错题进出池。
- 限流：`@RateLimit(scene="model-review", ACCOUNT, 5 次/60 秒)`；`42900` 带 `Retry-After`。与答疑（`tutor-ask`）同参数不同 scene，配额互不占用。
- 降级形态（镜像 §6.12 的 50000 模式）：无可用客户端（目录空且 legacy 未配置/目录未激活/凭证不齐）`40400` 功能不存在；调用异常、空回复或解析失败（重试 1 次后仍败）统一 `50000` 固定文案「模型复核暂时不可用，请稍后再试」——**不落任何新行**，原判分保留，主链路不阻塞。
- 预算强制门（033）：幂等回读与功能存在性之后、调模型之前判定——本月已用 ≥ 100% 时直接走既有降级语义 `50000`、文案带预算原因（「模型复核暂时不可用：本月模型预算已用尽，请下月再试或联系管理员调整预算」），不调模型不落行；已落复核行的重复请求按幂等回读返回，不受预算影响。80% WARN 不拦截只留痕（§8.17 状态可见）。
- 用量观测：`llm_usage` 双写 `scene='review'`（成功/空回复/异常三终态各记一行；非空但不可解析的回复 `ok=true`——token 消耗真实发生）；落库失败只记告警不阻塞复核主链路（与 §6.12 同纪律）。
- 前端：仅登录态且有服务端判分记录（游客/本地引擎提交的 `grading.id` 为 local 占位）时显示复核入口；成功展示新分 + 理由 + 「复核不影响复习计划」注记并刷新 attempt 查询；失败 toast 保留原分。

错误：`40000`（无可复核判分）、`40100`、`40300`、`40400`（模型未配置）、`40403`、`42900`、`50000`。`40900` 不适用（幂等回读而非冲突拒绝）。

字段回溯：`attempt_gradings`（DATA-MODEL §5.9，`model_snapshot`/`latency_ms` 列 V8 预留本批启用）+ `llm_usage`（DATA-MODEL §5.18）。

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
- `from`/`to` 为用户本地日期，日界按**用户设置的 IANA 时区**换算 UTC 时刻（凌晨 00:30 的记录计入用户本地「今天」）；闭区间跨度上限 **92 天**，超出按 `40000` 拒绝（显式拒绝而非静默截断，2026-11-04 015 修复轮明确）。

字段回溯：派生 `attempts` + `attempt_gradings`；`user_daily_stats` M3 聚合。

### 7.2 `GET /api/analytics/error-tags`（implemented）

请求：`from`、`to`（用户本地日期，缺省最近 14 天；日界按用户时区换算、闭区间上限 92 天，同 §7.1）。

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

### 8.2b `POST /api/admin/users/{id}/reset-password`（implemented，018）

用途：管理员重置用户密码——PRD §13 指定的唯一找回密码路径；替代此前经 `/api/admin/audit-events` 的演示审计（该动作已从白名单移除，防同动作双入口）。

请求：`{ "reason": "用户忘记密码" }`（必填，随审计落库）。

行为：服务端生成 12 位一次性临时口令（去混淆字母表 SecureRandom，与邀请码同风格），BCrypt 落库；`password_changed` 复位为 false——目标用户下次登录走 40303 强制改密（机制复用，无新增状态位）；吊销目标用户全部 refresh 会话。

响应（明文只随本响应返回一次，不落日志不进缓存）：

```json
{ "temporaryPassword": "AbC23456MNPQ", "userId": "3", "username": "learner" }
```

审计：`admin_audit_log` action=`RESET_PASSWORD`（`@OperationLog`，reason/target 随上下文）。

错误：`40000`（reason 缺失）、`40100`、`40300`（自己重置自己——自助改密走 `/api/me/password`）、`40400`（用户不存在/已删）、`50000`。

字段回溯：`users.password_hash/password_changed/password_updated_at`；`admin_audit_log.*`；Spring Session 删除。

### 8.2c `DELETE /api/admin/users/{id}`（implemented，018）

用途：管理员删除账号（软删 30 天冷静期，与原型用户抽屉「删除」对齐；前端此前已按本端点发送请求）。

请求：`{ "reason": "用户申请注销" }`（必填）。

行为：`deleted_at/deleted_by` 置位且 `enabled=false`（软删，不删答题与审计，append-only）；吊销目标用户全部 refresh 会话；最后一个活跃 ADMIN 不可删（与「停用最后一个管理员」同款行锁护栏）。

响应：软删后的 `AdminUserView`（`deleted=true`、`enabled=false`）；错误 `40000`、`40100`、`40300`（自己删自己）、`40400`（用户不存在/**已删再删同码**——软删惯例裁决：与 §8.2「用户已删除」口径一致，幂等 200 会把「目标不存在」伪装成成功并让审计失真）、`40900`（最后一个管理员）、`50000`。

审计：action=`USER_DELETE`。

字段回溯：`users.deleted_at/deleted_by/enabled`；`admin_audit_log.*`；Spring Session 删除。

### 8.2d `POST /api/admin/users/{id}/reset-2fa`（implemented，032）

用途：管理员重置目标用户的两步验证——公网锁死兜底（TOTP 验证器与恢复码全失时解救）；目标用户解救后重新登录走完整密码步并重新注册 2FA（§4.10）。

请求：`{ "reason": "用户恢复码全失" }`（必填 1–255 字符，随审计落库）。

行为：`users.totp_secret_cipher/totp_enabled/totp_enrolled_at` 清回未注册态、`user_2fa_recovery_codes` 全部行删除（凭据投影删除，残留旧码在重新注册后仍可登录属安全漏洞）；吊销目标用户全部 refresh 会话（口径镜像 §8.2b）。与 §8.2b 不同：**允许管理员对自己执行**——唯一管理员丢码时没有他人可救（自助关闭端点需要动态码，锁死时不可用）；未注册 2FA 的目标为无状态变化的幂等 200，审计行照常落库。

响应：`{ "code": 0, "message": "已重置该用户的两步验证", "data": null }`。

审计：`@OperationLog(module=admin, action=RESET_2FA, targetType=user)`，reason 随上下文落库。

错误：`40000`（reason 缺失/超长）、`40100`、`40300`（非管理员）、`40400`（用户不存在/已删）、`50000`。

`GET /api/admin/users`（§8.1）行新增 `totpEnabled: boolean` 只读投影，不含任何密钥材料。

字段回溯：`users.totp_secret_cipher/totp_enabled/totp_enrolled_at`；`user_2fa_recovery_codes` 删行；`admin_audit_log.*`；Spring Session 删除。

### 8.4 `GET /api/admin/audit-logs`（implemented，012）

用途：操作日志检索；只读，不做状态流转，`admin_audit_log` 原样出（无 `detail` JSON 列，参数与请求体不进检索页）。

请求：`page`（1 起，默认 1）、`size`（默认 20，**上限 100，越界归 100**）、`keyword`、`module`、`action`、`actorId`、`from`、`to`。

- `keyword` 对 `action/actorName/targetType/targetId/reason` 拼串 contains（大小写不敏感；SQL LIKE 通配符已转义，检索语义为纯 contains）。
- `module`/`action` 精确匹配，`actorId` 为数字（非数字 `40000`）。
- `from`/`to` 为 `YYYY-MM-DD`（`to` 含当日）；**日界口径（2026-11-04 015 修复轮勘误）**：按管理员本地日换算 UTC 时刻——服务端配置项 `yilian.admin.day-zone`（默认 `Asia/Shanghai`，单产品默认），前端日期选择器发送的是浏览器本地日期，按 UTC 日界换算会在本地凌晨把「今天」的记录筛丢（012 冒烟观察项）；**缺省兜底近 30 天**——`admin_audit_log` 是按月分区表，WHERE 恒含 `created_at` 范围。
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
- `module` 精确匹配；`from`/`to` 为 `YYYY-MM-DD`（`to` 含当日），日界按管理员本地日（`yilian.admin.day-zone`，默认 `Asia/Shanghai`）换算，口径同 §8.4；**缺省兜底近 30 天**（分区表 WHERE 恒含 `created_at` 范围）。
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
- `PATCH /api/admin/sentences/{sentenceId}`：行内编辑 `{zh?/level?/tags?/note?/bankId?/status?/reason?}`， null 字段不修改；响应 `SentenceView+bankId`。**字段分两类落地**： zh/level/tags/note 是版本字段——内容快照 `sentence_versions` 不可变（DB 触发器禁止 UPDATE）， 服务端新增 `version_no=最新+1` 的版本行并回填指纹（与导入共用 canonical 算法，改题后再导入同内容仍被去重拦截）与 content_hash（导入同式）， 所属题库按「每题递增」推进修订号，ref/chunks/blank 本端点不可改； 编辑不改变题目生命周期状态（016 P2·02#03：草稿题编辑后保持 DRAFT，发布必须走显式状态流转， 题库层「草稿随内容变更发布」不受影响）； bankId/status 是元字段——移动复用批量移动语义（目标库 40401/指纹冲突 40000/目标库每题递增、源库推进一次）， 状态流转含恢复语义、与当前状态相同则不推进。编辑先于移动：移动预检使用回填后的新指纹。审计 `SENTENCE_UPDATE`。

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

用途：演示动作的通用审计入口——导出数据、模型配置、运维吊销等尚无专属写端点的动作，用它在操作日志留下可见、有理由的记录。重置密码已迁移到真端点 `POST /api/admin/users/{id}/reset-password`（§8.2b，018），不在本入口白名单内。

请求：

```json
{ "action": "EXPORT_USER_DATA", "targetType": "USER", "targetId": "7", "reason": "用户申请导出" }
```

- `action` 必填且必须在白名单内（防任意串污染只增不改的审计表）：`EXPORT_USER_DATA`、`SESSION_REVOKE`、`MODEL_CONFIG_UPDATE`、`MODEL_TOGGLE`、`PROMPT_UPDATE`（覆盖前端 `appendAuditEvent` 的全部调用点）；越界或缺失回 `40000`。未来动作上线专属端点后从白名单移除。
- `targetType`/`targetId`/`reason` 可空（`EXPORT_USER_DATA` 前端不传 targetType）。
- 写入行：module=`admin`、outcome=`SUCCESS`、actor=当前管理员，traceId/clientIp/request_* 与 `@OperationLog` 切面同源；`created_at` 由数据库默认值生成。
- 本端点刻意不标 `@OperationLog`：它的产出就是一条审计行（action 随请求体动态变化），再标一次会产生重复行。

响应 `data`：`{ "id": "901" }`（id 字符串化，与前端 `z.string()` 解析契约一致）。

错误：`40000`（action 缺失/越界）、`40001`、`40100`、`40300`、`50000`。

字段回溯：`admin_audit_log.*`。

### 8.13 `/api/admin/fsrs`（implemented，014/M2.1）

FSRS 参数版本管理：SGD 优化产出候选、版本与训练摘要、启用状态机。fsrs 属 practice 域，控制器在 practice 模块；权限由 `/api/admin/**` 的服务端 `ROLE_ADMIN` 规则统一把关。

### 8.13.1 `POST /api/admin/fsrs/optimize`

用途：读指定用户的 `review_logs` 训练 21 维候选权重，写 `fsrs_parameter_versions` 的 GLOBAL CANDIDATE 行（单人产品语境：训练数据归属必须显式指定，不做「默认优化自己」的隐式绑定）。

请求：`{ "userId": 7 }`（`userId` 必填；缺失 40000，无 Content-Type 41500）。

- 样本 < 50 条 → `40000`（「复习样本不足 50 条」），不写任何行、不伪造已优化。
- 训练口径：FSRS-6 可召回模型 BCE 损失，全批 Adam（确定性，无随机种子），迭代/学习率固定并落 `metrics`；训练读数（可提取率/记忆状态模拟）直接调用 fsrs 库对象，与 FSRS-6 公式逐值对拍（`FsrsOptimizerTest`）。
- `metrics` 形状见 DATA-MODEL §6.9：`sampleCount`/`lossEvents`/`logLoss`/`rmse`/`optimizer`（`java-sgd-m2.1`）/`optimizerVersion`/`iterations`/`learningRate`/`initialLoss`。
- 审计：`@OperationLog(module=practice, action=FSRS_OPTIMIZE)`。

响应 `data`：`FsrsParameterVersionView`（见 8.13.3）。

错误：`40000`、`40001`、`40100`、`40300`、`41500`、`50000`。

### 8.13.2 `GET /api/admin/fsrs/parameters`

用途：全部版本行，创建时间倒序、id 倒序；含 weights 与 metrics。读接口，不标操作日志。

响应 `data`：`FsrsParameterVersionView[]`。`weights` 是数值数组，**双形态如实输出**（勘误 2026-11-03，原文误写「21 维数组」）：

- 优化器产出的行：21 维纯数组（DATA-MODEL §6.8）；
- V9 原型种子行：底层 weights 列是对象形 `{"w":[18 维],"note":…}`，响应取其 `w` 数组原值输出（18 维原型值，不伪造补齐到 21）。

后续行统一 21 维数组形（优化器写入即此形态）；种子行保持历史原样，不回改数据。

错误：`40100`、`40300`。

### 8.13.3 `POST /api/admin/fsrs/parameters/{id}/enable`

用途：启用候选版本。仅 `CANDIDATE` 可启用（其余状态 `40000`）；未知 id `40400`。同一事务内：旧 GLOBAL ACTIVE 即时置 RETIRED（bulk UPDATE 先落库，避免 `uk_fsrs_active_owner` 函数索引看到双 ACTIVE）、候选置 ACTIVE。调度自下一次排程起消费新权重（四分支公式形状不变，参数换源——GLOBAL 原型种子下与本地引擎常量逐值一致，golden 不变）。

响应 `data`：启用后的 `FsrsParameterVersionView`。

审计：`@OperationLog(module=practice, action=FSRS_ENABLE)`，targetId 由 `OperationLogContext` 动态写入。

`FsrsParameterVersionView` 形状：

```json
{
  "id": 33,
  "algorithm": "FSRS-6",
  "ownerType": "GLOBAL",
  "status": "CANDIDATE",
  "weights": [0.2172, 1.1771, 3.2602, 16.1507, 7.0114, 0.57, 2.0966, 0.0069, 1.5261, 0.112,
               1.0178, 1.849, 0.1133, 0.3127, 2.2934, 0.2191, 3.0004, 0.7536, 0.3332, 0.1437, 0.2],
  "sampleCount": 1200,
  "metrics": {"schemaVersion": 1, "sampleCount": 1200, "lossEvents": 1050, "logLoss": 0.2279,
               "rmse": 0.2544, "optimizer": "java-sgd-m2.1", "optimizerVersion": "1.0",
               "iterations": 300, "learningRate": 0.05, "initialLoss": 0.2557},
  "optimizedAt": "2026-11-03T00:00:00Z",
  "createdAt": "2026-11-03T00:00:00Z",
  "reason": "SGD 优化目标用户 7 的复习日志"
}
```

- `metrics` 为 null（V9 种子行）时响应中缺省；`weights` 双形态见 §8.13.2（种子行 18 维 w 数组，优化器行 21 维数组）。
- 状态机：`CANDIDATE → ACTIVE → RETIRED`；不提供「回滚到旧版本」端点（把旧候选重新训练/走管理流程，M2.1 不做状态回跳）。

字段回溯：`fsrs_parameter_versions.*`。

### 8.14 `/api/admin/llm/config`（implemented，017；022 扩展目录维度；028 legacy 形态全字段可编辑；033 扩预算护栏字段）

答疑 LLM 配置：服务端 `ROLE_ADMIN`（AGENTS §3.7）。**生效值优先级（028）：目录激活条目（022 语义不变，高于一切 legacy 形态）> tutor 覆盖（`app_settings` 键 `llm.tutor` 的 `baseUrl`/`model` + `llm_provider_keys` 预留 id=`tutor` 密钥，目录空时生效）> legacy 环境变量（`YILIAN_LLM_*`）**。027B 起目录条目密钥解析链为 **DB 密文解密（AES-256-GCM，管理端录入）> `YILIAN_LLM_KEY_<大写ID>` env > legacy**；028 起 tutor 覆盖轨密钥链为 **`llm_provider_keys` 预留 id=`tutor` 行密文解密 > `YILIAN_LLM_API_KEY` env > 空**（复用 027B 加密基建，主密钥只走 env `YILIAN_SECRET_MASTER_KEY`）。覆盖与密钥变更经注册表状态指纹即时换轨（无需重启）。契约演进链：017「baseUrl/model/apiKey 只经环境变量、PUT 非空值 40000 拒绝」→ 027B 密钥可加密入库（AGENTS §3.4 修订）→ **028 废除 40000 env-only 拒绝，legacy 形态（目录空/未激活）端点/模型/密钥全字段可编辑** → **033 扩预算护栏字段（PRD §17）：`monthlyBudgetCny`/`pricing` 存 `app_settings` 键 `llm.budget`，与覆盖同「状态型字段」语义（非空=落值，空/缺省=清除）**；预算判定与用量观测见 §8.17。

#### 8.14.1 `GET /api/admin/llm/config`

响应：

```json
{ "enabled": false, "baseUrl": "http://127.0.0.1:47860/gateway/zcode", "model": "glm-5.3", "hasKey": false, "providerId": "zcode-gateway", "protocol": "anthropic-messages", "credentialsComplete": true, "source": "env", "keyLast4": null, "monthlyBudgetCny": null, "pricing": null }
```

- `baseUrl`/`model`：**生效值**——目录激活时为激活条目端点/模型（022）；legacy 形态（目录空或未激活）逐字段按「覆盖 > env」投影（覆盖值非空取覆盖，否则取 `YILIAN_LLM_BASE_URL`/`YILIAN_LLM_MODEL`，模型缺省回落 `glm-4.7`）。
- `source`（028 新增）：legacy 形态取值来源——`custom`=存在 tutor 覆盖（`baseUrl`/`model` 任一非空）；`env`=纯环境变量。目录激活态恒为 `env`（覆盖休眠，前端以目录徽标优先）。存在任一覆盖即进入 tutor 覆盖轨：端点/模型逐字段「覆盖 > env」（端点缺省回退 `YILIAN_LLM_BASE_URL`，env 端点也为空则凭证不齐、轨不可用），投影与轨道恒同源——不存在「视图报 custom 而轨道忽略覆盖」的形态。
- `hasKey`：生效轨是否有可用密钥——目录激活按 027B 条目解析链（入库密钥可解密或 `YILIAN_LLM_KEY_<大写ID>` env 任一）；legacy 覆盖轨为 tutor 预留行可解密或 `YILIAN_LLM_API_KEY` env；纯 env 轨为 `YILIAN_LLM_API_KEY`。
- `keyLast4`（028 新增）：tutor 密钥行（`llm_provider_keys` 预留 id=`tutor`）的尾 4 位辨识号——仅在覆盖轨生效（覆盖轨视图存在）且有入库行时给值；env 态密钥与目录激活态为 `null`（明文永不回读）。
- `providerId`：生效 provider id；legacy 形态（覆盖轨与环境变量轨）为 `"legacy"`（覆盖/env 的来源差异由 `source` 表达），未配置（目录未激活且 legacy 未配）时为 `null`。`protocol`：生效协议 wire 值（`anthropic-messages` | `openai-chat` | `openai-responses`），未配置时为 `null`。
- `credentialsComplete`：凭证按协议口径是否齐备（anthropic-messages 轨 key 可空、齐备只看端点）——前端判定答疑可用性用它，不能用 `hasKey` 代替（anthropic 轨无 key 也可用，022）。目录非空但未激活时轨道 fail-closed（022 语义不变），`credentialsComplete=false`。
- **API Key 不回读**（AGENTS §3.4），只回 `hasKey` 布尔与 `keyLast4` 尾号；响应无 `apiKey` 字段。
- `enabled` 读 `app_settings`；键不存在/JSON 损坏/版本不识别一律按 `false`（fail-closed）。
- `monthlyBudgetCny`（033 新增）：月度预算（元），读 `app_settings` 键 `llm.budget` 直投影；`null`=未设预算（护栏不生效，现行为）。与 §8.17 用量视图同源。
- `pricing`（033 新增）：模型定价表 `{模型名 → {"inputPerMillionCny":数字,"outputPerMillionCny":数字}}`（每百万 token 单价）；`null`=未设定价（成本不计入预算）。键与 `llm_usage.model` 快照精确匹配。

#### 8.14.2 `PUT /api/admin/llm/config`

请求（028；033 扩预算护栏字段）：

```json
{ "enabled": true, "baseUrl": "http://10.0.0.8:8080/v4", "model": "glm-5.3-flash", "apiKey": "可选", "clearKey": false, "monthlyBudgetCny": 100, "pricing": { "glm-5.3-flash": { "inputPerMillionCny": 2, "outputPerMillionCny": 8 } } }
```

- `enabled` 必填，语义不变：`true` 且无可用客户端（激活 provider 凭证不齐 / 目录未激活且覆盖轨与 legacy 均不可用）→ `40000` 拒绝，**本次全部变更（覆盖/密钥/开关）同事务回滚**；文案按配置形态分口径（目录缺密钥→条目录入/env 索引；覆盖轨缺密钥→卡内录入或 `YILIAN_LLM_API_KEY`；纯 env→两件套文案，anthropic 轨提示「无需 API Key」）。
- `baseUrl`/`model` 为**状态型字段**（028）：非空白（trim 后）=落 tutor 覆盖（存 `app_settings` 键 `llm.tutor`，即时生效——`baseUrl` 需 `http(s)://` 前缀且无空白、≤500 字符；`model` ≤2048 字符，违规 `40000`）；**空串/缺省=清除该项覆盖**（生效值回退 env）。只传 `enabled` 的调用会同时清除既有覆盖——客户端应总是携带表单当前值。存在任一覆盖（含 model-only）即进覆盖轨：端点缺省回退 `YILIAN_LLM_BASE_URL`，模型覆盖在 env 端点上真实生效。
- `apiKey` 写-only（028）：非空白（trim 后 ≤2048 字符）=AES-256-GCM 加密 upsert 到 `llm_provider_keys` 预留 id=`tutor`（明文只在加密前内存短暂存在，不入库明文列、不入日志/审计/响应）；空/缺省=不变更；清除密钥用 `clearKey=true`（删该行，无行幂等）。
- `monthlyBudgetCny`/`pricing` 同为**状态型字段**（033，预算护栏）：`monthlyBudgetCny` 非 null 必须 >0（0/负 `40000`）、`pricing` 非 null 按整体替换（模型名 trim 后非空白且 ≤128 字符——与 `llm_usage.model` 同宽，单价非 null 且 >0，任一违规 `40000`），落 `app_settings` 键 `llm.budget`（`{"schemaVersion":1,"monthlyBudgetCny":数字或null,"pricing":对象或null}`，保存即时生效）；**null/缺省=清除对应项**（未设预算=护栏不生效、未设定价=成本不计入预算）。与 `baseUrl`/`model` 同口径：只传 `enabled` 的调用会同时清除预算与定价——客户端应总是携带表单当前值。
- 校验失败（形状/长度）先于任何写路径 `40000`；覆盖写（`app_settings`）与密钥写（`llm_provider_keys`）同事务，任何失败一并回滚。
- 审计：`@OperationLog(module="llm", action="LLM_CONFIG_UPDATE")`；GET 不标（§3.6）。
- 错误：`40100`、`40300`、`40000`、`40001`。

字段回溯：`app_settings.value`（JSON，键 `llm.tutor`，028 形状 `{"schemaVersion":1,"enabled":bool,"baseUrl":"…或null","model":"…或null"}`）+ `app_settings.value`（JSON，键 `llm.budget`，033 形状 `{"schemaVersion":1,"monthlyBudgetCny":数字或null,"pricing":{模型名:{inputPerMillionCny,outputPerMillionCny}}或null}`）+ provider 目录（键 `llm.providers`/`llm.active`）+ `llm_provider_keys`（027B 条目密钥 + 028 预留 id=`tutor` 行，密文列，DATA-MODEL §5.20）+ 环境变量；`credentialsComplete`/`hasKey` 按解析链实时口径（入库密钥解密失败按「密钥不存在」回退 env，不阻塞装配）。

前端消费（019，022 升级，027B 更新，028 更新）：ModelsPage「统一模型配置」状态卡读 GET 回显；目录激活态字段只读展示、编辑走 provider 抽屉（§8.15）；legacy 态（目录空/未激活）当前模型/生效端点 inline 可编辑（默认回显生效值），密钥 password 写-only（留空=不变更，永不回填），保存/「重置为环境默认」（清覆盖 + 删 tutor 密钥，确认弹窗）走 PUT，成功先落回执再失效本地缓存；来源徽标按激活态/`source` 三态展示（目录/自定义/环境变量）；「题目答疑」行与 AI 功能清单按 credentialsComplete && enabled 联动展示启用中/未生效（缺凭证）/未开放。legacy 态启停开关与表单当前值一起提交（状态型字段语义下裸传 enabled 会清掉既有覆盖）；目录态开关只传 `{ "enabled": bool }`。

### 8.15 `/api/admin/llm/providers`（implemented，022）

provider 目录（022，CC Switch 式多 provider；027B 扩密钥录入）：元数据 CRUD 与激活切换；服务端 `ROLE_ADMIN`。**apiKey 为写-only 字段**——目录只存 `{id, name, protocol, baseUrl, model}` 元数据；条目密钥解析链为 **`llm_provider_keys` 密文解密（AES-256-GCM，管理端录入）> `YILIAN_LLM_KEY_<大写ID>` env（id 内连字符转下划线，如 `zcode-gateway` → `YILIAN_LLM_KEY_ZCODE_GATEWAY`）> legacy**；明文密钥任何路径不回读、不入日志/审计——条目只回 `hasKey`（入库可解密或 env 任一）与 `keyLast4`（入库密钥尾 4 位辨识号，env 态为 null）。目录存 `app_settings` 键 `llm.providers`（`{"schemaVersion":1,"providers":[{id,name,protocol,baseUrl,model}...]}`）与 `llm.active`（`{"schemaVersion":1,"providerId":"…"}`，激活唯一）；JSON 损坏/版本不识别/条目非法（id 形态、未知协议）按空目录或跳过条目处理（fail-closed，回退 legacy 轨）。激活条目即时成为答疑等 AI 功能的数据源（客户端按「目录快照+密钥行状态」指纹换轨，密钥录入/删除也即时生效，无需重启）；`llm.tutor` 开关（§8.14）独立沿用。

#### 8.15.1 `GET /api/admin/llm/providers`

响应：

```json
{ "items": [ { "id": "zcode-gateway", "name": "ZCode 网关", "protocol": "anthropic-messages", "baseUrl": "http://127.0.0.1:47860/gateway/zcode", "model": "glm-5.3", "hasKey": false, "keyLast4": null, "active": true } ] }
```

- `protocol` ∈ `anthropic-messages | openai-chat | openai-responses`（拼接口径见 §6.12）。
- `hasKey`（027B 口径）：该 id 是否有可用密钥——`llm_provider_keys` 入库密钥（可解密）或 `YILIAN_LLM_KEY_<大写ID>` env 任一存在；入库密文解密失败（主密钥被换/密文被篡改）按「密钥不存在」回退 env，不阻塞目录读。anthropic-messages 轨无 key 也可用（齐备性看 §8.14.1 `credentialsComplete`）。
- `keyLast4`（027B 新增）：入库密钥的尾 4 位辨识号（录入时留痕，仅作辨认不算明文）；无入库密钥（env 态/未配置）时为 `null`——env 态不显示 last4。响应无 `apiKey` 字段（明文永不回读）。
- `active`：是否当前激活（激活唯一）。

#### 8.15.2 `POST /api/admin/llm/providers`

请求：`{ "id": "zcode-gateway", "name": "ZCode 网关", "protocol": "anthropic-messages", "baseUrl": "http://127.0.0.1:47860/gateway/zcode", "model": "glm-5.3", "apiKey": "可选" }`（前五字段必填；`id` 限 `[a-z0-9-]+`、≤64 字符、目录内唯一——env 变量名按连字符→下划线大写映射，故 id 只收小写字母/数字/连字符）。

- `apiKey`（027B 新增，写-only、可选）：空白/缺省=不录密钥（解析链回退 env/legacy）；非空白=AES-256-GCM 加密落 `llm_provider_keys`（明文只在加密前内存短暂存在，不入库明文列、不入日志/审计/响应；≤2048 字符）。
- 追加目录末尾，**默认未激活**（答疑数据源切换需显式激活）；`id` 重复/形态非法/协议未知 → `40000`。元数据写入与密钥录入同事务，加密失败一并回滚。
- 审计 `LLM_CONFIG_UPDATE`（切面不读请求体，明文不进审计）；错误：`40100`、`40300`、`40000`、`40001`。

#### 8.15.3 `PUT /api/admin/llm/providers/{id}`

请求：`{ "name": "…", "protocol": "…", "baseUrl": "…", "model": "…", "apiKey": "可选" }`（`id` 走路径不可改，元数据整体覆盖，末写胜出）。`apiKey`（027B，写-only）：**空/缺省=不变更**（保留已入库密钥与 env 态），非空白=加密 upsert 覆盖旧密钥（响应回新 `keyLast4`）。错误：`40400`（provider 不存在）、`40000`、`40100`、`40300`；审计 `LLM_CONFIG_UPDATE`（明文不进审计）。

#### 8.15.4 `DELETE /api/admin/llm/providers/{id}`

移除目录元数据，`llm_provider_keys` 密钥行**同事务删除**（027B；env 密钥不在库中、无从删也不改动）；**激活中的 provider → `40000`「激活中的 provider 不能删除，请先激活其他 provider」**（先切再删，避免答疑瞬间失去数据源）；`40400`（不存在）；审计 `LLM_CONFIG_UPDATE`。

#### 8.15.5 `PUT /api/admin/llm/providers/{id}/activate`

激活唯一，重复激活幂等（不重写）；激活即时生效（017 答疑数据源切换，无需重启）；凭证不齐时激活成功但答疑保持「功能不存在」（40400）。`40400`（不存在）；审计 `LLM_CONFIG_UPDATE`。

前端消费（022 落地，027A/027B 更新）：ModelsPage「Provider 目录」区做列表卡（协议/hasKey 徽标、027B 增入库密钥「尾号 ••••last4」展示、激活高亮）、新增/编辑抽屉（id 仅创建可填；027A 起表单一律空白手填去预设；027B 增「API 密钥」password 输入——写-only：留空=不变更、已存密钥只显尾号提示，填入=加密入库，永不回填明文）、删除确认（ConfirmDialog，文案讲清「入库密钥一并删除、env 密钥不动」，激活中删除由服务端 40000 文案透出）、激活按钮；目录为空时显示 legacy 环境变量直配形态卡；写操作成功后失效 providers 与 config 两查询（config 反映激活态）。

**内部表注（V11）**：`llm_usage`（DATA-MODEL §5.18）随本域建表——append-only 记录每次 LLM 调用的元数据（scene/protocol/model/token/耗时/成败），由答疑链路服务端落库（§6.12 用量观测双写）。**不暴露任何端点**，管理页用量视图为 M3 占位；§10.1 覆盖矩阵按「无端点」注记，不设接口级测试行。错误态文案：ModelsPage 错误卡用 `ErrorState variant="admin"`（「管理数据加载失败」语境），与练习页离线队列文案分化。

### 8.16 `/api/admin/appeals`（implemented，025）

申诉终审两端口：LLM 初裁（§6.4）之后的必经人工关口。管理员权限由 `/api/admin/**` 路径矩阵（SecurityConfig `hasRole("ADMIN")`）服务端保证。

#### 8.16.1 `GET /api/admin/appeals`

查询参数：`page`（从 1 起）、`size`（缺省 20，上限 100 越界归 100，011 分页口径）、`status`（`PENDING`/`ACCEPTED`/`REJECTED`/`WITHDRAWN`，非法值 40000）、`kind`（`APPEAL`/`DISPUTE`，非法值 40000）；均缺省=不过滤。

响应 `PageResponse<AdminAppealView>`，行按 `created_at` 倒序（同刻按 id 倒序稳定），每行含：定位（`id/attemptId/annotationKey/sentenceId/chunkIndex/kind/status`）、用户主张（`proposedAnswer/reason`）、AI 意见（`decisionVerdict`——从 `decision` JSON 抽取 `$.verdict`，`decisionReason`、`decidedBy`、`decidedAt`、`modelSnapshot`、`promptVersionId`）、上下文（`createdAt`、`sentenceZh`——LEFT JOIN 当前内容版本，题目已删为 null、`username`）。判分数据不回显（原文默认不可见红线：看原文走 §8.7 查看作答流程）。

#### 8.16.2 `POST /api/admin/appeals/{id}/decision`

请求：`{ "status": "ACCEPTED" | "REJECTED", "reason": "必填 ≤500 字" }`（Bean Validation：`@NotBlank` + 枚举 pattern）。

语义：仅 `PENDING` 可裁决，否则 `40900`（预检非 PENDING：「该申诉已裁决，请刷新后查看」；029 并发原子门——预检通过但终审 UPDATE 的 WHERE 携带 `status='PENDING'` 条件失配即已被并发终审：「该申诉已被终审」，并发双管理员裁决恰一人 200、后到者 40900 且先到者结论不被覆盖）；不存在 `40400`。落 `status` + `decision_reason`（终审理由，覆盖语义，读端按 `decidedBy` 区分初裁/终审来源）+ `decided_by='ADMIN'` + `decided_at`；初裁 `decision` JSON（AI 意见）保留不覆盖。`ACCEPTED` 的 APPEAL 经判分 overlay（`sentence_id+chunk_index` 维度，`proposed_answer` 作为该槽额外可接受答案，与 `answers[]` 同 norm-1 归一化）参与后续服务端判分，不物理改题目（D-015）；`REJECTED` 不回写任何题目数据。`status` 流转仅 `PENDING→(ACCEPTED|REJECTED)`，无 `WITHDRAWN` 入口（登记 backlog）。审计：`@OperationLog(module=admin, action=APPEAL_DECIDE, targetType=appeal)`，对象 id 与终审理由经 `OperationLogContext` 落库。

错误：`40000`（status 枚举/参数不合法）、`40001`（缺请求体/终审理由缺失）、`40100`、`40300`（非管理员）、`40400`、`40900`、`50000`。

字段回溯：`appeals.*`；`sentenceZh` → `sentence_versions.zh`（经 `sentences.current_version_id`）；`username` → `users.username`；`decisionVerdict` → `JSON_EXTRACT(appeals.decision,'$.verdict')`。

前端消费（025）：管理端第十页签「申诉」（`?tab=appeals`）：状态筛选 + 行内状态 pill + AI 意见摘要（tooltip 展开全文）/初裁不可用占位/终审理由、题目、槽位、用户提议、理由、相对时间；行操作「通过/驳回」走必填理由弹窗（`ReasonDialog` 预设 + 自定义）；概览待办「N 条答案申诉待裁决」跳转本页签。

### 8.17 `GET /api/admin/llm/usage`（implemented，033）

本月 LLM 用量视图（PRD §17 预算护栏的用量观测面）：服务端 `ROLE_ADMIN`；**读接口不标操作日志**（§3.6）。月度口径 = **UTC 自然月**（AGENTS §3 全 UTC 存储：`created_at >= UTC 月初` 起算，跨月自然重置）。聚合实现：JPA 单表 GROUP BY 构造器表达式（`sumBySceneSince`，留 MyBatis 席位给后续跨表报表——本聚合单表可表达）。判定与 §6.12/§6.13 强制门同内核（`BudgetGuardService` 快照直查不缓存，AGENTS §3.8 L2 预留不接线）。

响应：

```json
{ "monthUtc": "2026-11", "budgetCny": 100, "spentCny": 82.5, "percent": 83, "status": "WARN", "byScene": { "tutor-ask": { "tokens": 1540, "costMicroCny": 296000, "calls": 12 }, "review": { "tokens": 0, "costMicroCny": 0, "calls": 2 }, "appeal": { "tokens": 0, "costMicroCny": 0, "calls": 0 } } }
```

- `monthUtc`：判定月份（UTC 自然月，如 `2026-11`）。
- `budgetCny`：月度预算（元，§8.14 `monthlyBudgetCny` 同源）；`null`=未设预算（护栏不生效）。
- `spentCny`：本月已用**估算成本**（元，微元精度如实保留；只聚合 `estimated_cost_micro_cny` 非空的行——未定价模型调用不计入金额，PRD §17 的 cost 以估算值承接，非厂商账单）。
- `percent`：已用百分比（四舍五入到整数，可超 100）；未设预算为 `null`。
- `status` 三态 + 未设态：`UNSET`=未设预算（护栏不生效，现行为）；`OK`=<80%；`WARN`=≥80%（**不拦截只留痕**：结构化 WARN 日志 + 本视图状态可见——无邮件/推送渠道，PRD「提醒管理员」的如实落地口径）；`EXCEEDED`=≥100%（强制门生效：答疑 `40306`、复核/初裁走各自降级，§6.12/§6.13/§6.4）。
- `byScene`：分场景小计 `{tokens, costMicroCny, calls}`——已知三场景（`tutor-ask`/`review`/`appeal`）固定零值兜底，其他场景按实追加；`tokens` 为提示+回复合计，`calls` 成功与失败都计（调用即发生）。

错误：`40100`、`40300`（非管理员）。

字段回溯：`llm_usage.estimated_cost_micro_cny`（V16，DATA-MODEL §5.18）/`prompt_tokens`/`completion_tokens`/`created_at`；`budgetCny` → `app_settings` 键 `llm.budget`。

前端消费（033）：ModelsPage 下半区「本月用量」卡片（已用/预算/百分比指标 + 三态状态徽标 + 分场景小计；EXCEEDED 时展示降级说明「答疑拒新调用（40306），复核/申诉初裁走降级路径」），预算与定价编辑表单走 §8.14.2 PUT（状态型字段语义：预算空串=清除、定价行可增删整体替换）；用量视图独立查询，失败只降级为区块内提示不拖垮整页；预算/定价保存连带失效配置与用量两个查询。

### 8.18 `/api/admin/prompts`（implemented，034）

Prompt 版本治理（DATA-MODEL §5.19 写半边）：prompt_versions 注册表的应用内读写入口——此前版本流转走手工 SQL（GAP 029 余量 C-01）。模块归属沿用管理端申诉终审先例：controller 在 practice 域（`AdminPromptController`），只依赖 practice 的 service 接口（AGENTS §3.2 跨模块依赖纪律）。管理员权限由 `/api/admin/**` 路径矩阵（SecurityConfig `hasRole("ADMIN")`）服务端保证。**无 DELETE 端点**（历史快照不删=append-only 语义，预披露裁决）；label 枚举不扩 RETIRED（降级=CANDIDATE，历史版本可再激活——重启旧版本也是合法治理动作）。

#### 8.18.1 `GET /api/admin/prompts`

查询参数：`page`（从 1 起）、`size`（缺省 20，上限 100 越界归 100，011 分页口径）、`name`（`ask`/`review`/`appeal`，非法值 40000）、`label`（`ACTIVE`/`CANDIDATE`，非法值 40000）；均缺省=不过滤。

响应 `PageResponse<PromptVersionView>`，行按 `name` 升序 + `version` 倒序稳定排列（同场景新版本在前），每行含 `id/name/version/label/content/createdAt`。`content` 全文回读（版本快照非密钥，DATA-MODEL §5.19 预披露；前端列表做首行摘要）。读接口不标操作日志（§3.6）。

#### 8.18.2 `POST /api/admin/prompts`

请求：`{ "name": "ask" | "review" | "appeal", "content": "非空 ≤20000 字符（Unicode 码点口径）" }`（Bean Validation：`@NotBlank` + `@Pattern` 白名单 + `@Size`）。

语义：登记新版本——版本号**服务端分配**（该 name 现存最大 +1，不收自客户端），`label=CANDIDATE`（不自动激活，上线必须显式激活）；行内容（name/version/content）一经登记不改写（append-only），并发登记撞 `uk_prompt_name_version` 唯一键按 `40900` 收敛（重试即取到下一个版本号，幂等以数据库唯一约束为准）。审计：`@OperationLog(module=admin, action=PROMPT_REGISTER, targetType=prompt-version)`，动态对象 id 经 `OperationLogContext` 落库；prompt 全文不进审计（体积与敏感性双重考量）。

错误：`40000`（name 白名单/content 空白或超长）、`40001`（缺请求体）、`40100`、`40300`（非管理员）、`40900`（并发登记冲突）、`50000`。

#### 8.18.3 `POST /api/admin/prompts/{id}/activate`

语义：激活候选版本——仅 `CANDIDATE` 可激活；同事务完成「旧 ACTIVE 行降 CANDIDATE → 新行置 ACTIVE → `PromptRegistry.evictAll()`」，三条 LLM 链路（ask/review/appeal）下一次读取即取新版本（prompt_version_id 换轨语义，§6.4/§6.13 的可追溯性基础），缓存失效即使先于提交可见也只导致回源读库（方向安全）。对已 ACTIVE 行重复激活（双击/重试）**幂等返回现值**（不重复降级也不改写历史）；不存在 `40400`。定向 UPDATE 只碰 label 一列（行内容三列在 SQL 面不可达），`@Modifying(clearAutomatically=true)` 保证同事务回读走库。审计：`@OperationLog(module=admin, action=PROMPT_ACTIVATE, targetType=prompt-version)`。

错误：`40000`（id 形态不合法）、`40100`、`40300`（非管理员）、`40400`、`50000`。

字段回溯：`prompt_versions.*`（DATA-MODEL §5.19）。

前端消费（034）：ModelsPage「Prompt 模板」区块（M3 占位真实化）：版本列表（场景中文名+键名/版本/登记日期/label 徽标/内容首行摘要）、登记新版本抽屉（场景选择+内容编辑+字数计数+≤20000 校验）、激活确认弹窗（仅 CANDIDATE 行可用，描述讲清旧版本降级可回滚）；版本查询独立（失败只降级为区块内提示），登记/激活成功后失效回读。

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
| `PromptVersionView.id/name/version/content` | `prompt_versions.*` | 034 §8.18；行内容 append-only |
| `PromptVersionView.label` | `prompt_versions.label` | ACTIVE/CANDIDATE（激活流转） |
| `PromptVersionView.createdAt` | `prompt_versions.created_at` | UTC |

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

## 10. 实现状态总览（2026-11-19）

> 状态分级：**已实现** = 后端真实可用；**前端 mock** = 前端演示层已按本契约实现、服务端待开发（前端代码带 `TODO(mock)` 注释，接口落地后删除）；**未开发** = 契约在案、两侧均未实现。

### 10.1 已实现（后端）

> 接口级测试列对应 `server/src/test/java/io/github/codejinxz/yilian/**` 下的测试类（DoD §6 强化条款）：
> `*ControllerTest` 为 WebMvcTest 切片（真实 `SecurityConfig`，锁鉴权边界/参数校验/错误码映射/信封形状），
> `AuthFlowIntegrationTest` 为 RANDOM_PORT 真实 HTTP（认证链路专用）；两者都断言统一 `ApiResponse` 信封。

| 组 | 端点 | 说明 | 接口级测试 |
|---|---|---|---|
| Account | `GET /api/auth/csrf`、`POST /api/auth/login`、`POST /api/auth/refresh`、`POST /api/auth/logout`、`POST /api/auth/register`、`GET /api/auth/me`、`POST /api/auth/password` | 双 token、CSRF 轮换、邀请码注册；`passwordChanged` 已返回 | `AuthFlowIntegrationTest`（真实 HTTP：登录/刷新/登出/me 全链路、注册即登录与重复邮箱 40901、改密吊销会话、错误密码 40101、缺 CSRF 40301、伪造 token 401、非管理员 403）；限流口径另由 `AuthRateLimitAnnotationTest` 锁定 |
| Account | `POST /api/auth/guest-upgrade` | 游客本机记录批量迁移：≤100 条/批、同 id 同载荷 skip、异载荷 40903 整体回滚；只落 attempts/attempt_gradings，不触发排程 | `AuthGuestUpgradeControllerTest`（切片：匿名 401/信封/限流注解） |
| Account | `POST /api/auth/login/totp`、`GET /api/auth/2fa/status`、`POST /api/auth/2fa/setup`、`POST /api/auth/2fa/activate`、`POST /api/auth/2fa/disable`（032，§4.10） | TOTP 两步验证全链路：登录密码步 40305+challengeToken（5 分钟 purpose 限定 JWT，正反向互不冒用——challenge 当 Bearer 被过滤器链拒收、access token 当 challenge 被 purpose 校验拒绝）；login/totp 验 TOTP（±1 窗）或恢复码（**条件更新原子消费**：同码并发双发恰一成功、败者 40101「恢复码无效或已使用」，库内 used_at 只落一次）、剩余数随响应可见；setup 密钥/otpauth 一次性展示（重复调用作废旧密钥，activate 校验密文漂移）；activate 10 个 BCrypt 哈希恢复码一次性返回；disable 密码+动态码双确认（恢复码可代验）清注册；40305/40101/40100/40000 信封 | `AuthTotpFlowIntegrationTest`（真实 HTTP：40305 挑战与 purpose 正反向隔离、恢复码一次性消费与**两线程并发单次语义**（恰一 200 一 40101、used_at 只落一次）、reset-2fa 解救/幂等/40400）；`TotpControllerTest`（切片：匿名 401、40000/40101/40000 校验与业务透传、信封形状、限流注解契约）；限流口径（login-totp 10/min IP+challengeToken、三写端点账号 5/min）另由 `AuthRateLimitAnnotationTest` 锁定；Dao 原子消费由 `V15UserTotp2faSchemaIntegrationTest`（条件更新首标 true/重标 false、used_at 不被改写）覆盖 |
| Account | `GET /api/me/settings`、`PUT /api/me/settings` | 主题/目标/评分标准/时区/保留率等 | `MeControllerTest`（切片：匿名 401、信封、40000 字段明细、40001、首登 40303）；DTO 注解层另由 `UserSettingsRequestValidationTest` 锁定 |
| Account | `GET /api/me/export`（036，§4.11） | 本人服务端账号数据整包导出：账号块（身份投影+设置）+ attempts/全部判分行/appeals/reviewCards 四切片（DATA-MODEL §5.8–§5.11 投影）；**响应体为导出 JSON 本身而非统一信封**（文件下载语义的唯一显式例外，Content-Disposition 附件）+ 他人数据不可见 + 凭据投影（密码哈希/TOTP 材料/恢复码）不进文件 + 空数据形状 | `MeControllerTest`（切片：匿名 401、裸 JSON 形状与 Content-Disposition 附件头）；`SelfAccountLifecycleIntegrationTest`（真实 HTTP：本人数据整包与 `yilian-export-<UTC日期>.json` 附件头、他人 attempt 不可见（水平越权反向）、凭据投影字段不出现、空数据形状、已删账号导出 401） |
| Account | `POST /api/auth/delete-account`（036，§4.12） | 自助删号：密码 BCrypt 确认（40101）+ 2FA 开启者双确认（缺码 40000、错码 40101，镜像 2FA disable）→ 软删（deleted_by=本人）+ 清 TOTP 注册与恢复码 + 吊销全部会话；回执 deletedAtUtc/anonymizeAtUtc（=+30 天，配置键 `yilian.account.anonymize-grace-days` 与匿名化调度同源）；已删重复调用 40900「账号已删除」（幂等口径裁决，预披露）；最后一个管理员不可删 40900；学习记录不动；限流 delete-account 5/min（账号）；审计 SELF_DELETE（reason 固定「用户自助删号」） | `SelfAccountLifecycleIntegrationTest`（真实 HTTP：错密 40101 且不删号、2FA 三分支（缺 40000/错 40101/动态码过 200 回执）、成功主链路（回执差值恰 30 天、deleted_by/软删落值、refresh 401、再登录 40101、attempt 行保留、重复删号 40900、导出 401））；限流注解契约另由 `AuthRateLimitAnnotationTest` 锁定；软删/清 2FA/幂等分支由 `UserAccountServiceImplTest`（selfDelete 成功/已删 40900/最后管理员 40900）覆盖 |
| system/Account | （无端点，036 匿名化日调度） | `AccountAnonymizationJob`（DATA-MODEL §11 Job 登记）：每日扫 `deleted_at <= now-30d AND anonymized_at IS NULL` 逐用户匿名化（身份字段处置+设置回默认+TOTP 清+按软删时用户名吊销会话+落 anonymized_at），幂等重入零变更，学习记录 append-only 一律不动；审计=结构化日志（admin_audit_log 以管理员操作者为语义前提，自动化动作不留 actor） | `AccountAnonymizationJobIntegrationTest`（真实 MySQL：到期匿名化主链路与身份处置断言、未到期零变更、会话按软删时用户名吊销、attempt 不动、重入零变更、截止时刻口径）；列形状由 `V17UserAnonymizedAtSchemaIntegrationTest` 锁定 |
| Content | `GET /api/banks`、`GET /api/banks/{bankId}`、`GET /api/banks/{bankId}/sentences`、`GET /api/banks/{bankId}/sentences/list`（020） | PUBLIC_OR_USER：匿名仅公开题库；句子为 §5.3 增量游标契约；`/sentences/list` 为选题句列表（§5.3.1：页码分页 + keyword/level/status 筛选 + 当前用户练过态，与增量端点刻意不同形） | `BankControllerTest`（切片：匿名放行与私有隔离、40004/40005/40401、tombstone 信封、限流注解契约 IP 120/60s；020 补选题句列表 5 例——匿名公开 200/本人私有 200 带练过态/匿名私有 40100/他人私有 40300/分页 40004，限流矩阵扩到 4 端点）；SQL 语义另由 `PickerSentenceQueryDaoIntegrationTest`（真实 MySQL：可见性三态/练过态按用户隔离/动态筛选与缺省隐藏回收站/分页排序）、`LikeEscapeIntegrationTest`（keyword LIKE 转义字面量语义：题库/题目/用户三链路）覆盖 |
| Practice | `GET /api/practice/next`（D7 四参数 2026-11-03 服务端补齐，语义见 §6.1）、`POST /api/practice/attempts`（幂等 + norm-1/rubric-1 判分 + SRS 排程）、`GET /api/practice/attempts/{attemptId}`、批注 `appeal`/`dispute`（§6.4/§6.5）、`GET /api/practice/wrong-questions`、`GET /api/practice/reviews`、`POST /api/practice/reviews/{reviewId}/reset`、`GET /api/practice/reviews/overload`、`POST /api/practice/reviews/postpone`、`GET /api/practice/today` | 练习主链路（#8 波次三）；幂等口径 §6.2；复习卡一句一卡（DATA-MODEL §5.11 Q1）；next 指定句优先（MANUAL）/errors=1 错题池（与 §6.6 同口径）/excludeIds 合并去重/tag JSON 精确匹配，非法参数静默归一 | `PracticeControllerTest`（切片：全部 10 端点——匿名 401、mode 取值 40000、分页 40004、40903/40403/40404、信封形状、限流注解、D7 四参数归一透传）；`PracticeFlowIntegrationTest`（对拍：指定句优先/找不到退回、errors=1 错题口径与 exclude/tag/bankId 过滤、next 显式 bankId 40401/空态、并发同 attemptId 双发幂等收敛零 500） |
| Analytics | `GET /api/analytics/accuracy`（含 `daily14`/`recent` 扩展）、`GET /api/analytics/error-tags` | 用户时区日界；disputed 批注排除 | `AnalyticsControllerTest`（切片：匿名 401、§7.1/§7.2 信封、参数透传、非法日期 40000、空 items=[]）；`AnalyticsIntegrationTest`（真实 MySQL：聚合对拍、dispute 排除、from/to 闭区间上限 92 天） |
| Practice | `POST /api/practice/{sentenceId}/ask`（017） | 题目答疑三态门（无可用客户端 40400 功能不存在 / 未开启 40304 / 开启可用；§6.12，022 起客户端由 provider 目录按激活条目装配）、可见性复用 016 口径（40402/40300）、LLM 调用超时 15s 异常降级 50000 固定文案、限流 tutor-ask 5 次/分钟（账号）、question 1–500、回答必带 disclaimer（AI 生成，可能有误）；上下文只含题目中文/考点/本人最近错批注，不含参考译文 | `AskControllerTest`（切片：匿名 401、空白/超长 40000、缺体 40001、40400/40304/50000 透传、信封形状、限流注解契约）；`TutorServiceImplTest`（mock LLM+registry：三态门、app_settings 损坏 fail-closed、上下文拼装不含参考译文、问题定界、空回复/异常降级、40300 透传、开关存取 apiKey/baseUrl/model 拒收 40000、022 补目录模式开启被拒文案与空视图形状）；三协议客户端与装配测试见 Practice/llm 行 |
| Practice | `POST /api/practice/attempts/{attemptId}/review`（024，§6.13；029 并发串行化） | 模型复核：追加 grader=MODEL 判分行（sequence=首判+1，append-only），回看/统计取最新 sequence 而排程与错题维持首判口径（wrongCount/countWrong 限定 sequence=1）；可见性复用 §6.3 口径（40403/40300，REVEAL 无判分 40000）；幂等=已有 MODEL 行回读不再调模型（有意非 409），029 起并发复核在 READ_COMMITTED 事务内锁 attempts 父行串行化——后到者幂等回读、模型只调一次、MODEL 行恰一条，uk_grading_attempt_sequence 保留为兜底（命中即数据异常按 50000 降级回滚）；strict JSON 输出解析失败重试 1 次仍败降级 50000 不落行（镜像答疑模式），无可用客户端 40400；score 钳制 0–100、reason ≤200 字码点截断；限流 model-review 5 次/分钟（账号）；llm_usage scene='review' 三终态双写（029 起旁路记账走 REQUIRES_NEW 独立事务，不随复核主事务回滚丢失）；prompt_version_id 落 PromptRegistry 的 review 版本 id（025 V13 注册表，缺行时 NULL 降级） | `PracticeControllerTest`（切片：匿名 401、委托与信封形状、限流注解契约 5 次/60 秒/账号）；`PracticeModelReviewIntegrationTest`（真实 MySQL + 桩注册表/桩客户端：[AC-1] 落行 sequence+1/grader=MODEL/快照/耗时/口径版本沿用与 result 批注结构、[AC-2] 幂等读 LLM 调用数=1、[AC-3] 匿名 401/他人与不存在/REVEAL 拒绝且零模型调用、[AC-4] 异常与非法输出降级不落行+重试后成功+101 钳制 100、[AC-5] llm_usage 三终态、[AC-6] 限流脚本化 429+Retry-After 且被拒请求零业务副作用（真实 ZSET 窗口语义由 RateLimitServiceTest/RateLimitAspectTest 覆盖，CI 无 Redis 不可复现）、[AC-7] 回看/今日全对率取复核分而卡与 review_logs/错题池 lastScore 维持首判）；`PracticeModelReviewConcurrencyIntegrationTest`（029 [AC-2]：同 attempt 双线程并发复核 MODEL 行恰 1/双方 200 其一幂等回读/LLM 调用数=1/首判行不动，串行重复复核幂等回归）；`ModelReviewPromptBuilderTest`（prompt 五段上下文、系统提示 strict JSON 契约、定界剥离防伪造、解析/钳制/码点截断）；前端 `PracticePage.test.tsx`（[AC-8] 入口门禁匿名与本地占位隐藏、成功新分+理由+复核不影响复习计划注记+attempt 刷新、失败 toast 保留原分、无演示占位残留） |
| Practice | `POST /api/practice/attempts/{attemptId}/annotations/{key}/appeal`（025 扩展，§6.4） | 申诉同步 LLM 初裁：落库（status 保持 PENDING）后同步调模型，成功落 decision/decision_reason/decided_by=LLM/decided_at/model_snapshot/prompt_version_id（V8 裁决列启用）；失败/超时/非法输出（重试 1 次）与限流（独立 scene `appeal-adjudication` 5 次/分钟账号、程序化判定）落 `{"verdict":"UNAVAILABLE"}` 占位且回执 verdict=`"UNAVAILABLE"`（029 对齐：以存储 decision JSON 为真相，「初裁不可用」回执态真实可达），**不阻塞提交**（响应 200、status PENDING）；llm_usage scene='appeal' 三终态双写；提示词只含被申诉批注上下文与本人主张（系统提示走 PromptRegistry ask/review/appeal 三链路注册表，V13）；`dispute`（§6.5）语义不变 | `PracticeControllerTest`（切片：appeal/dispute 200 信封与缺体、40000/40300/40403/40900 映射）；`PracticeAppealAdjudicationIntegrationTest`（真实 MySQL + 桩注册表/桩限流：[AC-2] 初裁成功落裁决六列且 status=PENDING/失败与非法输出占位 decided_by=NULL/限流跳过且申诉不拒/重试恢复/DISPUTE 零模型调用、[AC-5] ACCEPTED 进 overlay 后 proposed_answer 判对而 PENDING/REJECTED 不影响、DISPUTE 不进 overlay、[AC-6] 初裁行与 MODEL 复核行 prompt_version_id 落注册表 id）；prompt 契约另由 `AppealAdjudicationPromptBuilderTest`（五段上下文不含整句参考译文/strict JSON/verdict 枚举/300 字截断/围栏容错）、V13 形状由 `V13PromptVersionsSchemaIntegrationTest` 锁定；前端 `PracticePage-appeal-opinion.test.tsx`（[AC-7] 批注行 AI 意见/初裁不可用/终审通过/驳回/无意见原文案/DISPUTE 已排除回归；029 起回执夹具与 `PracticeAppealAdjudicationIntegrationTest` 的真实回执断言同形） |
| Admin | `GET /api/admin/appeals`、`POST /api/admin/appeals/{id}/decision`（025，§8.16） | 申诉分页检索（status/kind 精确过滤、011 分页口径 size 上限 100、非法筛选 40000、行内含 AI 意见/题目摘要/用户主张；复杂查询走 MyBatis 只读 JOIN）与人工终审（仅 PENDING 可裁决否则 40900、status/decision_reason/decided_by=ADMIN/decided_at 定向 UPDATE、decision JSON 保留初裁意见；ACCEPTED 进判分 overlay 不改题目、REJECTED 不回写；审计 `@OperationLog(action=APPEAL_DECIDE)` + OperationLogContext 落对象与理由） | `AdminAppealControllerTest`（切片：匿名 401/非管理员 403、筛选透传与非法筛选 40000、PageResponse+AdminAppealView 形状、终审 200/绑定校验 400/40900 透传、审计注解口径 [AC-3]）；终审落库与 overlay 消费另由 `PracticeAppealAdjudicationIntegrationTest`（真实 MySQL）覆盖 |
| Admin/Practice | `GET /api/admin/prompts`、`POST /api/admin/prompts`、`POST /api/admin/prompts/{id}/activate`（034，§8.18） | Prompt 版本治理（DATA-MODEL §5.19 写半边，controller 在 practice 域沿用 AdminAppealController 先例）：GET 分页检索（name/label 精确过滤、非法值 40000、011 分页口径 size 上限 100、name 升序+version 倒序、content 全文回读）；POST 登记（name 白名单 ask/review/appeal、content 非空 ≤20000 码点、版本号服务端分配=现存最大+1、label=CANDIDATE 不自动激活、append-only 行内容不改写、并发撞 uk_prompt_name_version 40900）；POST activate（仅 CANDIDATE 可激活、已 ACTIVE 幂等返回、同事务「旧 ACTIVE 降 CANDIDATE→新行 ACTIVE→PromptRegistry.evictAll 即时换轨」、定向 UPDATE 只碰 label 一列、clearAutomatically 防一级缓存旧值、不存在 40400、无 DELETE 历史快照不删）；审计 PROMPT_REGISTER/PROMPT_ACTIVATE（targetType=prompt-version，动态 id 经 OperationLogContext） | `AdminPromptControllerTest`（切片：匿名 401/非管理员 403、GET 筛选透传与 PageResponse 形状、非法筛选 40000、登记 200/绑定校验（白名单/空白/超长）、激活 200/40400/40900 映射、审计注解口径与 GET 不标）；`PromptRegistryWritePathIntegrationTest`（真实 MySQL+真实 Caffeine 注册表：登记递增版本号与 CANDIDATE/校验拒绝零落库/激活后注册表即时换新版本且重复读不回旧值/旧 ACTIVE 降级/再激活旧版本回滚语义/幂等激活/40400/列表过滤与分页口径）；R2-01 初裁守卫另由 `AppealAdjudicationWindowRaceIntegrationTest`（真实 MySQL 窗口竞态：终审先行则初裁丢弃、终审结论与 decided_by 不被覆盖、回执按存储真相投影、占位路径同门）覆盖 |
| Admin | `GET/PUT /api/admin/llm/config`（017）、`GET /api/admin/llm/usage`（033）、`GET/POST /api/admin/llm/providers`、`PUT/DELETE /api/admin/llm/providers/{id}`、`PUT /api/admin/llm/providers/{id}/activate`（022） | 答疑 LLM 配置（§8.14）、本月用量视图（§8.17，033）与 provider 目录（§8.15）：enabled 与 tutor 覆盖存 `app_settings` 键 `llm.tutor`（fail-closed 解析；028 扩 baseUrl/model 覆盖）；预算护栏字段 `monthlyBudgetCny`/`pricing` 存键 `llm.budget`（033，状态型字段：非空=落值、空/缺省=清除，预算>0、模型名 ≤128、单价>0 违规 40000）；目录存键 `llm.providers`/`llm.active`（激活唯一、激活中不可删、id `[a-z0-9-]+` 唯一、协议白名单 fail-closed）；027B：目录 POST/PUT 收写-only 可选 apiKey（非空白=加密 upsert，空/缺省=不变更），GET 回 hasKey（DB 或 env 任一）/keyLast4（仅 DB 态）/active 不回读明文（响应无 apiKey 字段），DELETE 同事务删密钥行，元数据与密钥行同事务；028：PUT config 收 baseUrl/model 覆盖（非空白=落覆盖、空/缺省=清除，形状/长度校验 40000）与写-only apiKey（加密 upsert 到 `llm_provider_keys` 预留 id=`tutor`）及 clearKey（删行），GET 回 source（custom\|env）/keyLast4（tutor 行），原 40000 env-only 拒绝废除；enabled=true 无可用客户端 40000 且同事务回滚（文案按目录/覆盖轨/legacy 分口径）；033 usage 视图按 UTC 自然月聚合 `llm_usage`（金额只聚合估算成本非空行，status 三态 UNSET/OK/WARN/EXCEEDED 与强制门同内核，读接口不标审计）；审计 LLM_CONFIG_UPDATE（GET/usage 不标，切面不读请求体） | `AdminLlmConfigControllerTest`（切片：匿名 401/非管理员 403、GET 投影形状 source/keyLast4/预算定价字段无 apiKey 回显、PUT 全字段透传/clearKey/预算与定价透传 [AC-2]、校验拒绝信封、审计注解口径、usage 匿名 401/非管理员 403/投影形状 [AC-6]）、`AdminLlmProviderControllerTest`（切片：401/403、GET 形状含 keyLast4、POST/PUT/DELETE/activate 透传与 id 校验 40000、40400 信封、审计注解口径、apiKey 透传与响应无明文）；service 分支另由 `TutorServiceImplTest`（覆盖落库/清除/tutor 密钥 upsert/clearKey/校验零写入/定向文案；033：预算写入回显/清除/非法 40000 [AC-2]、40306 预算门 [AC-5]、成本落列与无定价 NULL [AC-3]、用量视图 UNSET 与状态回显 [AC-6]）、`BudgetGuardServiceTest`（80/100 边界三态/跨月重置/未设预算直通 [AC-4]）、`LlmCostCalculatorTest`（输入输出分开计价/ROUND_HALF_UP/无定价 null）、`LlmBudgetSettingsTest`（fail-closed 解析/条目校验）、`LlmProviderAdminServiceImplTest`（CRUD 规则/激活幂等/写-only apiKey/删除联动密钥行）、`LlmClientRegistryTest`（覆盖轨>env 轨/即时换轨/三态门）与集成 `TutorConfigRailIntegrationTest`、`LlmProviderKeyResolutionIntegrationTest`（真实 MySQL：三级优先序/覆盖即时换轨/tutor 密钥加密入库与 last4/开启被拒回滚；033：预算门 40306 与直通 [AC-5]、用量视图空表 UNSET/跨场景聚合 WARN/跨月排除 [AC-6]）覆盖 |
| Practice/llm | （022 支撑 §6.12 的三协议客户端与 provider 目录装配，无独立端点） | `LlmChatClient` 三协议统一接口：`OpenAiChatLlmClient`（Spring AI OpenAI 轨收编）、`AnthropicMessagesLlmClient`（021 网关轨；022 改官方 SDK 直调+手写 content 提取）、`OpenAiResponsesLlmClient`（新，手写 java.net.http）；`LlmClientRegistry` 按激活 provider 装配+目录指纹缓存+legacy 回退。V11：`llm_usage` 用量账本（DATA-MODEL §5.18）落库为内部表，无端点、不进矩阵行 | `AnthropicMessagesLlmClientTest`（JDK 桩：/v1/messages 请求形状与头、**thinking 块只拼 text 块**/max_tokens 吃尽空正文、usage 映射、429（网关非标准错误体）与 5xx 退避重试一次、4xx 不重试）；`OpenAiResponsesLlmClientTest`（JDK 桩：/v1/responses 请求形状与 Bearer 头、output 取 message/output_text、reasoning 跳过、非 2xx 不重试）；`OpenAiChatLlmClientTest`（离线装配）；`LlmClientRegistryTest`（协议装配选择、目录指纹缓存失效、legacy 回退、凭证口径按协议分、三态门）；`LlmProviderCatalogTest`（app_settings 解析 fail-closed、写入形状、env 索引连字符→下划线）；`LlmPropertiesTest`（env 绑定+legacy 映射）；V11 落库形状/失败旁路由 `TutorServiceImplTest`（[AC-V11-1]/[AC-V11-2]）与 `SentenceActiveExternalKeySchemaIntegrationTest`（真实 MySQL 全字段读回）覆盖——矩阵注：内部表无端点，无接口级测试行 |
| Upload | `POST /api/admin/uploads/preview`、`POST /api/admin/uploads/{batchId}/commit`、`GET /api/admin/uploads/{batchId}`、`GET /api/admin/uploads`、`POST /api/admin/uploads/{batchId}/rollback` | 导入 preview→commit→rollback（波次二）；§5.4–§5.8 | `ImportBatchControllerTest`（切片：匿名 401/非管理员 403、multipart 绑定与缺参 40000、缺请求体 40001、40405/40904、PageResponse 信封）；`ImportBatchServiceIntegrationTest`（真实 MySQL：五端点语义、commit 原子性（回写失败整体回滚可恢复）、COMMITTING 残留对账收敛、重复 commit 幂等、回滚 tombstone） |
| Admin | `GET /api/admin/overview`、`GET /api/admin/users`、`PATCH /api/admin/users/{id}/status`、`PATCH /api/admin/users/{id}/role`、`POST /api/admin/invites`、`GET /api/admin/invites`（011-a）、`POST /api/admin/users/{id}/reset-password`、`DELETE /api/admin/users/{id}`（018）、`POST /api/admin/users/{id}/reset-2fa`（032） | 概览聚合（活跃=attempts 当日去重用户，UTC 日界）、用户检索分页、启停/改角色（审计经 `OperationLogContext`）、邀请码生成（明文一次性）/前缀列表、重置密码（一次性临时口令+40303 强制改密+吊销全部会话，§8.2b）、删除账号（软删+吊销会话+40400 幂等口径，§8.2c）、重置两步验证（清 TOTP 注册+恢复码+吊销全部会话，原因必填审计 RESET_2FA，允许自愈式自重置——唯一管理员丢码解救，未注册幂等 200、已删 40400，§8.2d；users 列表行新增 totpEnabled 只读投影） | `AdminOverviewControllerTest`、`AdminUserControllerTest`、`AdminInviteControllerTest`（切片：匿名 401/非管理员 403、信封与 PageResponse 形状、reason 越界 40000、40001、审计上下文断言；018 补 reset-password/delete 绑定校验与 403/401 矩阵；032 补 reset-2fa 原因透传与审计上下文/缺原因 40000/403/40400 与列表行 totpEnabled 投影）；service 分支另由 `UserAccountServiceImplTest`（自己停自己/自己降自己/邀请码哈希落库；018 补重置成功与自重置/已删/原因必填、删除成功与自删/已删/最后管理员；032 补 reset-2fa 清列+删恢复码+吊销会话/未注册幂等/缺失与已删与空白原因）、`AdminOverviewServiceImplTest`（补 0 与活动组装）覆盖 |
| Admin | `GET/POST /api/admin/banks`、`GET/PATCH/DELETE /api/admin/banks/{bankId}`、`GET /api/admin/banks/{bankId}/sentences`、`GET /api/admin/sentences`、`POST /api/admin/sentences/bulk-status`、`POST /api/admin/sentences/bulk-move`、`PATCH /api/admin/sentences/{sentenceId}/status`、`PATCH /api/admin/sentences/{sentenceId}`（011-b） | 题库检索/新建/详情/部分更新/软删（状态流转 RECYCLED，恢复清 `deleted_at`）、题库内与跨题库题目检索（跨题库缺省隐藏回收站）、批量状态/移动（单事务；不存在的 id 与无变化 id 跳过、计数如实返回；每题递增银行修订号；移动预检指纹冲突 40000）、单题状态流转（幂等不推进）、行内编辑（zh/level/tags/note 走新版本行并回填指纹/content_hash，bankId/status 走元字段；编辑先于移动，预检用新指纹） | `AdminBankControllerTest`、`AdminSentenceControllerTest`（切片：匿名 401/非管理员 403、信封与 PageResponse/SentenceView 形状、reason/ids/zh 校验 40000、缺体 40001、40401/40402、审计上下文断言）；批量与列表/编辑语义另由 `ContentAdminServiceImplTest`（每题递增版本、悲观锁按题库 id 升序、跳过语义、恢复清软删、版本字段新版本行、非法枚举 40000/分页 40004）覆盖 |
| Admin | `GET /api/admin/audit-logs`、`GET /api/admin/error-logs`、`GET /api/admin/error-logs/{id}`、`POST /api/admin/generation/import`、`POST /api/admin/audit-events`（012） | 操作日志/异常日志检索（分区表强制时间兜底：from/to 缺省近 30 天；size 上限 100 越界归 100；keyword LIKE 转义纯 contains；异常列表剥离 stackTrace、详情含堆栈缺失 40400）、出题导入（复用 content 导入接缝，指纹去重 40904 整批回滚，行校验与文件导入同口径、拼接比对按前端 normLoose 宽松归一，不建 upload 批次）、审计事件（动作白名单防污染，返回字符串 id） | `AdminAuditLogControllerTest`、`AdminErrorLogControllerTest`、`AdminGenerationControllerTest`、`AdminAuditEventControllerTest`（切片：匿名 401/非管理员 403、信封与 PageResponse/批次视图形状、stackTrace 剥离与详情、审计上下文/字符串 id 断言）；服务层分支另由 `AdminLogServiceImplTest`（时间兜底/转义/size 归一/40000/40400）、`AdminGenerationServiceImplTest`（行校验 fail-fast/接缝透传/40904 上抛）、`AdminAuditEventServiceImplTest`（白名单/行形状）覆盖；题库「空串清空说明」契约由 `ContentAdminServiceImplTest` 补测（012 AC-5） |
| Admin/Practice | `POST /api/admin/fsrs/optimize`、`GET /api/admin/fsrs/parameters`、`POST /api/admin/fsrs/parameters/{id}/enable`（014/M2.1） | FSRS 参数版本管理：SGD 优化读指定用户 review_logs 产 GLOBAL CANDIDATE（样本 <50 40000 拒绝、metrics 携训练摘要）、版本列表、启用状态机（仅 CANDIDATE；旧 ACTIVE bulk UPDATE 即时退役防 uk 撞键；审计 FSRS_OPTIMIZE/FSRS_ENABLE）；启用后调度消费新权重（§6.1 四分支参数换源，原型种子逐值不变） | `AdminFsrsControllerTest`（切片：匿名 401/非管理员 403、40000/40001/41500、信封与视图形状、审计注解口径）；`FsrsAdminIntegrationTest`（真实 MySQL：真实日志训练产 CANDIDATE、样本不足不落行、启用状态机/40000/40400、启用后失败排程 stability=w[0]/difficulty=w[4] 且 weights_version_id 绑新版本）；`AdminFsrsControllerTest` 补 optimize 限流注解契约（ACCOUNT 5/60s）；优化器数学另由 `FsrsOptimizerTest`（FSRS-6 闭式/BCE 手算对拍、合成数据收敛）覆盖 |
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

- Admin 剩余写接口（§8.7 `attempts/{id}/raw` 查看原文、§8 模型配置；审计/异常检索与出题导入/audit-events 已随 012 落地，用户管理与概览/邀请码已随 011-a 落地，题库管理与题目检索/批量/单题状态/行内编辑已随 011-b 落地）。模型/运维页签保留演示态（012 观察项，随模型域切片真实化）。
- 深度批改（M3）。
- 017 观察项（V11 收口）：`llm_usage` 用量表已随 V11 建表并落库（DATA-MODEL §5.18，结构化日志+落库双写，内部表无端点）；`llm_providers`/`prompt_versions` 全量模型配置域（M3，当前经 provider 目录（§8.15，app_settings 单键）+ YILIAN_LLM_KEY_* 环境变量过渡）。

### 10.5 待收口

- 登录「用户名或邮箱」：语义已随基线实现（`JpaAppUserDao.findByLogin` 经 `findByUsernameOrEmail(login, login)` 单查询匹配用户名或邮箱，与 §4.2 契约一致），余项为补邮箱登录的接口级测试（现有集成测试仅覆盖用户名登录）。
- 错误码补齐（已收口）：原登记的 40302/40303、40404–40405、40901–40905、41300、42200 已全量定义于 `ApiCode.java`（§1.3 错误码表同步在案），其中 40303/40404/40405/40904 有对应端点与接口级测试在用。
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
| 2026-11-04 | v0.7 | 015 修复轮（后端）：§5 限流口径（banks 读 IP 120/60s）、§4.2 login 限流勘误 10/min(IP+账号)、§4.8 guest-upgrade 并发竞态 40900/40903、§6.1 bankId 前置校验 40401 与 excludeIds ≤100、§6.2 并发同 attemptId 幂等收敛、§7.1/§7.2 区间上限 92 天与用户时区日界口径、§8.4/§8.5 from/to 改按管理员本地日界（yilian.admin.day-zone，默认 Asia/Shanghai）、§5.5 commit 单事务原子化与 COMMITTING 对账恢复、§8.13 fsrs/optimize 限流 5/min 与样本上限 5000；§10.1 矩阵补 `LikeEscapeIntegrationTest`/`PracticeDtoContractTest` 与各集成测试新增断言 | 实现工程师 |
| 2026-11-03 | v0.7 | 014/M2.1 第一步：§6.1 D7 四参数服务端补齐（sentenceId 指定句优先 MANUAL/errors=1 错题池（与 §6.6 同口径，REVEAL 无错批注行不进池为与本地引擎的已登记偏差）/excludeIds 合并去重/tag JSON 精确匹配，非法值静默归一）；§10.1 同步 | 实现工程师 |
| 2026-11-03 | v0.8 | 014/M2.1 第二步：新增 §8.13 admin/fsrs 三端点（SGD 优化产出 GLOBAL CANDIDATE、版本列表、启用状态机）；§10.1 同步 | 实现工程师 |
| 2026-11-04 | v0.9 | 018 二轮评审修复+原型小项：新增 §8.2b reset-password（一次性临时口令+40303 强制改密+吊销会话，audit-events 白名单移除 RESET_PASSWORD 改真端点）与 §8.2c DELETE users/{id}（软删+吊销会话+已删 40400 幂等口径）；§6.11 顺延口径统一 3 天（对齐原型与本地引擎）；登录 40101 三分支补齐假 BCrypt 时序对齐（响应形状不变）；§10.1 矩阵同步 | 实现工程师 |
| 2026-11-04 | v1.0 | 017 题目答疑 AI 机器人：新增 §6.12 `POST /api/practice/{sentenceId}/ask`（被动一问一答，三态 40400/40304/可用，LLM 降级 50000，限流 5/min 账号，disclaimer 常驻）与 §8.14 admin/llm/config（enabled 存 app_settings 键 llm.tutor，base-url/model/apiKey 环境变量只读、apiKey 不回读只回 hasKey，审计 LLM_CONFIG_UPDATE）；§1.3 增 40304 ASK_NOT_OPEN（计划原文 40301 已被 CSRF 占用，按「不改号不复用」启用新号）；Spring AI 2.0.0（BOM，openai starter，自动配置关闭手工装配）；llm_usage 落库待批记 §10.4；§10.1 矩阵同步 | 实现工程师 |
| 2026-11-05 | v1.1 | 021 LLM provider 抽象与 ZCode 网关适配：§6.12 三态凭证口径按 provider 分（openai=base-url+api-key；anthropic=仅 base-url，网关本机无鉴权）并补 provider 双轨说明（YILIAN_LLM_PROVIDER=openai 缺省 \| anthropic；anthropic 轨 base-url 填到网关前缀、客户端拼 /v1/messages，429/5xx 退避 30s 重试一次）；§8.14.2 文案分口径；§10.1 矩阵补 AnthropicMessagesLlmClientTest/TutorLlmConfigurationTest/LlmPropertiesTest 新增 | 实现工程师 |
| 2026-11-05 | v1.2 | 022 AI 配置升级：§6.12 改写为 provider 目录 + 三协议（anthropic-messages \| openai-chat \| openai-responses，Responses 轨手写 java.net.http——spring-ai 2.0.0 无支持）与 legacy 单 provider 回退；maxTokens 三协议同源 768→2048（思考模型 thinking 计入 max_tokens，实测 32 全被吃掉），补思考模型注（anthropic 轨改官方 SDK 直调+按块类型提取，Spring AI getText() 会把 thinking 块当正文）；新增 §8.15 provider 目录五端点（元数据 CRUD+激活唯一，密钥走 YILIAN_LLM_KEY_<大写ID> env 索引不入库）；§8.14.1 视图扩 providerId/protocol/credentialsComplete；§10.1 矩阵重排 Practice/llm 与 Admin 行 | 实现工程师 |
| 2026-11-05 | v1.2 | 020 练习选题 + AC-0 提交假失败修复：新增 §5.3.1 `GET /api/banks/{bankId}/sentences/list`（页码分页 + keyword/level/status 筛选缺省隐藏回收站 + 当前用户练过态，匿名=未学，与 §5.3 增量端点刻意不同形并登记差异）；§6.2 补记内嵌 review 为排程投影（无 sentenceId/mode，与 §6.7 队列卡不同形）——此前前端 schema 误按队列卡校验致 200 已入库被误报「提交未确认」；§2 鉴权矩阵、§10.1 矩阵同步（BankControllerTest 补 5 例切片 + PickerSentenceQueryDaoIntegrationTest） | 实现工程师 |
| 2026-11-06 | v1.3 | V11 迁移 + 错误态语境分化：§6.12 用量观测改「结构化日志 + `llm_usage` 落库」双写（V11 建表，内部表无端点，017「落库待批」收口；落库失败只告警不阻塞答疑）；§8.15 补内部表注（llm_usage 不暴露端点、矩阵注）与 ModelsPage 错误卡 `ErrorState variant="admin"` 文案口径（「管理数据加载失败」，不带练习页离线队列文案）；§10.1 Practice/llm 行补 llm_usage 矩阵注、§10.4 017 观察项收口 | 实现工程师 |
| 2026-11-06 | v1.5 | 025 申诉 LLM 裁决：§6.4 扩展（提交即同步 LLM 初裁，回执新增 verdict/decisionReason/decidedBy/decidedAt 四字段，初裁失败/限流落 `{"verdict":"UNAVAILABLE"}` 占位不阻塞提交，status 恒 PENDING 待人工终审）；新增 §8.16 `GET /api/admin/appeals` + `POST /api/admin/appeals/{id}/decision`（011 分页口径、仅 PENDING 可裁决 40900、审计 APPEAL_DECIDE；ACCEPTED 进判分 overlay 不改题目、REJECTED 不回写）；判分 overlay：本句 ACCEPTED APPEAL 的 (chunk_index, proposed_answer) 作为该槽额外可接受答案（norm-1 同口径，DISPUTE 排除语义不变）；V13 建 `prompt_versions` 注册表（ask/review/appeal 首版种子），ask/review/appeal 三链路系统提示改经 PromptRegistry 取 ACTIVE 版本（缺行回退同文常量），attempt_gradings MODEL 行与 appeals 落 prompt_version_id（llm_usage 无该列、本批不扩列——计划 AC-6 原文含 llm_usage 系笔误，PR 披露）；§2 矩阵、§10.1 矩阵两行同步 | 实现工程师 |
| 2026-11-06 | v1.4 | 024 模型复核接后端：新增 §6.13 `POST /api/practice/attempts/{attemptId}/review`（追加 grader=MODEL 判分行 sequence=首判+1，model_snapshot/latency_ms V8 预留列启用；幂等=已有 MODEL 行回读不重复计费，并发撞 uk 回读既有行故 40900 不适用；strict JSON 解析失败重试 1 次仍败降级 50000 不落行；score 钳制 0–100、reason ≤200 字；限流 model-review 5 次/分钟账号；llm_usage scene='review' 三终态双写）；错题池/wrongCount 口径明确按首判（sequence=1），复核不影响复习计划；§2 矩阵、§10.1 矩阵同步 | 实现工程师 |
| 2026-11-17 | v1.6 | 027B provider 密钥管理端加密入库：V14 建 `llm_provider_keys`（DATA-MODEL §5.20；provider_id 主键/AES-256-GCM 密文/key_last4/updated_at，零物理外键）；新增主密钥 env `YILIAN_SECRET_MASTER_KEY`（prod 缺失 fail-fast、local 文档化 dev 值，AGENTS §3.4 修订随批次落地）；条目密钥解析链改 **DB 密文 > `YILIAN_LLM_KEY_<大写ID>` env > legacy**（解密失败按密钥不存在回退 env 不阻塞目录读，hasKey=DB 或 env 任一，credentialsComplete 同步）；§8.15.2/8.15.3 请求体增写-only 可选 apiKey（空/缺省=不变更、非空白=加密 upsert，明文不入日志/审计/响应）、§8.15.1 增 keyLast4（env 态 null）、§8.15.4 密钥行同事务删；§8.14 字段回溯与开答疑被拒文案双通道；§10.1 Admin 行测试引用同步 | 实现工程师 |
| 2026-11-17 | v1.7 | 028 答疑 LLM legacy 态全字段可编辑：§8.14.2 PUT 扩 baseUrl/model 覆盖（非空白=落覆盖存 `app_settings` 键 `llm.tutor`，空/缺省=清除回 env；形状/长度校验 40000）、写-only apiKey（AES-256-GCM 加密 upsert 到 `llm_provider_keys` 预留 id=`tutor`，空/缺省=不变更）、clearKey=true 删 tutor 密钥行；**废除 017 的 40000 env-only 拒绝语义**（契约演进链 017→027B→028），enabled=true 无可用客户端仍 40000 且同事务回滚全部写入；§8.14.1 GET 增 source（custom\|env，legacy 形态取值来源）与 keyLast4（tutor 密钥行尾号），生效值改按「目录激活 > tutor 覆盖 > env」逐字段投影；解析链三级（022 语义不变：目录激活高于一切 legacy 形态，目录非空未激活仍 fail-closed），覆盖/密钥变更经注册表指纹即时换轨；§10.1 Admin 行同步；黑盒套件（docs/analysis/ai-tutor-test）A-04/A-05/A-06/C-05 期望同步并注明 | 实现工程师 |
| 2026-11-17 | v1.8 | 029 双 skill 第一轮消缺：§6.13 复核并发语义修订（READ_COMMITTED 事务内锁 attempts 父行串行化，后到者幂等回读、模型只调一次、MODEL 行恰一条，uk_grading_attempt_sequence 降为兜底——命中即数据异常按 50000 降级回滚；llm_usage 旁路记账改 REQUIRES_NEW 独立事务不随主事务回滚丢失）；§6.4/§8.16 申诉语义收口（终审 UPDATE WHERE 携带 status='PENDING' 条件——并发双管理员裁决恰一人成功，后到者 40900「该申诉已被终审」；占位回执 verdict="UNAVAILABLE" 如实发送，与存储 decision JSON 同源，「初裁不可用」回执态真实可达）；§10.1 矩阵两行同步 | 实现工程师 |
| 2026-11-18 | v1.9 | 030 过时声明修正：§10.4 删「§6.1 D7 四参数未开发」条目（已随 014 落地，§10.1 Practice 行在案）、删「FSRS 排程真值/申诉 LLM 裁决」（分别已随 014/025 落地，§8.13/§6.4/§8.16 在案，深度批改 M3 保留）；§10.5 错误码行改写为已落地口径（40302/40303、40404–40405、40901–40905、41300、42200 已全量定义于 `ApiCode.java`，40303/40404/40405/40904 有端点与测试在用）；登录「用户名或邮箱」条目按代码实态改写（语义已随基线经 `findByUsernameOrEmail` 单查询实现，余项收窄为补邮箱登录接口级测试）；§10 头部日期 2026-10-31→2026-11-18 | 实现工程师 |
| 2026-11-18 | v2.0 | 032 管理员 2FA（TOTP 自研 RFC 6238，零新依赖）：新增 §4.10（`POST /api/auth/login/totp` 公开+CSRF、`GET /api/auth/2fa/status`、`POST /api/auth/2fa/setup|activate|disable` 登录态）与 §8.2d `POST /api/admin/users/{id}/reset-2fa`；§4.2 登录密码步 40305（data 携 5 分钟 purpose 限定 challengeToken，challenge 与 access token 分 purpose 互不冒用）；§1.3 增 40305 TOTP_REQUIRED；恢复码 10×8 位 BCrypt 落 user_2fa_recovery_codes（使用落 used_at 不删行，DATA-MODEL §5.21/V15）；限流 login-totp 10/min(IP+challengeToken)、setup/activate/disable 5/min(账号)；审计 TOTP_SETUP/ACTIVATE/DISABLE 与 RESET_2FA；§2/§3/§10.1 矩阵同步 | 实现工程师 |
| 2026-11-18 | v2.1 | 033 预算护栏（PRD §17，V16）：§6.12 答疑前置预算强制门（已设 `llm.budget` 月度预算且本月已用估算成本 ≥100% 时 `40306` 固定文案拒新调用，预算门先于三态门；80% WARN 只留痕：WARN 日志+§8.17 状态可见——无推送渠道的如实口径）；§6.13 复核预算门走既有 50000 降级且文案带预算原因（零模型调用零落行）；§6.4 初裁预算门落 `{"verdict":"UNAVAILABLE","reason":"BUDGET"}` 占位不阻塞提交；V16 建 `llm_usage.estimated_cost_micro_cny`（微元整数口径 ROUND_HALF_UP，无定价 NULL=不计入预算，三记账点补成本）；§8.14.1/8.14.2 扩 `monthlyBudgetCny`/`pricing`（键 `llm.budget`，状态型字段）；新增 §8.17 `GET /api/admin/llm/usage`（UTC 自然月用量视图：monthUtc/budgetCny/spentCny/percent/status/byScene，JPA 单表聚合）；§1.3 增 40306 MODEL_BUDGET_EXHAUSTED；§10.1 Admin 行同步 | 实现工程师 |
| 2026-11-18 | v2.2 | 034 Prompt 注册表写路径 + 黄金集回归资产 + R2-01：新增 §8.18 `/api/admin/prompts` 三端点（GET 分页检索 name/label 过滤 011 口径、POST 登记 name 白名单/content ≤20000 码点/版本号服务端分配=label=CANDIDATE append-only、POST activate 仅 CANDIDATE 可激活同事务「降级→激活→evictAll」即时换轨、已 ACTIVE 幂等、无 DELETE 历史快照不删；审计 PROMPT_REGISTER/PROMPT_ACTIVATE）；§10.1 增 Admin/Practice 行；§6.4 初裁回执扩窗口竞态口径（终审先行时回执按存储真相投影为终审态，status 不恒 PENDING，R2-01）；§9.3 增 PromptVersionView 字段回溯 | 实现工程师 |
| 2026-11-18 | v2.3 | 035 strict JSON 输出契约双执行点（GAP 029 DEF-01 方向①）：§6.13/§6.4 补「输出契约双执行点（035）」口径——系统提示（V13 种子语义不动）声明 + 用户消息末尾「输出格式要求」指令块强制（字段清单与解析端一一对应、只输出 JSON 对象本身）；受控实验+黄金集基线实证现网网关不生效 top-level system 字段（只写在系统提示的契约模型收不到、回复自由文本、解析必败），契约经用户消息 wire 级可达，尊重 system 的直连部署双重强调无害；指令块为 builder 代码侧静态文本不进 `prompt_versions`（无迁移，DATA-MODEL §5.19 注）；接口形状/错误码/限流零变化 | 实现工程师 |
| 2026-11-19 | v2.4 | 036 自助删号（30 天冷静期匿名化）+ 服务端数据导出（GAP pending-20261117 P0-05）：新增 §4.11 `GET /api/me/export`（本人账号块+练习域四切片整包 JSON；**响应体为导出 JSON 本身而非统一信封**——文件下载语义的唯一显式例外，Content-Disposition 附件；凭据投影不进文件；不做管理员单用户导出=P1-05）与 §4.12 `POST /api/auth/delete-account`（密码确认 + 2FA 开启者双确认 → 软删（与 §8.2c 同口径，deleted_by=本人）+ 清 TOTP 注册 + 吊销全部会话；回执 deletedAtUtc/anonymizeAtUtc=+30 天（配置键 `yilian.account.anonymize-grace-days` 与匿名化日调度同源）；已删重复调用 40900「账号已删除」幂等口径裁决；无自助撤销预披露）；V17 `users.anonymized_at`（DATA-MODEL §5.1）；§10.1 矩阵三行（export/delete-account/匿名化 Job）；§10 头部日期 2026-11-18→2026-11-19 | 实现工程师 |

---

## 12. OpenAPI 导出约定

- `docs/openapi.yaml` 只导出 `implemented` 端点；`planned` / `transitional` 不出现。
- 每个 `@Operation`/`@Schema` 与本文语义一致；Javadoc 与 OpenAPI 不允许两套说法。
- `ApiResponse<T>` 作为统一信封；错误码用 `ApiCode` 枚举，`oneOf`/`discriminator` 不另造。
- 时间字段 schema `format: date-time`；`date` 仅用于 `Today.date`/`user_daily_stats.stat_date`。
- 字段名严格用本文 camelCase；数据库 snake_case 只在 `DATA-MODEL.md` 出现。
- 导出后做三方对照：端点、字段名、必填项、错误码；结论写进交付总结。
