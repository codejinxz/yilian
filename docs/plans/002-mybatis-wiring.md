# 计划：002-mybatis-wiring（MyBatis 正确接线与首个真实消费者）

## 元信息
- 任务：MyBatis starter 声明后完全休眠（0 @Mapper / 0 MapperScan / 0 yml 配置）——按 AGENTS §3.1 契约正确接线，并把首个「复杂查询」迁给 MyBatis
- 类型：refactor（基础设施接线 + 一条只读复杂查询迁移）
- 通道：full（S0 guard：500 行 > 200）
- 计划文件：docs/plans/002-mybatis-wiring.md

## 背景（why）
`mybatis-spring-boot-starter` 4.1.0 在 pom 中声明（白名单内），但全仓库无 @Mapper/@MapperScan/无 mybatis 配置——依赖完全未生效，AGENTS §3.1「JPA 写 + MyBatis 复杂查询」的双持久层契约当前只兑现了 JPA 半边。用户指示处理。首个合法消费者选定 `ContentQueryServiceImpl.findPracticeCandidates`：现状为「2 条 JPA 查询 + 内存 join + 快照缺失静默丢弃」，属契约中「复杂查询」类别，且有真实生产消费端（练习拉题）。

## 目标与非目标
- 目标：MyBatis 基础设施接线（yml + MapperScan 约定包 `**/dao/mybatis`）并以真实 MySQL 集成测试证明可用；练习候选查询迁为单条 MyBatis JOIN（只读 DTO，Dao 契约以 `Dao` 结尾，服务行为不变）。
- 非目标：不动其余 JPA 查询（findChangedSince 等 §4.5 明令不动）；不迁移 common 的 JdbcTemplate 记录器（简单写入，属横切豁免）；不做 JSON TypeHandler（本查询 JSON 列按 String 读取即可，波次三 typed-JSON 需求出现时随测引入）；不改前端；不预建波次二/三端点。

## 方案说明（≤5 行）
1. `application.yml` 增 `mybatis.mapper-locations: classpath*:mapper/**/*.xml` + `configuration.map-underscore-to-camel-case: true`。
2. `common/config/MyBatisConfig`：`@MapperScan("io.github.codejinxz.yilian.**.dao.mybatis")`。
3. 新增 `content/dao/PracticeCandidateQueryDao`（契约）+ `content/dao/PracticeCandidateRow`（只读 record）+ `dao/mybatis/PracticeCandidateQueryMapper`（@Mapper + XML 动态 SQL）+ `dao/mybatis/MyBatisPracticeCandidateQueryDao`（@Repository 适配，镜像 JpaXxxDao 模式）。
4. `ContentQueryServiceImpl.findPracticeCandidates` 改为注入新 Dao、单查询直出 SentenceDetail；`ContentQueryServiceImplTest` 桩同步。
5. 集成测试（AuthFlow 模式：yilian_test + JdbcTemplate 播种）证明扫描/驼峰/动态 SQL/可见性过滤/快照缺失丢弃/limit 语义。

## AC 清单（简式一行，映射并入）
- AC-1: GIVEN 播种了公开/私有、发布/草稿/软删混合的题库与句子 WHEN 调用 findPracticeCandidates（匿名/指定题库/排除句/limit）THEN 返回与原两步实现语义一致的候选（仅发布题库×发布未删句子、排除句生效、快照缺失行丢弃、createdAt+id 稳定排序、limit 生效） → 新增 PracticeCandidateQueryDaoIntegrationTest（用例名含 [AC-1]）[AC-1]
- AC-2: GIVEN MyBatis 接线 WHEN Spring 全量上下文启动 THEN MapperScan 注册生效、XML 加载、驼峰映射可用（由 AC-1 集成测试的上下文加载同证）[AC-2]
- AC-3: GIVEN 服务层单测 WHEN 跑 ContentQueryServiceImplTest THEN 既有 22 用例语义保持（候选查询桩切到新 Dao，断言不变或等价）[AC-3]
- AC-4: GIVEN 波次一修复 WHEN mvnw test 全量 THEN 0 Failures / 0 Errors（245 + 新增用例）[AC-4]
- AC-5: 前端不受影响（无 web 文件改动），三件套基线保持 [AC-5]

