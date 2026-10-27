# 脚手架启动与验证（BOOTSTRAP）

本文件描述如何在一台新机器上从零跑通本项目：账号认证、题库拉取、练习（本地引擎）、管理端演示。
当前能力边界见文末「未实现清单」，实现状态矩阵见 `docs/API.md` §10。

## 1. 前置工具

| 工具 | 版本 / 说明 |
|---|---|
| JDK | 21（当前验证：21.0.9） |
| Maven | 使用仓库自带 `server/mvnw.cmd`（Maven Wrapper 3.9.11），无需全局安装 |
| Node.js | 24（当前验证：24.15.0） |
| pnpm | 12.x，通过 Corepack 或全局安装；`web/package.json` 已固定 `pnpm@12.15.0` |
| Docker Compose | v2+（CLI 当前 v5.1.4；本机 daemon 未运行，healthcheck 未实测） |
| 浏览器 | 支持 Cookie / ES2022 |

```powershell
java -version
node -v
corepack enable pnpm
pnpm -v
docker compose version
```

## 2. 首次启动

### 2.1 本地配置

本机已安装 MySQL 和 Redis 时，直接使用本机服务，不需要 `.env`，也不需要 Docker。

后端本地值统一放在 `server/config/application-local.yml`。该文件含本机明文凭据，
已被 `.gitignore` 忽略，并且**不在 classpath 中，不会打进 jar**；仓库只提交占位模板
`server/config/application-local.yml.example`。新机器首次启动时先复制模板：

```powershell
Copy-Item server/config/application-local.yml.example server/config/application-local.yml
```

至少把复制后的 `application-local.yml` 中 `spring.datasource.password` 改成本机 MySQL 的
真实密码。模板中的占位值：

- MySQL：`localhost:3306`，开发库 `yilian`，账号 `root` / `change-me-local-db-password`
  （必须改成真实密码后才能启动）
- Redis：`localhost:6379`，无密码
- JWT：`local-dev-only` 前缀的开发示例密钥，只能用于 local，不能复用到其他环境
- bootstrap 管理员：`admin` / `change-me-now`

不要提交修改后的 `application-local.yml`，也不要把它复制到共享环境。部署到 prod 时必须通过
环境变量/secret 提供数据库凭据和至少 32 字节的随机 `YILIAN_JWT_SECRET`；access token TTL
只能是 10–15 分钟。prod 不读取 `.env`。

### 2.2 确认 MySQL 与 Redis

```powershell
Get-Service MySQL80, Redis
```

两个服务应为 `Running`。可用下面的只读命令确认账号和库：

```powershell
mysql --host=127.0.0.1 --port=3306 --user=root -p -e "SHOW DATABASES LIKE 'yilian'; SELECT 1;"
```

`yilian` 库不存在时先创建（只建库，不建业务表；Flyway 负责建表）：

