# 命名与工程结构

## 产品名

**译练**，英文标识 `yilian`。中文名用于产品与传播，`yilian` 只作仓库、包、命名空间等技术标识，不做海外消费品牌。

## 坐标

| 项 | 值 |
|---|---|
| Maven groupId | `io.github.codejinxz` |
| Maven artifactId | `yilian-server` |
| Java 根包 | `io.github.codejinxz.yilian` |
| 启动类 | `io.github.codejinxz.yilian.YilianApplication` |
| 配置前缀 | `yilian.*` |
| 前端包名 | `@yilian/web`（private） |
| 仓库 | `github.com/codejinxz/yilian` |
| Docker 镜像 | `ghcr.io/codejinxz/yilian-server:<sha>` |

groupId 走 GitHub 验证路径，不需要自购域名；以后发布到 Maven Central 时按 GitHub 流程验证 `io.github.codejinxz` 即可。

## 仓库

单 monorepo，不拆双仓库：

```
yilian/
├─ AGENTS.md
├─ README.md
├─ docs/
├─ prototype/
├─ server/            # yilian-server
└─ web/               # @yilian/web
```

## 后端

```xml
<groupId>io.github.codejinxz</groupId>
<artifactId>yilian-server</artifactId>
<name>yilian-server</name>
```

```java
package io.github.codejinxz.yilian;

@SpringBootApplication
public class YilianApplication { ... }
```

```yaml
yilian:
  bootstrap-admin:
    enabled: true
```

## 包结构

按功能分包，单模块；实体放在各自模块内，根级不设 `entity`。

```
io.github.codejinxz.yilian
├─ YilianApplication
├─ common/                     # 横切基础设施
│  ├─ api/                     # ApiResponse、ApiCode、全局异常
│  ├─ config/                  # 缓存等通用配置
│  ├─ logging/                 # 结构化日志、@OperationLog 切面、error_log 记录与脱敏
│  ├─ concurrent/              # 虚拟线程/受控线程池、上下文传播、超时降级
│  ├─ storage/                 # ObjectStorage 与 LOCAL/云 OSS SPI
│  ├─ ratelimit/               # @RateLimit + Redis 滑动窗口
│  ├─ idempotency/             # @Idempotent + 唯一键幂等
│  ├─ security/                # 过滤器、JWT、CSRF、会话安全
│  └─ util/                    # 纯函数工具：字符串、时间、ID、密码、用户名、Web
├─ auth/                       # 认证
│  ├─ controller
│  ├─ service                  # 接口
│  ├─ service/impl             # 实现类
│  └─ dto
├─ system/                     # 用户与系统基础设施
│  ├─ bootstrap                # bootstrap 管理员启动钩子
│  ├─ dao                      # Dao 接口
│  ├─ dao/jpa                  # JpaRepository 与 Jpa*Dao 适配器
│  ├─ entity
│  ├─ service                  # 接口
│  ├─ service/impl
│  └─ service/dto              # 跨模块只读 DTO
├─ upload/                     # 通用上传/导入：文件上传、断点续跑、批次状态
│  ├─ api                      # 模块级异常出口
│  ├─ bootstrap                # 启动恢复钩子
│  ├─ config                   # multipart/异步专用配置
│  ├─ controller
│  ├─ dao                      # Dao 接口
│  ├─ dao/jpa                  # Jpa*Dao 适配器与仓库
│  ├─ dto
│  ├─ entity
│  ├─ exception                # 模块业务异常
│  ├─ service                  # 接口
│  ├─ service/impl             # *Impl 实现
│  ├─ support                  # 策略/流读取等无状态支撑
│  └─ validation               # 行校验 SPI
├─ content/                    # 题库、句子、导入、批次（后续）
├─ practice/                   # 答题、判分、SRS、复习（后续）
├─ ai/                         # 供应商、Prompt、用量、预算（后续）
├─ admin/                      # 用户管理、审计、运维（后续）
└─ analytics/                  # 统计、看板（后续）
```

约束：

- `common` 只放跨模块基础设施，业务逻辑回各自模块；`common` **不强制** `service`/`service.impl` 双层，避免为无替换价值的实现制造 `Impl` 样板。
- 模块内分层固定 `controller` → `service`（接口） → `service.impl`（`*Impl`） → `dao`；启动钩子放 `bootstrap`，模块异常放 `exception`，DAO 适配器放 `dao/jpa`。
- Controller 不得直接调用 Dao/Repository；跨模块只调 `service` 接口，传递 DTO。
- `common` 组件只有在类数 >10 且跨 ≥4 种角色时才拆 `annotation`/`aspect`/`filter` 子包；`logging` 已达到该阈值，但本轮不做纯搬迁，后续新增注解/过滤器时优先拆分。
- 新增顶层模块必须先更新本文件与 AGENTS，不得以“临时目录”绕过评审。
- `upload` 是通用上传/导入基础设施；具体导入业务仍保留 `ImportBatch*` / `ImportFile*` / `ImportRow*` 类名与 `import_batch` 表名，不随模块名泛化而重命名。

## 统一响应

- `ApiResponse<T>` 放 `common/api`，不放 `util`。
- 所有 `/api` 接口统一返回 `ApiResponse<T>`，HTTP 状态码保留表示成功/失败类别。
- 失败统一用 `ApiCode` 枚举业务码，不设默认 400 兜底。
- 工厂方法：成功用 `success(...)`，只有提示文案用 `successMessage(...)`，失败用 `fail(...)`；不再使用 `ok`/`message`。

## 技术版本

| 项 | 版本 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.x（如锁定 3.5，用 3.5.16） |
| MySQL | 8 |
| 前端 | React 19 + TypeScript + Vite |

Java 21 用法：

- `record` 用于 DTO、`@ConfigurationProperties`、值对象，不用于 JPA 实体。
- `sealed` 用于封闭判定集，如 `Correct / NeedsWork / Skipped`。
- 虚拟线程开启 `spring.threads.virtual.enabled=true`，不单独写 `VirtualThreadConfig`。

## 前端

| 项 | 值 |
|---|---|
| 目录 | `web/` |
| 包名 | `@yilian/web` |
| 可见性 | `"private": true` |
| 构建 | Vite + React + TypeScript |

## 命名规则

- Java 包名全小写，不用下划线、连字符、大写。
- Maven `artifactId` 用连字符。
- npm 包名小写，优先 scoped。
- 不写 `...yilian.server`，`server` 重复且无信息量。
- 目录用 `web`，不用 `fronted` 或 `frontend`。
- Docker 标签用 commit sha，不用 `latest` 部署。

## 域名

`yilian.com`、`yilian.dev`、`yilian.app`、`getyilian.com` 均已注册，暂不购买。

需要上线时优先：

- `yilian.study`、`yilian.tools` 等后缀；
- 已有域名下开子域：`yilian.<你的域名>`。

不为域名更换产品名。

## 备选名

| 名称 | 结论 | 原因 |
|---|---|---|
| **译练 / yilian** | 采用 | 语义透明、方向无关、两字好记 |
| `sentencelab` | 备选 | 无冲突，记忆度一般 |
| `recast` | 否决 | npm 同名库周下载量极高，搜索被压 |
| `transdrill` | 否决 | 域名已被注册，且与牙科器械同名 |
| `zh2en` | 否决 | 语言方向写死，无法扩展 |

商标建议核查第 9 / 41 / 42 类。