## 文件级改动清单
| 文件 | 改动 |
|---|---|
| server/src/main/resources/application.yml | 修改：mybatis 配置块 |
| server/src/main/java/io/github/codejinxz/yilian/common/config/MyBatisConfig.java | 新增：@MapperScan |
| server/src/main/java/io/github/codejinxz/yilian/content/dao/PracticeCandidateQueryDao.java | 新增 |
| server/src/main/java/io/github/codejinxz/yilian/content/dao/PracticeCandidateRow.java | 新增 |
| server/src/main/java/io/github/codejinxz/yilian/content/dao/mybatis/PracticeCandidateQueryMapper.java | 新增 |
| server/src/main/java/io/github/codejinxz/yilian/content/dao/mybatis/MyBatisPracticeCandidateQueryDao.java | 新增 |
| server/src/main/resources/mapper/PracticeCandidateQueryMapper.xml | 新增 |
| server/src/main/java/io/github/codejinxz/yilian/content/service/impl/ContentQueryServiceImpl.java | 修改 |
| server/src/test/java/io/github/codejinxz/yilian/content/service/impl/ContentQueryServiceImplTest.java | 修改 |
| server/src/test/java/io/github/codejinxz/yilian/content/dao/PracticeCandidateQueryDaoIntegrationTest.java | 新增 |

## 预期产物清单
| 产物路径 | 类别 |
|---|---|
| docs/plans/002-mybatis-wiring.md、docs/pr/002-mybatis-wiring-pr.md、docs/retro/friction-mybatis-wiring.md、上表 5 个新增文件 | must-create |
| application.yml、ContentQueryServiceImpl.java、ContentQueryServiceImplTest.java | may-modify |
| 无 | may-delete |

## 风险与回滚
- 风险 1：mybatis-spring-boot 4.1.0 与 Boot 4.1 自动装配兼容性未知 → 集成测试首验；若自动装配不生效，改手工 SqlSessionFactory Bean（同样在集成测试暴露）。
- 风险 2：SQL 语义与 JPA 版有偏差（排序、可见性）→ 集成测试逐条对拍锁定。
- 回滚：单 commit revert；不改表、不改契约。

## 任务分解
1. [ ] M-1 接线 + Dao 四件套 + 服务迁移 + 双层测试（AC-1..4）
2. [ ] M-2 验证 + 证据 + 审查 + PR 文档 + retro（AC-5）

## 退回记录
| 日期 | 从哪退回 | 原因 | 改了什么 |
|------|---------|------|---------|
| 2026-10-29 | S4 隔离审查 | blocker=0，7 条非 blocker 裁决：① 集成测试 visibility 硬编码 PUBLIC，「私有题库参与候选」腿无真实保护 → **已修**（insertBank 参数化，104 播 PRIVATE）；③ limit 契约无守卫 → **已修**（适配器前置校验）；④ 旧两步查询成死代码 → **已修**（SentenceDao/JpaSentenceDao/SentenceJpaRepository 的 findPracticeCandidates 删除，findVersionsByIds 保留仍有调用方）；② limit 边界差（缺快照行不再占名额）→ 行为改进，Dao Javadoc 补边界差说明留痕；⑤ 测试口令与 AuthFlow 同源先例一致 → 留痕不修；⑥ 全量计数留痕 → PR 002 补 248/0/0；⑦ AGENTS.md 指针行 → 前波已裁决。非 blocker 修复在 S5 前落地（blocker=0 不触发强制全量重审），由人裁量。 |
| 2026-10-29 | 独立分析复核 | 「有真实生产消费端（练习拉题）」表述不准确：findPracticeCandidates 无 Controller 调用方（原 JPA 实现同样为练习切片预埋无调用） | 更正见 PR 002 更正节：定性为「首个 MyBatis 承载的复杂查询（端到端接线 + 对拍测试），HTTP 消费者随波次三落地」 |

## 批准记录
- 批准通道：unattended（自主会话，用户已在任务中直接指示本工作，原因与决策随汇报披露）
- 批准模式：std