```sql
CREATE DATABASE IF NOT EXISTS yilian
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

Redis 无密码时直接用 `redis-cli -h 127.0.0.1 -p 6379 PING` 验证应返回 `PONG`。
`docker-compose.yml` 仅作为可选方案保留，使用它时需要自行设置
`YILIAN_DB_PASSWORD`、`YILIAN_DB_ROOT_PASSWORD` 等环境变量。

### 2.3 启动后端

```powershell
cd server
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.arguments=--spring.config.additional-location=optional:file:./config/"
```

必须显式指定 profile，并通过 `additional-location` 加载 `server/config/application-local.yml`。
项目不再提供默认 profile：不设 profile 时 JWT secret、数据库凭据等关键配置为空，应用会直接启动失败，避免生产漏配置时回退到 local。

首次启动会：

1. Flyway 按版本执行 `db/migration/` 下的迁移（V1–V8：baseline、error_log、operation_log、限流与幂等、账号与审计、import_batches、content_core、practice_core）；
2. Hibernate 以 `ddl-auto=validate` 校验表结构；
3. 创建 bootstrap 管理员（默认 `admin` / `change-me-now`，仅 local）；
4. 在 8080 端口提供服务，日志为 JSON（含 `traceId`、脱敏 `userId`、
   `module`、`action`、`durationMs`；带 `@OperationLog` 的写接口额外输出操作结果）。

### 2.4 认证模型（JWT 双 token）

- access token：15 分钟 JWT，只保存在前端内存，请求头
  `Authorization: Bearer <token>`。
- refresh token：不透明值，Spring Session JDBC 保存；Cookie 名默认
  `YILIAN_REFRESH`，`HttpOnly + SameSite=Strict + Path=/api/auth`，30 天有效。
- 刷新：`POST /api/auth/refresh` 使用 refresh cookie 换新 access token，并轮换
  session id；旧 refresh cookie 立即失效。
- 登出/改密：吊销该用户全部 refresh 会话；旧 access token 最多再存活 15 分钟，不使用
  JWT 黑名单。
- CSRF：登录、刷新、登出以及所有非 GET 请求都要回填 `X-XSRF-TOKEN`。
- 操作日志：写接口标注 `@OperationLog(module, action)` 后由 AOP 输出操作者、IP、结果与耗时；可选的 `targetType/targetId/reason` 会一并写入审计表；读接口不标。
- 异常日志：只有未知异常写入 `error_log`（V2 迁移）；密码错误、参数校验、404/405/415、越权/CSRF 不写。

### 2.5 启动前端

另开一个终端，在仓库根目录：

```powershell
pnpm --dir web install
pnpm --dir web dev
```

打开 <http://localhost:5173>。Vite 把 `/api`、`/actuator` 代理到
`http://localhost:8080`。若 5173 被系统保留端口段占用（报 `EACCES`），可用
`pnpm --dir web dev --port 5273` 换端口。

- 演示模式：`VITE_USE_MOCK=true pnpm --dir web dev` 启用前端 mock 演示层（管理端九页签、
  演示账号 admin / change-me-now），不依赖后端。
- 游客模式：不登录可直接练习；练习/复习/统计数据由前端本地引擎存于本机（IndexedDB）。
- 生产构建的 Service Worker 仅在 `vite build` 后生效，dev 不注册。

前端骨架说明：

- Tailwind CSS 4 通过 `@tailwindcss/vite` 接入；`@theme` 中放「稿纸与朱笔」色板。
- shadcn/ui CLI 未初始化（需要交互式初始化），当前用
  `web/src/components/ui/` 下的最小 Button / Card / Input 作为占位；后续可再迁移到
  shadcn/ui，不影响页面结构。
- pnpm 12 默认拦截依赖构建脚本，仓库根目录的 `pnpm-workspace.yaml` 显式允许
  esbuild，否则 `pnpm install` 会以 `ERR_PNPM_IGNORED_BUILDS` 结束。

## 3. 默认管理员与改密

- local：默认用户名 `admin`，密码 `change-me-now`。用于本机启动，不要用于任何
  共享环境。
- prod：必须提供 `YILIAN_BOOTSTRAP_ADMIN_USERNAME` 与
  `YILIAN_BOOTSTRAP_ADMIN_PASSWORD`；密码至少 12 字符，且不能等于用户名、不能是
  `change-me-now` 或常见弱口令，否则**启动直接失败**。同时必须提供
  `YILIAN_JWT_SECRET`（至少 32 字节随机值，TTL 10–15 分钟）。
- 密码以 BCrypt（strength 10）写入 `app_user.password_hash`，不写 yml、SQL、日志，
  也不进入 git。
- 改密（当前没有改密页面，使用一次性恢复开关）：

```powershell
# 1. 修改 server/config/application-local.yml 中的 bootstrap 密码
# 2. 临时把 reset-password-on-startup 设为 true
# 3. 重启后端一次，确认新密码可登录
# 4. 把 reset-password-on-startup 改回 false，再重启
```

重置会吊销该管理员已有的 refresh 会话，旧 access token 最多再存活 15 分钟；日志只记录
“已重置/已吊销”，不记录密码。

## 4. 健康检查与登录链路（curl）

以下命令在仓库根目录执行，使用 `application-local.yml` 中的管理员密码。

