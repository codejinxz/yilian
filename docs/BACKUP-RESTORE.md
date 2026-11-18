# 备份与恢复（Backup & Restore）

> 适用形态：单机 MySQL 8（dev / prod 同构）。脚本在 `scripts/`，快照落 `server/data/snapshots/`（gitignored——快照含真实用户数据，不入库）。
> 门槛对照（GAP 上线前硬门槛）：RPO ≤ 15min / RTO ≤ 4h / 季度演练。现状差距如实登记于 §6，不假装达标。

## 1. 备份策略

| 项 | 现状 | 说明 |
|---|---|---|
| 全量 | `scripts/snapshot.ps1`（mysqldump `--single-transaction`） | **迁移前必跑**（§4 SOP）；日常全量按日执行（定时调度未建，§6 登记项） |
| 位点 | `--source-data=2` | 快照头部注释记录 binlog 位点，是 PITR 的重放起点（§3） |
| 增量 | binlog（服务端已开启：ROW + `binlog_row_image=FULL`） | 同机磁盘保留 30 天（`binlog_expire_logs_seconds=2592000`） |
| 保留 | 快照目录默认保留最近 14 份（`-Keep`，两周日备） | 只清理目录名形如 `yyyyMMdd-HHmmss` 的目录 |
| 存储 | `server/data/snapshots/<时间戳>/<库名>-full.sql.gz`（gzip） | 本机单副本；**异地副本未建**，公网部署前升级项 |
| 内容 | 全表 + 存储过程 + 触发器 + 事件 | `--routines --triggers --events`；含 `flyway_schema_history`，还原后启动只校验不重放迁移 |

### 1.1 snapshot.ps1 用法（已实测）

```powershell
# 工作目录 = 仓库根
powershell -File scripts/snapshot.ps1 -Passwd '***'
```

| 参数 | 缺省 | 说明 |
|---|---|---|
| `-D` | `yilian` | 要备份的库名 |
| `-U` / `-P` / `-DbHost` | `root` / `3306` / `localhost` | 连接目标 |
| `-OutputDir` | `server/data/snapshots` | 输出根目录 |
| `-Keep` | `14` | 保留份数 |
| `-Passwd` | 无缺省 | 密码；缺省回落 `YILIAN_DB_PASSWORD` > `MYSQL_PWD` 环境变量，全缺时以 2 退出 |

**凭据纪律**：脚本不内置任何默认密码；密码经 `MYSQL_PWD` 环境变量传给客户端，不进命令行、不进日志。

退出码：`0` 成功；`2` 用法/参数错误；`3` mysqldump 失败；`4` dump footer 校验失败（末行缺 `-- Dump completed on`，视为截断）；`5` 压缩失败。失败写 stderr。

本机实测回执（2026-11-19，dev 库 yilian）：

```
快照完成: E:\code\yilian\server\data\snapshots\20261119-035412\yilian-full.sql.gz
大小: 146.7 KB（150200 bytes）
耗时: dump 0.5 s + 压缩 0.1 s = 总 0.6 s
binlog 位点: -- CHANGE MASTER TO MASTER_LOG_FILE='COMPUTER-bin.000129', MASTER_LOG_POS=162260797;
保留策略: 快照目录现存 2 份（Keep=14）
```

## 2. 恢复路径 A：全量恢复（restore.ps1，已实测）

```powershell
# 默认还原到隔离演练库 yilian_drill（工作目录 = 仓库根）
powershell -File scripts/restore.ps1 -Snapshot server/data/snapshots/<ts>/yilian-full.sql.gz -Passwd '***'
```

行为：按源库字符集/排序规则 drop+create 目标库 → mysql 导入（`.gz` 自动解压临时文件，导入完即删）→ 核心表行数对账（`users` / `sentences` / `sentence_versions` / `attempts` / `attempt_gradings` / `sentence_banks` / `prompt_versions` / `appeals` / `reviews`）。对账表把「快照后源库又有写入」的合法漂移与「还原不完整」都显性化：不一致时退出码 5，人工核对。

**防误操作护栏**（实测）：

- 目标库为 `yilian` 主库：拒绝，退出码 2；只有显式 `-Force` 才放行（高危：drop 现网数据后整库重导）。
- 系统库（`information_schema` / `mysql` / `performance_schema` / `sys`）：一律拒绝。
- 目标库与对账源库同名：拒绝（防止对账语义失效的同库自还原）。
- 快照路径含空白字符：拒绝（mysql `source` 传参限制）。
- 对账源库不存在：拒绝（退出码 2），不产生任何建库副作用。

退出码：`0` 成功且对账一致；`2` 用法/护栏拒绝；`3` 快照缺失/格式不支持/解压失败；`4` 建库或导入失败；`5` 对账不一致。

实测回执（2026-11-19，还原 `yilian_drill`）：导入 1.5 s，9 表对账全部一致，位点与快照头部一致。

**演练/验证场景**：还原到 `yilian_drill` 后在 8081 起独立实例跑登录+判分（§7 演练制度）；确认无误后如需覆盖主库，才用 `-TargetDB yilian -Force`。

## 3. 恢复路径 B：PITR（binlog 重放）【未实测】

> 本节步骤按 MySQL 官方语法编写，**本机未实测**（单机 dev 无连续性恢复的现实需求场景，诚实边界）。位点来源=快照头部注释（`-- CHANGE MASTER TO MASTER_LOG_FILE='...', MASTER_LOG_POS=...`）。

