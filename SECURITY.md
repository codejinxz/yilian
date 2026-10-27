# 安全策略

## 密钥与凭据红线

- 密钥只进环境变量或 secret；不进 git、不进日志、不进数据库。
- 本机开发凭据只放在 `server/config/application-local.yml`（已被 .gitignore 忽略），仓库仅提交 `.example` 模板。
- prod 缺失默认凭据或 JWT secret 时启动直接失败。

## 已部署的安全机制

- 双 token：15 分钟 access JWT（前端内存）+ refresh 会话（HttpOnly Cookie、Spring Session JDBC、登出/改密吊销）。
- CSRF 双提交 Cookie；`/api/admin/**` 服务端鉴权；登录限流与幂等以数据库唯一约束兜底。
- 已知未覆盖项见 `docs/GAP-ANALYSIS.md`（2FA、限流全链路实测等属于上线前门槛）。

## 报告漏洞

请不要以公开 issue 报告未披露的漏洞。通过 GitHub 私信联系 [codejinxz](https://github.com/codejinxz)，
或在仓库内以 `security:` 前缀的受限 issue 说明影响面。收到后 72 小时内响应。