```powershell
# 1) 公开健康检查（actuator 不属于 /api，不套 Result）
curl.exe -s http://localhost:8080/actuator/health
# => {"groups":["liveness","readiness"],"status":"UP"}

# 2) 取 CSRF 令牌，同时写入 cookies.txt（令牌在响应的 data.token 中）
$csrf = (curl.exe -s -c cookies.txt http://localhost:8080/api/auth/csrf | ConvertFrom-Json).data.token

# 3) 登录（把 JSON 写入临时文件，避免 PowerShell 吞掉引号）
$loginFile = Join-Path $env:TEMP 'yilian-login.json'
Set-Content -Path $loginFile -Value '{"username":"admin","password":"change-me-now"}' -Encoding ascii
$login = curl.exe -s -b cookies.txt -c cookies.txt -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $csrf" --data-binary "@$loginFile" http://localhost:8080/api/auth/login
$login
# => {"code":0,"data":{"accessToken":"eyJ...","tokenType":"Bearer","expiresIn":900,"user":{...}},"message":"success"}
$token = ($login | ConvertFrom-Json).data.accessToken

# 4) 当前用户：必须带 Bearer access token
curl.exe -s -H "Authorization: Bearer $token" http://localhost:8080/api/auth/me
# => {"code":0,"data":{"id":1,"username":"admin","role":"ADMIN"},"message":"success"}

# 5) 用 refresh cookie 换新 access token（会轮换 refresh cookie）
$refresh = curl.exe -s -b cookies.txt -c cookies.txt -H "X-XSRF-TOKEN: $csrf" -X POST http://localhost:8080/api/auth/refresh
$refresh
# => {"code":0,"data":{"accessToken":"eyJ...","tokenType":"Bearer","expiresIn":900,"user":{...}},"message":"success"}
$newToken = ($refresh | ConvertFrom-Json).data.accessToken

# 6) 登出（HTTP 200 + Result；access token 是否过期都能调用）
curl.exe -s -i -b cookies.txt -c cookies.txt -H "X-XSRF-TOKEN: $csrf" -H "Authorization: Bearer $newToken" -X POST http://localhost:8080/api/auth/logout
# => HTTP/1.1 200
# => {"code":0,"data":null,"message":"已退出登录"}

# 7) 登出后 refresh 必须 401；登出已吊销该用户全部 refresh 会话。
#    先重新取 CSRF（登出会让旧 token 失效），再调用 refresh。
$csrf2 = (curl.exe -s -b cookies.txt -c cookies.txt http://localhost:8080/api/auth/csrf | ConvertFrom-Json).data.token
curl.exe -s -i -b cookies.txt -H "X-XSRF-TOKEN: $csrf2" -X POST http://localhost:8080/api/auth/refresh
# => HTTP/1.1 401
# => {"code":40100,"data":null,"message":"请先登录"}
```

所有 `/api` 接口统一返回 `ApiResponse<T>` 信封：`code` 是业务码（`0` 表示成功），
`message` 是提示文案，`data` 是业务数据；HTTP 状态码仍然保留，只表示成功/失败类别
（2xx 成功、4xx 客户端错误、5xx 服务端异常），具体原因看 `code`。错误同样
返回 `ApiResponse`，不再使用 `ProblemDetail`。

CSRF 规则：`XSRF-TOKEN` 是 HttpOnly=false 的 Cookie，`SameSite=Strict`，prod 下 `Secure`；
前端读取后回填 `X-XSRF-TOKEN` 请求头。登录、刷新、登出等非 GET 请求都必须带这个头；
服务端用 40301 表示 CSRF 失效，前端会重新获取一次 token 后重试。

## 5. 验证命令

```powershell
.\server\mvnw.cmd -q test
.\server\mvnw.cmd -q -DskipTests package
pnpm --dir web install
pnpm --dir web build
pnpm --dir web test
pnpm --dir web biome check
git diff --check
git status --short
```

说明：

- `mvn test` 会真实运行本机 MySQL 集成测试，不依赖 Docker / Testcontainers，也不 skip。
  当前存在 7 个已知 Error（upload→preview/commit 重构半成品导致 `import_batch` 表缺失），
  属存量问题，修复见 `docs/GAP-ANALYSIS.md` 当前状态。测试使用独立库 `yilian_test`，不会读写开发库
  `yilian`；默认以 `root / 123456` 连接 `localhost:3306`，可用
  `YILIAN_TEST_DB_HOST`、`YILIAN_TEST_DB_PORT`、`YILIAN_TEST_DB_USERNAME`、
  `YILIAN_TEST_DB_PASSWORD`、`YILIAN_TEST_DB_NAME` 覆盖。