前提：目标实例的 binlog 链从快照位点开始连续保留（本机 `binlog_expire_logs_seconds=2592000`）。

步骤（示意）：

1. 先按路径 A 还原最近全量快照到目标库。
2. 从快照头部读取位点（文件名 + position）。
3. 重放该位点之后的全部变更：

```bash
mysqlbinlog --start-position=<快照位点> --stop-position=<目标位点或省略> \
  <binlog 文件...> | mysql -u<用户> -p <目标库>
```

4. 多个 binlog 文件按序重放（`SHOW BINARY LOGS` 确认连续性）；`--stop-position` 用于丢弃误操作时点之后的事务。
5. 重放后跑 restore.ps1 的对账口径人工核数。

**binlog 本机实测现状**（2026-11-19，`SHOW VARIABLES` / `SHOW BINARY LOGS` 原样）：

```
log_bin                          ON
binlog_format                    ROW
server_id                        1
binlog_row_image                 FULL
log_bin_basename                 D:\development\MySQL\MySQL Server 8.0\Data\COMPUTER-bin
binlog_expire_logs_seconds       2592000

COMPUTER-bin.000127    1004001    No
COMPUTER-bin.000128    237879849  No
COMPUTER-bin.000129    162260797  No
```

mysqlbinlog 在 PATH（`D:\development\MySQL\MySQL Server 8.0\bin\mysqlbinlog.exe`，8.0.44）。

## 4. prod 迁移前快照 SOP（迁移前必跑）

任何对 prod 库的 Flyway 迁移（以及 dev 的正式迁移），执行前必须完成：

1. **快照**：`powershell -File scripts/snapshot.ps1 -Passwd '***'`（prod 用 `-D <prod库名>` 与真实凭据，凭据走环境变量不进命令行）。
2. **核回执**：退出码 0、回执含 binlog 位点行、大小非零。产物自动归档进 `server/data/snapshots/<时间戳>/` 并按 Keep 清理。
3. 才允许执行迁移（`mvnw spring-boot:run` / 部署触发 Flyway）。
4. 迁移失败回滚 = §2 路径 A 全量恢复（必要时叠加 §3 PITR 追平）。

历史上 V11 起的手工 mysqldump 习惯（`server/data/pre-v*.sql`）由本 SOP 机制化替代。

## 5. 已应用迁移的注释编辑后：checksum 对齐（flyway repair）

**场景**：编辑了已应用迁移（V10–V17）的注释 → 已有库下次启动 Flyway validate 因 checksum 不一致失败；新库 / CI 不受影响。

**SOP**（单行命令，对 dev 库逐字实测可用：repair 对齐 checksum 后应用启动校验通过）：

```powershell
cd server
./mvnw.cmd -q org.flywaydb:flyway-maven-plugin:repair "-Dflyway.url=jdbc:mysql://localhost:3306/yilian" "-Dflyway.user=root" "-Dflyway.password=123456" "-Dflyway.locations=filesystem:E:/code/yilian/server/src/main/resources/db/migration"
```

说明：`-Dflyway.password` 按环境代入（上例为文档化 dev 默认值，仅限本机）；repair 只重写 `flyway_schema_history` 的 checksum，不动业务数据；执行后启动应用确认 validate 通过。prod 用真实凭据走环境变量执行，先完成 §4 快照。

## 6. RPO / RTO 实测与差距登记

| 指标 | 门槛 | 现状 | 结论 |
|---|---|---|---|
| RTO | ≤ 4h | **实测 ≈15.6 s**（2026-11-19 演练：还原 1.5s + 启动 13s + 登录 API 0.9s + 判分 0.17s；含人工读码 ≈30s） | 达标，余量 3 个数量级；主导项是实例启动 |
| RPO | ≤ 15min | 现形态 ≈ **24h**（每日全量 + 同机 binlog） | **差距如实登记**：binlog 只存本机，整盘故障时退回最近全量 |
| 演练 | 每季度 | 2026-11-19 首次实测 | 制度见 §7 |

**差距收敛项（公网部署前升级）**：① 每日全量的定时调度未建（当前=迁移前必跑 SOP + 手动；需任务计划/调度器）；② 快照与 binlog 的异地副本未建（真 15min RPO 需 binlog 实时/定时异地流式）；③ PITR 步骤未实测。

## 7. 季度恢复演练制度

每季度执行一次并留档：

1. `scripts/snapshot.ps1` 产出新快照。
2. `scripts/restore.ps1` 还原到 `yilian_drill`（隔离库，主库零风险）。
3. 8081 起独立实例（不动 8080 主实例）：`local` profile + 命令行覆盖端口与 datasource（实测命令见演练记录）。
4. 验证码登录 + 提交一次答题（本地判分），记录分步耗时与总 RTO。
5. `scripts/drill-cleanup.ps1` 清理（停实例+删演练库；护栏：拒绝 8080、拒绝系统库与主库、仅结束 java 进程）。
6. 记录落 `docs/analysis/restore-drill-<日期>.md`（本机资产），并在本文 §6 更新实测数字。

最近一次演练：2026-11-19，`docs/analysis/restore-drill-20261119.md`（RTO ≈15.6 s，达标）。