- 前置条件：可用的 MySQL 测试实例；不满足时该集成测试会直接失败（不是 skip）。集成测试关闭了
  Redis 健康检查，不需要 Redis。
- 本机 MySQL/Redis 可用时，更直接的验收是 `application-local` 启动后按第 4 节 curl。
- 首次 `mvnw` 会下载 Maven 与依赖，耗时取决于网络；Docker Compose 仅作为可选基础设施，
  需要单独提供数据库密码环境变量。

## 6. 常见错误

| 现象 | 原因 / 处理 |
|---|---|
| `docker compose config` 警告变量为空 | Docker 方案需要自行设置 `YILIAN_DB_PASSWORD` / `YILIAN_DB_ROOT_PASSWORD`；本机服务方案不需要 Compose |
| `docker compose up` 报 daemon / pipe 错误 | Docker Desktop 未启动，或 WSL 不可用；先启动 Docker Desktop 并确认 Docker engine 在运行 |
| `Communications link failure` | MySQL 未就绪或端口不对；确认 `MySQL80` 服务、`application-local.yml` 的 URL 与账号 |
| `Access denied for user 'root'` | `application-local.yml` 的 root 密码不是本机 MySQL 的密码；先本地验证 |
| `Port 3306 is already allocated` | Docker Compose 与本机 MySQL 冲突；本机已有 MySQL 时不要启动 Compose |
| Flyway `validate failed` / checksum mismatch | 已执行的迁移被修改；不要改历史迁移，新增 `V2__...` |
| Hibernate `Schema-validation: missing table/column` | 实体与 `V1__baseline.sql` 不一致；改迁移或实体，禁止改成 `ddl-auto=update` |
| 登录 403 | CSRF 缺令牌或 Cookie 被拦；先 `GET /api/auth/csrf`，带 Cookie 与 `X-XSRF-TOKEN`；服务端返回 40301 表示 CSRF 失效 |
| 登录 401 | 用户名/密码错误或账号被禁用；local 默认密码是 `change-me-now` |
| `/api/auth/me` 401 | 未带 `Authorization: Bearer`、access token 已过期且 refresh 失败、或已登出 |
| `/api/auth/refresh` 401 | refresh Cookie 缺失/过期/已轮换/已登出；重新登录 |
| prod 启动报 placeholder / 默认密码 | 补齐 `YILIAN_DB_*`、`YILIAN_BOOTSTRAP_ADMIN_*`，密码至少 12 字符且不能是 `change-me-now` |
| prod 启动报 JWT secret | 设置 `YILIAN_JWT_SECRET` 为至少 32 字节随机值，TTL 10–15 分钟；不能以 `local-dev-only` 开头或含占位符 |
| 未设置 profile 启动失败 | 必须显式 `--spring.profiles.active=local` 或 `prod`；项目没有默认 profile |
| `/actuator/health` 为 DOWN | 通常是 MySQL 或 Redis 不可达；`management.endpoint.health.show-details` 默认不暴露细节 |

## 7. 未实现清单

实现状态矩阵见 `docs/API.md` §10。当前明确不含：

- Practice / Analytics 服务端（#8 练习主链路；练习/复习/统计由前端本地引擎承担）
- guest-upgrade 游客数据迁移（M2）
- 管理端检索与写接口（用户管理、审计/异常检索、题目与题库管理、模型配置；管理端九页签为演示态）
- 深度批改（M3）、FSRS 排程（V2，字段已预留）
- 登录限流、2FA、access token 即时吊销增强
- `docs/openapi.yaml` 导出

## 8. 下一步（按优先级）

1. #8 练习主链路（服务端判分/复习/统计，upload→preview/commit 收尾一并做）。
2. guest-upgrade 游客数据迁移（M2 契约已定）。
3. 管理端检索与写接口，管理九页签从演示态转真实。
4. 深度批改（M3）与 FSRS（V2）。
