# 待办与优先级

## 当前状态

- **034 Prompt 注册表写路径 + 黄金集回归资产 + R2-01 完成（2026-11-18，计划 [docs/plans/034-prompt-registry-write-golden.md](plans/034-prompt-registry-write-golden.md)）**：销 pending-20261117 P0-10（写半边）+ P0-11（黄金集）+ GAP 029 余量 R2-01 三项——①管理端 Prompt 版本治理三端点（`/api/admin/prompts` §8.18：GET 分页检索 name/label 过滤 011 口径、POST 登记 name 白名单/content ≤20000 码点/版本号服务端分配=label=CANDIDATE append-only、POST activate 仅 CANDIDATE 可激活同事务「旧 ACTIVE 降 CANDIDATE→新行 ACTIVE→PromptRegistry.evictAll 即时换轨」，已 ACTIVE 幂等、无 DELETE 历史快照不删，审计 PROMPT_REGISTER/PROMPT_ACTIVATE；controller 在 practice 域沿用 AdminAppealController 先例）；②黄金集分层回归资产（server/src/test/resources/golden/：grading-cloze 31 条确定性精确对拍+review 25/appeal 26/ask 12 结构+方向验收，每条带 rationale 人工复核说明，期望值=按判分语义人工推导非实现输出抄录）；③runner 双层（`GoldenGradingSetTest` 31 用例普通 JUnit CI 常跑对拍判分主链；`GoldenLlmSetTest` @Tag("golden") 双闸=golden.enabled 属性+网关探活 Assumptions，CI 不跑跳过逻辑由 `GoldenAssetsIntegrityTest` 证明；LLM 层本次实跑因 ZCode 网关账号冷却/JWT 失效按双闸设计正确跳过，网关恢复后由监督者复跑）；④README 新增「换模型/改 Prompt 前必跑黄金集」SOP 节（两步命令逐字+期望输出+结果记录位置）；⑤R2-01：`updateAdjudication` WHERE 加 `AND a.status='PENDING'`（初裁侧原子门，与 029 A-01 终审同款），行数=0 不落初裁列、回执按存储真相（终审态）投影不伪造「初裁成功」；跨套件纪律：PromptRegistryWritePathIntegrationTest 逐用例前后把 prompt_versions 恢复为 V13 种子状态（首版全量实测发现删种子行会让 V13/申诉初裁套件污染，已修复并四套件混合序复跑验证）。质量门禁实测：后端 `YILIAN_TEST_DB_NAME=yilian_test_034 mvnw test` **997 run / 0 Failures / 0 Errors / 0 Skipped**（033 记录基线 936，+61；本批新测试 58=切片 13+注册表集成 5+竞态集成 2+golden grading 31+资产完整性 7，其余 3 为基线漂移）+ `fmt:check` 通过 + JaCoCo 门禁随构建通过；前端 `vitest run` **212/212**（基线 207，+5：ModelsPage Prompt 区块列表/登记/激活/40000 透传/区块降级用例）+ `tsc --noEmit` 0 错误 + `vite build` 成功 + `biome check` 0 新增（44 条存量 warning 与 HEAD 持平）；LLM 黄金层实跑：当前 ZCode 网关账号全部冷却/JWT 失效（curl 直测返回「没有可用的 ZCode 账号」），双闸按设计正确跳过（Tests run: 0），网关恢复后由监督者按 README SOP 复跑并记录四层数字；黑盒 304 套件期望零变化（prompts 端点不在套件，复跑由监督者执行）；另登记：register 兜底把 content 超 TEXT 64KB 字节上限（≥16384 个四字节码点，20000 码点校验放行）误报 40900 且重试不收敛——触发极端罕见，后续项=字节长校验或迁 MEDIUMTEXT（S4 N-2）

- **032 管理员 2FA（TOTP 自研 RFC 6238）完成（2026-11-18，计划 [docs/plans/032-admin-2fa-totp.md](plans/032-admin-2fa-totp.md)）**：销 pending-20261117 P0-01（AGENTS §3.7「公网部署前管理员启用 2FA」硬前置）——后端 V15（users 三列 + user_2fa_recovery_codes，迁移前快照 server/data/pre-v15-2fa-snapshot.sql）+ TotpService 自研 RFC 6238（HMAC-SHA1/8 位/30s/±1 窗/常数时间比较，RFC 4648 Base32 手写，RFC 6238 附录 B 全部 6 个 SHA1 向量对拍）+ SecretCipher 抽取至 common/security（ProviderKeyCrypto 薄委托、公共形状零变化，027B 测试零改动全绿）+ 自助三端点（/api/auth/2fa setup/activate/disable + status）+ 登录第二步（40305 challengeToken 5 分钟 purpose 限定，与 access token 互不冒用；login/totp 验 TOTP 或恢复码，恢复码 10×8 位 BCrypt 落库使用落 used_at）+ 管理端 reset-2fa（允许自愈式自重置、未注册幂等 200、40400 口径）+ 前端三处（登录第二步表单/设置卡片/管理列与重置）+ mock 全量新端点；QR 码渲染本批不做（主人裁决：otpauth URI 文本+手动录入，后续点单）；「强制管理员开 2FA」全局开关不做（公网 SOP 文档承接，后续批）。质量门禁实测：后端 `mvnw test` **893 run / 0 Failures / 0 Errors**（基线 847，+45+R1 恢复码并发原子用例 1）+ S4 隔离审查 8/9→R1 修复应修×2（恢复码条件更新原子消费/§10.1 account 矩阵行）+反向 purpose 隔离测试后增量复审收口+ `fmt:check` 通过 + JaCoCo 门禁随构建通过；前端 `vitest run` **197/197**（基线 191，+6）+ `tsc --noEmit` + `biome:check` + `build` 全过；黑盒 304 套件期望零变化（admin 未开 2FA，登录路径不变，复跑由监督者执行）。
- **031 合规文档四件套起草稿完成（2026-11-18，计划 [docs/plans/031-compliance-docs.md](plans/031-compliance-docs.md)；纯文档批，无代码改动；同日 S4 退回 R1 措辞修正：B-1 参考译文复核链路口径、B-2 游客自动合并时机、B-3 导出范围收窄）**：销 pending-20261117 P0-15 起草部分——新建 `docs/compliance/` 四件：[privacy-policy.md](compliance/privacy-policy.md)（隐私政策）、[user-agreement.md](compliance/user-agreement.md)（用户协议）、[age-and-minor.md](compliance/age-and-minor.md)（年龄声明与未成年人保护）、[data-cross-border-statement.md](compliance/data-cross-border-statement.md)（模型服务与数据出境说明），均标注「起草稿 v0.1 · 待运营者终审」，逐条锚定实际数据流（游客数据全本地 IndexedDB 零上传／答疑与申诉不外发参考译文（复核链路按重新判分口径外发）／答疑内容不落库／llm_usage 只记元数据／操作日志 1 年·异常日志 30 天／密钥 AES-256-GCM 不回显不进日志）。**主人终审为后续门，终审通过前不进前端呈现**。占位项如实：待运营者确认＝模型服务商归属与数据保留政策（出境说明 §五、隐私政策 §八）、协议赔偿上限与管辖地（协议 §八/§十）、监护人联系方式（年龄声明 §五）；规划中＝自助删号 30 天冷静期匿名化（尚未上线，三件如实标注（出境说明不涉及该话题））＋账号内服务端记录的整体导出（隐私政策 §7.1 如实标注）；待填写＝运营者主体信息与联系方式（四件统一占位）。另登记：设置页 UI 文案「答题流水保留 90 天」与 D-011 待定口径冲突，随后续 UI 批修正。
- **030 API.md 过时声明修正完成（2026-11-18，计划 [docs/plans/030-api-md-stale-declarations.md](plans/030-api-md-stale-declarations.md)；纯文档批，无代码改动，随本行同提交入库）**：销 pending-20261117 §⑦-1/§⑦-2 两处过时声明——API.md §10.4 删「§6.1 D7 四参数未开发」（与 §10.1「2026-11-03 服务端补齐」自相矛盾，014 已落地）并将「FSRS 排程真值/申诉 LLM 裁决」移出未开发（分别已随 014/025 落地，§8.13/§6.4/§8.16 在案；深度批改 M3 保留）；§10.5 错误码行改写为已落地口径——40302/40303、40404–40405、40901–40905、41300、42200 已全量定义于 `ApiCode.java`（grep 全命中，40303/40404/40405/40904 有端点与接口级测试在用，§1.3 错误码表同步在案）；§10 头部日期 2026-10-31→2026-11-18；偏差修正：登录「用户名或邮箱」实证已随基线实现（`AuthServiceImpl`→`JpaAppUserDao.findByLogin`→`findByUsernameOrEmail(login, login)` 单查询，与 §4.2 契约一致；pending P1-18「零命中」系大小写敏感 grep 假阴），余项收窄为补邮箱登录接口级测试（API.md §10.5 v1.9 同步）。
- **029 双 skill 第一轮消缺批完成（2026-11-17，计划 [docs/plans/029-dual-skill-round1-fixes.md](plans/029-dual-skill-round1-fixes.md)，提交 `094b507`(AC-1 终审并发原子门)+`c411f20`(AC-2 复核并发串行化)+`4cc94aa`(AC-3 申诉前端超时)+`97d0a24`(AC-4 UNAVAILABLE 回执态+C-01 注释如实化)+`558a9e8`(AC-5 README 契约修正)+AC-7 本行随收口提交）**：双 skill 轮巡（[code-review-full-20261117](analysis/code-review-full-20261117/00-评审汇总.md) + [test-audit-full-20261117](analysis/test-audit-full-20261117/00-测试体检报告.md)，P0×0/P1×2/P2×5/P3×7）P1/P2 可修项一次消缺，P3 与依赖侧登记（见「029 轮巡余量」）。①A-01(P1)：申诉终审 UPDATE WHERE 携带 `status='PENDING'` 条件（并发原子门），行数=0 改判 40900「该申诉已被终审」——并发双管理员裁决恰一人 200、先到者结论不被覆盖、admin_audit_log 两行都在；②A-02(P2)：reviewAttempt 事务化（READ_COMMITTED）+ attempts 父行悲观锁串行化——并发复核 MODEL 行恰一条、模型只调一次（幂等回读语义不变），llm_usage 旁路记账改 REQUIRES_NEW 独立事务（降级回滚不再丢记账行，三终态契约保持）；③A-03(P2)：appealAnnotation 前端超时 35s（补齐 024 REVIEW_TIMEOUT_MS 同算术先例）；④F-01(P2)：初裁不可用回执态以存储 decision JSON 为真相三方对齐——占位回执 verdict="UNAVAILABLE" 如实发送，「AI 初裁暂不可用」分支转活，AC-7 测试夹具改与集成断言同形（mock 漂移修正）；⑤F-02(P2)：README 密钥双通道/Prompt 注册表 V13 落地/里程碑 020-028 契约修正+命令逐字校验；⑥C-01(P2) 注释部分：PromptRegistryImpl「负条目短 TTL」如实化；⑦DEF-01(P1 依赖侧)实测处置：**不改预算**——实测证明网关不生效 top-level system 字段（回复必为自由文本、strict JSON 解析必败，与超时预算无关），根因+修复方向登记 029 轮巡余量。质量门禁实测：后端 `YILIAN_TEST_DB_NAME=yilian_test_029 mvnw test` **847 run / 0 Failures / 0 Errors**（基线 840，+7：AC-1 并发集成 2+单测 2、AC-2 并发集成 2、AC-4 回执 1、AC-3 vitest 1）+ `fmt:check` 0 不合规 + JaCoCo 门禁过；前端 `vitest run` **191/191**（基线 190，+1）+ `tsc --noEmit` 0 错误 + `vite build` 成功 + `biome check` 0 问题（src/mocks 存量 warning 不变）；黑盒复跑 **304/304**（015 套件，验证码开+操作员读图原生模式，7/7 读码命中 0 降级，0 缺陷 0 信封违例）。

- **028 答疑 LLM legacy 态全字段可编辑完成（2026-11-17，计划 [docs/plans/028-tutor-config-editable.md](plans/028-tutor-config-editable.md)，提交 `3051409`(server 覆盖轨+tutor 密钥预留行)+`a41bb29`(切片/单测/集成用例)+`a23b4a0`(web 编辑态)+`70fa7bc`(API v1.7/DATA-MODEL v0.4 同步)+`0330a01`(格式归一)+`5c44ccd`(覆盖轨投影与轨道一致修复)，合并态 HEAD `5c44ccd`）**：答疑 LLM legacy 态全字段可编辑——覆盖轨（目录激活>覆盖>env）+ tutor 密钥复用 `llm_provider_keys` 预留 id + 来源徽标（环境变量/自定义/目录）+ 即时生效；40000 env-only 语义废除（§3.4 演进链 017→027B→028）。质量门禁实测：后端 `mvnw test` **840 run / 0 Failures / 0 Errors**（基线 816，+20+R1 4）、前端 `vitest run` **190/190**（基线 184，+5+R1 1）、S4 隔离审查 **6/6 PASS、BLOCKER=0**（R1 一轮增量解除）。
- **027B provider API 密钥管理端加密入库完成（2026-11-17，计划 [docs/plans/027b-provider-key-vault.md](plans/027b-provider-key-vault.md)，提交 `1579094`(server V14+加密+解析链)+`02ec7e9`(五端点写-only+模型页录入)+`37bf417`(缺密钥文案双通道)）**：provider API 密钥管理端加密入库——V14 建 `llm_provider_keys` 表+AES-256-GCM 加解密（主密钥 env `YILIAN_SECRET_MASTER_KEY`，prod 缺失 fail-fast）+解析链 DB>env>legacy；provider 目录五端点写-only `apiKey`（响应只投影 `keyLast4`，不回显明文）+模型页密钥录入即时生效。质量门禁实测：后端 `mvnw test` **816 run / 0 Failures / 0 Errors**（基线 794，+22）、前端 `vitest run` **184/184**（基线 181，+3）、S4 隔离审查 **6/6 PASS、BLOCKER=0**（1 轮增量：GAP 行补齐+构造器注释对齐后解除）。
- **025 申诉 LLM 裁决两级闭环完成（2026-11-06，计划 [docs/plans/025-appeal-llm-adjudication.md](plans/025-appeal-llm-adjudication.md)，提交 `79d017b`(server)+`3272177`(web)+`4de03e0`(API/DATA-MODEL 同步)）**：申诉裁决两级闭环——①同步 AI 初裁：`submitAppeal` 落库后同步调 LLM 产「AI 意见」（verdict+reason 落 decision/decision_reason/decided_by=LLM/decided_at/model_snapshot/prompt_version_id，**status 保持 PENDING** 不自动判分），失败/非法输出（重试 1 次）与限流（独立 scene appeal-adjudication 5 次/分钟）落 UNAVAILABLE 占位不阻塞提交，`llm_usage` scene=appeal 三终态双写；②管理端「申诉」第十页签（`GET /api/admin/appeals`+`POST /api/admin/appeals/{id}/decision`，仅 PENDING 可裁决否则 40900，必填终审理由，审计 APPEAL_DECIDE）人工终审 ACCEPTED/REJECTED；③ACCEPTED 进判分 overlay：本句 ACCEPTED APPEAL 的 (chunk_index,proposed_answer) 追加为该槽额外可接受答案（CLOZE norm-1 同口径，DISPUTE 排除语义不变，不物理改题目）；④V13 迁移建 `prompt_versions` 注册表（ask/review/appeal 三首版种子幂等，唯一 (name,version)），PromptRegistry 按 name 取 ACTIVE 版本（Caffeine/写后失效/缺行兜底常量），答疑/复核/申诉三链路 prompt_version_id 落值；⑤用户端透明度：批注行按回执分化展示「AI 意见:…/初裁不可用/等待人工终审/已加入可接受答案」。质量门禁实测：后端 `mvnw test` **794 run / 0 Failures / 0 Errors**（基线 761，+33）、前端 `vitest run` **181/181**（基线 168，+13）、S4 隔离审查 **9/9 PASS、BLOCKER=0**。
- **027 统一模型配置动态化 + 体验修正合并批完成（2026-11-06，计划 [docs/plans/027-config-dynamic-and-ux-batch.md](plans/027-config-dynamic-and-ux-batch.md)，提交 `a174cd1`(A 组)+`adbe017`/`d1b5b35`(B 组)+`4b6ba53`(R1 修复)，合并态 HEAD `4b6ba53`）**：A 组 ModelsPage provider 去预设——新增/编辑抽屉打开即空白表单（名称/协议三协议下拉/端点/模型/maxTokens 全字段动态编辑，保存即时生效），ZCode 网关预置模板与卡片内静态预设文案全部移除，顶部说明中性化为如实描述双通道（目录条目按 `YILIAN_LLM_KEY_<大写ID>` 从 env 取 key，密钥 UI 录入走已批准的 027B 另行批次），legacy env 形态卡保留只读并注明回退语义，022 五端点 API 契约零变化；B 组体验修正——填空判分结果区新增「按我的输入/按参考对位」双视图切换（多词块逐词对位着色，默认视图保持现状）、整句批注列表正确/错误语块样式分化（苔绿/朱红，答对语块不再作为错误条目展示）、定级提交与判分落定「已记录 ✓」微反馈（克制动效不弹窗）、今日启动台主 CTA 语义改「开始今日 N 题」（N=当日待练数，取自既有 today 聚合）且测试题库 chip 折叠收起、注册/登录页「本机记录并入账号」文案对齐自动同步实际行为（不承诺自动并入，如实描述）、批改方式开关先实测定位「只存不生效」后如实标注为演示（AC-9 三选一裁决，理由与定位记录见 PR）、会话恢复态 auth 恢复路径补登录 UI 置位（刷新后复核按钮与登录态一致，024 冒烟非阻塞观察收口）。质量门禁实测：前端 `vitest run` **168/168**（基线 147，+21）、`tsc --noEmit` 0 错误、`vite build` 成功；后端 `mvnw test` **761 run / 0 Failures / 0 Errors**（零 server 改动，与 026 基线持平）；S4 隔离审查 **11/11 PASS、BLOCKER=0**（含 R1 修复轮 1 次：注册页文案与自动同步行为矛盾修复 `4b6ba53`）。
- **026 管理页错误态 admin 变体收口 + 死查询清理完成（2026-11-06，计划 [docs/plans/026-errorstate-and-dead-query.md](plans/026-errorstate-and-dead-query.md)，提交 `0e3b4fa`）**：7 个管理页共 8 处 `ErrorState` 补 `variant="admin"`（OverviewPage/BanksPage/SentencesPage/UsersPage/GenerationPage/ErrorsPage 列表+详情抽屉两处/AuditPage——022R1 ModelsPage 先例的推广），断网/服务端不可达时错误卡为管理语境（标题「管理数据加载失败」+「管理数据不做本地暂存」提示），不再出现「已经填写的答案会保存在本机」等练习文案；新增 `errorstate-admin-variant` 防回退用例 3 例（概览页断网、异常日志列表与详情抽屉 5xx 均渲染管理语境）；删除零调用死查询链路三段——`SentenceDao.findByExternalKey` 声明、`JpaSentenceDao` 实现、`SentenceJpaRepository.findByBankIdAndExternalKey`（V11 后被 `findActiveByFingerprint` 生成列口径取代，全仓 main+test 零引用）。质量门禁实测：后端 `mvnw test` **761 run / 0 Failures / 0 Errors**、前端 `vitest run` **147/147**（基线 144，+3）、S4 隔离审查 **3/3 PASS、BLOCKER=0**。
- **024 模型复核接后端完成（2026-11-06，计划 [docs/pr/024-model-review-pr.md](pr/024-model-review-pr.md)）**：`POST /api/practice/attempts/{attemptId}/review` 真实接入——登录+归属校验（他人 40300/不存在 40403/REVEAL 40000）、LLM strict JSON {score,reason} 解析失败重试 1 次、追加 `attempt_gradings` sequence=首判+1、grader=MODEL 判分行（score 钳制 0–100、model_snapshot=providerId:model、latency_ms V8 预留列启用）、同 attempt 已有 MODEL 行幂等回读+并发撞唯一键回读；模型异常/输出非法不落行按 50000 语义降级（镜像答疑形态），`llm_usage` scene='review' 三终态双写；`@RateLimit(scene="model-review")` 5 次/分钟独立于答疑配额；排程隔离——错题池与 countWrong 明确改读首判行、FSRS 卡与 review_logs 零变动（集成测试逐字段断言）；前端 PracticePage 本地假复核占位全部移除，真实 mutation+Zod schema，成功展示新分+理由+「复核不影响复习计划」注记并刷新 attempt/today，仅登录态且有服务端 attemptId 显示入口（游客/本地引擎 grading.id=local 不显示），35s 超时同答疑口径；API.md §6.13+§2/§10.1 矩阵、DATA-MODEL §5.9/§5.18 同步。质量门禁：后端 `mvnw test` **761 run / 0 Failures / 0 Errors**（基线 739+，JaCoCo 门禁过，独立库 yilian_test_024）+ `fmt:check` 0 不合规；前端 `vitest run` **144/144**（+3）、`tsc --noEmit` 0 错误、`vite build` 成功、`biome check` 改动文件 0 问题；黑盒收口复跑 **304/304**（015 套件，验证码开+操作员读图原生模式，0 缺陷 0 信封违例）；S4 隔离审查 AC **9/9 PASS、BLOCKER=0**；浏览器冒烟走**降级路**——真实 LLM 经网关 6 次调用全部 30s 超时（账号池占用），前端 35s toast「请求超时，请重试」+原分保留+复核按钮可重发（成功路径未复现，与 PR 披露一致），复核按钮登录会话内可见/游客不可见，全程无「演示占位」，证据 [docs/analysis/browser-smoke-024/](analysis/browser-smoke-024/)。
- **CI Server job 稳定 8 红归因修复完成（2026-11-06，主人授权；报告 [docs/analysis/ci-fix-round2-20261105.md](analysis/ci-fix-round2-20261105.md)）**：根因=「会话属性表大小写与 Spring Session JDBC 运行时拼写不一致」——spring-session-jdbc 4.1.1 按 `table-name + 固定大写后缀 _ATTRIBUTES` 拼接属性表名（后缀不可配置），V1 建表为全小写 `spring_session_attributes`；Windows 本机 `lower_case_table_names=1` 大小写不敏感全绿，CI（Ubuntu mysql:8.4.13 容器，Linux 默认 lctn=0）表名大小写敏感 → 登录/注册在请求收尾保存会话失败（经 ERROR dispatch 被 RestAuthenticationEntryPoint 收敛为 40100「请先登录」）、`findByPrincipalName` 吊销查询直接 BadSqlGrammar——AuthFlow×5、PracticeMainFlowE2E×1、UserAdminLock 并发×2 共 8 例全此一因（两轮 CI run 1c085bc/e56607c 失败集一致）。修复：`V12__session_attributes_case_align.sql` 两段式 RENAME 对齐运行时精确拼写（Windows lctn=1 上直接 RENAME 报 1050 already exists，先临时名中转，lctn=0/1 双路径成立；历史迁移不动）+ 手写 SQL 四文件同步（`PartitionMaintenanceJob` 孤儿清理 SQL 与三集成测试清场语句），生产 Java 逻辑零改动。验证：Docker mysql:8.4.13 本地 A/B——pre-fix 复现 CI 逐字同构失败（16 run/7F+1E）、post-fix 16/16 全绿（`SHOW TABLES` 实证 `spring_session_ATTRIBUTES` 落地）；主库 8.0 全量 `mvnw test` **738 run / 0 Failures / 0 Errors** + `fmt:check` + JaCoCo 门禁通过。任务材料更正：recorder/PartitionMaintenanceJobTest/RateLimitServiceTest/ApiExceptionHandlerTest 在两轮 CI 均 Failures: 0 全绿，其「错误日志」是吞异常用例的预期输出，真实失败集即上述 8 例。残余观察登记报告 §五（filter 链异常经 ERROR dispatch 呈现 401 而非 500 的边角行为、Maven resources 不清 stale 迁移副本）。
- **V11 迁移 + 管理端错误态语境分化完成（2026-11-06，主人批准执行；无独立计划，按主人任务单直接实施）**：三件事一次迁移（`V11__active_external_key_llm_usage.sql`，append-only、不碰 V1–V10、无任何物理外键——与 V10 逻辑外键决策一致）。①`sentences.active_external_key` 生成列（未删取 `external_key`、已删恒 NULL，与 `active_fingerprint` 同款）+ 唯一键 `uk_sentence_active_external (bank_id, active_external_key)`，删除旧全行唯一键 `uk_sentence_bank_external`——批次回滚后重导同文件放行（016 评审 05#01 收口；旧键对并发导入的防护由生成列唯一键 + 导入事务 `bankDao.lockById` 锁承接，`ImportExternalKeyReuseIntegrationTest` 补「重导成功/活跃行同键 40904/两线程并发同键恰一批成功」三用例，schema 形状另由 `SentenceActiveExternalKeySchemaIntegrationTest` 锁定）。②`llm_usage` 建表（DATA-MODEL §5.18：id/user_id/sentence_id/scene/protocol/model/prompt_tokens/completion_tokens/duration_ms/ok/created_at；append-only 只记元数据不落问答内容；无物理外键，user/sentence 逻辑引用+查询索引）；`TutorServiceImpl` 改「结构化日志 + 落库」双写（`LlmUsageDao` 旁路，落库异常吞掉记告警，成功/空回复/失败三种终态都落行），017「落库待批」收口；内部表不暴露端点，API.md §8.15/§10.1 矩阵注。③`spring_session_attributes` 孤儿 BLOB 清理：裁决放 `PartitionMaintenanceJob` 小时级反连接 DELETE（DML 可重入、失败只记日志；迁移内不放一次性大表 DELETE），V11 经核不加索引（attributes PK 左前缀即驱动侧扫描键、spring_session PK 即探测键），016 的 023 修正项收口；`PartitionMaintenanceJobIntegrationTest` 真实 MySQL 锁「孤儿删/活者留/重跑空转」。**迁移前快照**：本机 mysqldump 双库落 `server/data/`（gitignored）——`yilian-pre-v11-20261105-234844.sql`（dev，575KB）与 `yilian_test-pre-v11-20261105-234901.sql`（test，45KB）；V11 另在 dev 数据克隆库实测 ALTER（29 行真实数据上生成列+唯一键替换成功）；**prod 迁移前快照机制未实现，登记 backlog（023 code-review F-01 遗留，非本批范围）**。前端：`ErrorState` 加 `variant`（默认 practice 行为不变，练习页离线队列文案原样；admin 变体=「管理数据加载失败」+ 检查网络/服务端提示，不带「答案保存在本机」），ModelsPage 两处错误卡传 `variant="admin"`——主人截图的文案错配（练习页离线文案出现在模型页）修复；注意若截图时 8080 后端未运行，错误卡本身是正确行为，本次只修文案错配。质量门禁：后端 `mvnw test` 738 run / 0 Failures / 0 Errors（023 基线 722，+16，独立库 `yilian_test_v11` 验证）+ `fmt:check` 0 不合规 + JaCoCo 门禁通过；前端 `vitest run` 137/137（基线 131，+6：ErrorState 语境分化 4 例 + ModelsPage 管理变体 2 例）、`tsc --noEmit` 0 错误、`vite build` 成功、`biome check` 改动文件 0 问题。- **022 AI 配置升级完成（2026-11-05，计划 [docs/plans/022-ai-config-and-markdown.md](plans/022-ai-config-and-markdown.md)）**：CC Switch 式多 provider 目录 + 答疑 Markdown 渲染。①provider 目录（`app_settings` 键 `llm.providers`/`llm.active`，激活唯一）+ 管理端五端点 `GET/POST /api/admin/llm/providers`、`PUT/DELETE /api/admin/llm/providers/{id}`、`PUT .../{id}/activate`（审计 LLM_CONFIG_UPDATE；id 限 `[a-z0-9-]+`、激活中不可删、协议白名单 fail-closed）；**apiKey 仍不入库**——按 id 走 `YILIAN_LLM_KEY_<大写ID>`（连字符→下划线），UI 只显 hasKey；目录空且 `YILIAN_LLM_*` 齐备时回退 legacy 单 provider（读路径兼容 017/021）。②`LlmChatClient` 三协议统一接口：anthropic-messages（021 网关轨，022 改官方 anthropic-java SDK 直调——桩实测 Spring AI `getText()` 会把 thinking 块当正文返回 text 块被丢，手写按块类型提取兜底）、openai-chat（SpringAiLlmClient 收编更名 `OpenAiChatLlmClient`）、openai-responses（新，手写 `java.net.http`——spring-ai 2.0.0 无 Responses 支持，侦察确认）；`LlmClientRegistry` 按激活条目 protocol 装配 + 目录指纹运行时缓存（变更即失效无需重启）；maxTokens 三协议同源 768→**2048**（思考模型 thinking 计入 max_tokens，主人实测 32 全被吃掉）。③ModelsPage 升级 provider 目录管理（列表卡协议/hasKey 徽标与激活高亮、新增/编辑抽屉、ZCode 网关预置模板、删除确认、legacy 形态卡），AI 功能清单按激活 provider 联动；`llm/config` 视图扩 `providerId`/`protocol`/`credentialsComplete`（anthropic 轨 key 可空的齐备性口径）。④AskPanel 回答 react-markdown 10.1.0 + remark-gfm 4.0.1 渲染（GFM 表格/代码块/列表/粗体；不引 rehype-raw，原始 HTML 按纯文本展示默认安全；用户问句仍纯文本）；**白名单外新依赖披露**：react-markdown（MIT，纯渲染无传递 runtime 依赖问题，替代方案=手写解析器/DOMPurify+dangerouslySetInnerHTML 均否——后者引入 XSS 面）与 remark-gfm（MIT，GFM 表格/删除线扩展），**追认状态：主人已追认（2026-11-06），两项转正；AGENTS §3.3 预置白名单条目待主人安排写入（kit 保护路径，须独立变更）**——披露与替代方案论证见 022 计划。质量门禁：后端 `mvnw test` 710 run / 0 Failures / 0 Errors（基线 678，+32）、`fmt:check` 0 不合规；前端 `vitest run` 126/126（基线 118，+8）、`tsc --noEmit` 0 错误、`vite build` 成功、`biome check` 本次改动文件 0 问题。
- **020 练习选题功能完成（2026-11-05，计划 [docs/plans/020-sentence-picker.md](plans/020-sentence-picker.md)；本行 023 补登）**：新端点 `GET /api/banks/{id}/sentences/list`（不动 §5.3 增量契约；可见性复用 016 口径——匿名私有 40100/他人 40300/40401；keyword/level/status 筛选缺省隐藏 RECYCLED，逐行练过态 JOIN reviews，MyBatis 直出 DTO）+ 练习页内 SentencePicker 选题面板（一屏一句纪律的展开层：难度/状态徽标/练过态列、点选经 next?sentenceId= 指定练习、当前句高亮、Esc/遮罩/× 关闭、游客公开库可用），替代旧 BankPeekDrawer；附带 AC-0 实证修复——前端 reviewSchema 误用队列卡形状解析排程投影致「提交未确认」，拆 attemptReviewSchema 放行 optional（014 冒烟「离线误判」成因闭环，API.md §6.2 补记两形状）。质量门禁：后端 `mvnw test` 678/0（+24）、前端 `vitest run` 118/0（+15）（见 020 PR 文档）。
- **019 统一模型配置状态中心完成（2026-11-05，计划 [docs/plans/019-model-config-unified.md](plans/019-model-config-unified.md)；本行 023 补登）**：ModelsPage 重写为「统一模型配置」真实状态卡（凭证状态/模型/端点/答疑关联读 `GET /api/admin/llm/config` 回显 + legacy env 两件套指引 + AI 功能清单按答疑开关联动 + 任务路由/Prompt/用量/预算 M3 占位），演示供应商卡/配置弹窗等演示交互移除；架构裁决=凭据 env 一次配置全局共享（密钥不入库红线，ModelsPage 不做 key UI），`llm.tutor` 开关走 PUT 且成功以 GET 回读为准。022 扩 provider 目录时沿用本状态卡并按 `credentialsComplete` 分口径。质量门禁：前端 `vitest run` 103/0（+4）、后端 654/0（Javadoc-only）、浏览器冒烟 4/4 PASS（见 019 PR 文档）。
- **021 LLM provider 抽象与 ZCode 本地网关适配完成（2026-11-05，计划 [docs/plans/021-llm-gateway.md](plans/021-llm-gateway.md)）**：答疑机器人双轨接入——`YILIAN_LLM_PROVIDER`（`openai` 缺省 | `anthropic`）选择 `LlmChatClient` 实现：openai 轨沿用 `SpringAiLlmClient`（017 语义不变，022 收编更名 `OpenAiChatLlmClient`）；anthropic 轨新增 `AnthropicMessagesLlmClient`（spring-ai-anthropic 2.0.0，BOM 已含，官方 anthropic-java-core 底座仅依赖 okhttp/micrometer 无新传递面，starter 自动配置随 `spring.ai.model.chat=none` 一并关闭），服务主人的 ZCode 本地网关（GLM 套餐额度，Anthropic Messages 协议）。拼接口径：`YILIAN_LLM_BASE_URL` 填到网关前缀、客户端拼 `/v1/messages`；网关本机无鉴权故 anthropic 轨凭证口径=仅 base-url（apiKey 可空，非空以 x-api-key 头携带）；网关 429 错误体为非标准形状（{"error":"ZCode 网关:所有账号尝试均失败",attempts:[...]}），识别按 HTTP 状态码（SDK 状态码驱动抛 RateLimitException/InternalServerException），429/5xx 退避 30s 重试一次（冷却特性；最坏 15s+30s+15s≈60s），仍败沿 `TutorServiceImpl` 50000「答疑暂时不可用」降级。护栏三件套（maxTokens 768/超时 15s/免责常驻）两轨同源（022 起 maxTokens 提至 2048 并扩三协议）。主用途切换：022 起首选 provider 目录（管理端 UI 激活切换）；legacy 路径=env 设 `YILIAN_LLM_PROVIDER=anthropic` + `YILIAN_LLM_BASE_URL=http://127.0.0.1:47860/gateway/zcode`（+可选 `YILIAN_LLM_MODEL=glm-5.3`）重启即走网关；删掉 provider 或设回 openai 恢复智谱直连。质量门禁：见 021 PR 文档。
- **017 题目答疑 AI 机器人完成（2026-11-04，计划 [docs/plans/017-ai-tutor.md](plans/017-ai-tutor.md)）**：被动题目答疑（PRD §5.4），非「AI 外教」；LLM 接入走 Spring AI OpenAI 兼容协议（spring-ai-bom 2.0.0 对齐 Boot 4.1，starter 自动配置关闭、TutorLlmConfiguration 按凭证惰性装配），主人智谱套餐经 `YILIAN_LLM_BASE_URL`/`YILIAN_LLM_API_KEY`（`YILIAN_LLM_MODEL` 缺省 glm-4.7）即接入。三态：未配 key=功能不存在（答疑端点 40400、前端无入口）；配 key 未开=40304「答疑未开放」（注：计划原文写 40301，但 40301 已是 CSRF 稳定契约，按「发布后不改号不复用」新增 40304）；开启=可用。管理员配置 `GET/PUT /api/admin/llm/config`（审计 LLM_CONFIG_UPDATE；enabled 存 `app_settings` 键 `llm.tutor`（V5 已建表，fail-closed 解析）；base-url/model/apiKey 环境变量只读、PUT 传非空值 40000 拒绝——AGENTS §3.4 密钥不入库，计划「apiKey 只写不回读」落地为「apiKey 拒收并提示走环境变量」，GET 回 hasKey）。`POST /api/practice/{sentenceId}/ask` 复用 016 accessibleForPractice 口径（PracticeService 新增 requirePracticeableSentence），限流 5 次/分钟（账号），超时 15s，异常降级 50000「答疑暂时不可用」；prompt 只喂题目中文/考点/本人最近批注，不喂参考译文，问题定界 + 500 字上限。`llm_usage` 表已随 V11 建表（DATA-MODEL §5.18，结构化日志+落库双写，落库失败只告警不阻塞答疑；内部表不暴露端点，API §10.1 矩阵注），017 的「落库待批」收口。前端练习页批注栏「问一问」入口 + AskPanel（免责常驻、会话内历史、40304/40400 后隐藏入口；复习页重练走同一练习页批注栏，不另设入口）。质量门禁：后端 `mvnw test` 649 run / 0 Failures / 0 Errors（基线 621，+28）、`fmt:check` 0 不合规、JaCoCo 门禁通过；前端 `vitest run` 99/99（基线 94，+5）、`tsc --noEmit` 0 错误、`vite build` 成功、`biome check` 本次改动文件 0 问题（src/mocks 存量 warning 不变）。
- **018 二轮评审修复 + 原型小项完成（2026-11-04，计划 [docs/plans/018-review2-and-prototype-fixes.md](plans/018-review2-and-prototype-fixes.md)；本行 019 补登）**：复评 70 条经独立核实（[docs/analysis/review-verify-2.md](analysis/review-verify-2.md)，P1×2 全真、P2 真缺陷 27 条、误报 2 条为 ULID 字母表与 utf8mb4 判断）。P1×2：登录时序侧信道（用户名不存在时执行假 BCrypt matches 对齐耗时）与 prod 加固旁路（判定扩为 profile=prod OR 显式 env 标记，别名部署不再静默用默认口令）。P2 择要：`reset-password-on-startup` 一次性持久化标记（用后重启不覆盖已改口令）、`review_card` @Version 乐观锁（并发 lost update 拒绝）、分片上传超时会话定时清扫（并入 PartitionMaintenanceJob）。原型小项：管理员重置用户密码端点（`POST /api/admin/users/{id}/reset-password`，一次性临时口令只随响应返回一次）、删除账号端点（`DELETE /api/admin/users/{id}` 软删+吊销会话+审计、不能删自己）、整句批改要点命中 chips（前端对照原型）、复习顺延口径统一为 3 天。质量门禁：后端 `mvnw test` 621 run / 0 Failures / 0 Errors（+24，含 JaCoCo 门禁）；前端 `vitest run` 94/94（+6）；API.md 新端点已挂 §10.1 矩阵。
- **016 外部评审消化完成（2026-11-04，计划 [docs/plans/016-external-review.md](plans/016-external-review.md)；本行 019 补登）**：两步消化——先独立核实再修复。核实（[docs/analysis/review-verify.md](analysis/review-verify.md)）：112 条评审中 P0×2+P1×9 全部真缺陷、零误报（含 API 实证与 SQL 实证）。P0×2：练习整链题库可见性校验补齐（候选 SQL `NOT EXISTS` 下推 + 交卷/回看/答疑复用 `requirePracticeableSentence` 校验，练习史豁免为本人可见口径）；测试库口令明文收敛（application-test.yml env 三层分层=本机默认回落文档化 dev 值/开发者 env 覆盖/CI 必须注入随机 + TestDatabases 幂等建库 + 干净检出等价验证）。P1×9：新题召回 `NOT EXISTS` 下推（LIMIT 20 作过滤后窗口）、reviews 建卡 `REQUIRES_NEW`+输家读回复用、`external_key` 批次隔离（`gen_<batchId8>_%04d`）、停用/降级吊销 refresh 会话（真实 HTTP 测试零残留）、rollback 事务化（与 commit 同构）、`spring_session` BLOB 孤儿清理（**023 修正：此项实际未落地——V11 迁移从未创建（`git log --all --diff-filter=A -- "*V11*"` 零命中）；024 批准后已随 V11 收口：`PartitionMaintenanceJob` 小时级反连接 DELETE（DML 可重入、失败只记日志，迁移内不放一次性大表 DELETE），真实 MySQL 集成测试锁「孤儿删/活者留/重跑空转」**）、CSV RFC4180 子集解析（引号/逗号/换行/BOM）、集成测试建库策略（9 类单类可跑）、登录→拉题→提交→批改 E2E 主链路集成测试（DoD 缺口）。P2 择要 10 项（prod 反代默认收紧、回收归属校验、编辑不隐式发布、page 上限、会话纪元护栏、reset 开关一次性、Redis 超时、40904 翻译、JaCoCo 门禁 LINE≥85/BRANCH≥65、fmt:check 转阻断），其余登记 [docs/analysis/review-p2-backlog.md](analysis/review-p2-backlog.md)。质量门禁：后端 `mvnw test` 597 run / 0 Failures / 0 Errors（534 基线，+63，含 JaCoCo 门禁）；前端 `vitest run` 88/88。

- **防护缺口批次完成（2026-11-03，计划 [docs/plans/013-protection-gaps.md](plans/013-protection-gaps.md)，analysis-protection-gaps Top10 之 #4/#5/#6/#8/#9）**：
  - **幂等清理（#8）**：`PartitionMaintenanceJob` 并入 `@Scheduled(fixedDelay=30min)` 清理任务，`DELETE ... WHERE expires_at <= now LIMIT 500` 分批循环（单轮 ≤200 批、失败仅记日志），过期口径与惰性清理的含边界判定一致；`JdbcIdempotencyService` 链路不变。
  - **缓存接线（#5）+ 三防护（#6）**：`CacheConfig` 改显式缓存名清单（`content:bank:detail:v1` / `content:bank:list:v1`，版本段进缓存名，禁动态建缓存）；落点为匿名公开读 `GET /api/banks` 列表与详情（登录/管理端不缓存，理由见 `BankMetadataCache` 类注释）；击穿=`@Cacheable(sync=true)`、穿透=`Absent` 负缓存条目（60s 短 TTL）、雪崩=`JitteredTtlExpiry` ±10% TTL 抖动（基准 10min）；写后删缓存经 `BankMetadataCache.evictWrittenBanks` 事务提交后执行（详情逐键、列表整体清），失效路径覆盖题库 CRUD/删除/批量状态/批量移动/单题状态/行内编辑/导入/导入回收。
  - **安全响应头（#9）**：`SecurityConfig` 增加 nosniff、`X-Frame-Options: DENY`、`Referrer-Policy: strict-origin-when-cross-origin`、HSTS（仅 HTTPS 请求写入，max-age 1 年含子域）、CSP（prod 从紧：自托管 + Google Fonts 两域 `fonts.googleapis.com`/`fonts.gstatic.com`；local 放宽内联脚本供 swagger-ui；来源盘点记录于 `SecurityConfig` 常量注释）。
  - **最后管理员锁（#4）**：`AppUserJpaRepository.lockActiveAdminIds` 以 `@Lock(PESSIMISTIC_WRITE)` 锁定启用管理员行（ORDER BY id 防死锁）后计数，`adminSetRole`/`adminSetStatus` 两处接入；新增真实 MySQL 两线程并发互降回归测试（仅一人成功、败者 40900）。
  - 质量门禁：`mvnw test` 525 run / 0 Failures / 0 Errors（基线 490，+35）；`fmt:check` 0 不合规。前端无改动；无新增端点，API.md §10.1 不动。

- **波次一完成（2026-10-29）**：V1–V8 迁移链已由 commit `857e644` 从构建产物恢复（下文 2026-10-28「迁移全部缺失」的更正已过时，以本条为准）。`AuthFlowIntegrationTest` 恢复 7/7 全绿——根因链：`ImportBatch` 实体仍映射已 DROP 的旧 `import_batch` 表（叠加 `sentence_versions.schema_version` 实体 `int` 与 DDL/契约 `SMALLINT` 错配，两者都是被首个 validate 错误掩盖的存量问题）。已按 preview→commit 契约重写 `ImportBatch`/`ImportBatchRow` 实体与状态机（切片 T3 实体部分提前），删除旧 upload→PROCESSING→resume 过渡机制（旧端点/service/recovery/async/DTO 及其测试）；全量 `mvnw test` 245 run / 0 Failures / 0 Errors。覆盖率工具接入：后端 JaCoCo（`mvn test` 产出 `target/site/jacoco/`）、前端 @vitest/coverage-v8（`pnpm test:coverage`，既有 50/50 不变）。CI 的 `-Dtest=!AuthFlowIntegrationTest` 排除按计划随波次四移除；preview→commit 新端点与前端导入改造为波次二（T4/T6），banks P1×2 为 T5。

截至 2026-10-24 实测收口：

- 已完成：需求、设计、可点击原型；工程基线收口——后端 auth / system / upload / content / common 模块（ApiResponse 信封、双 token JWT、@OperationLog 审计切面、@RateLimit Redis 滑动窗口、幂等、error_log、存储抽象）与 V1–V8 迁移；前端全部页面与路由注册（AppShell 壳层布局，`/` 即今日启动台，登录后落 `/today`；管理页独立注册）
- 本轮修复：导入审计 module 归一（`module="import"` 落库归一为 `content`，原 `"upload"` 不在 DATA-MODEL §7 枚举导致误落 system）+ actor 透传正向测试
- 质量门禁实测：后端 `mvnw test` 224 run / 0 Failures / 7 Errors / 0 Skipped；前端 `tsc --noEmit` 0 错误、`biome check` 0 错误（32 条 warning 级遗留，集中在 src/mocks，不阻塞）、`vitest run` 21/21（grading 14 + auth-flow 7）、`vite build` 成功；google-java-format（aosp）全量格式化后 `fmt:check` 0 不合规
- 已知存量：upload→imports preview→commit 重构进行到一半——V6 迁移已 DROP 旧 `import_batch` 并重建为 `import_batches`（DATA-MODEL §5.6/§5.7），但 `upload/entity/ImportBatch.java` 仍 `@Table(name="import_batch")`，Hibernate `ddl-auto=validate` 启动失败，`AuthFlowIntegrationTest` 上下文无法加载，即上述 7 Errors 的唯一根因；imports 切片完成后自然消除，非本轮引入
- 基线可比性：surefire 历史报告中的 imports 模块测试（如 ImportBatchServiceImplTest）连同实现都不在当前工作树中，「259+」等历史数字不可比
- git：仓库已初始化（main 分支、0 提交），首次提交由用户自行执行
- FSRS：契约与字段已预留（PRD / API / DATA-MODEL、后端 V5/V8 迁移与 UserSettings、前端 schemas/mocks），实现按计划在 V2（M2.1 优化器后置），无缺失
- 下一步：练习主链路切片（题库→拉题→填空→判分，V8__practice_core 已建表）；另有 upload 模块按新契约替换的存量收尾（见「已知存量」）
- 下一步（2026-10-28 修订）：切片执行稿见 [docs/练习主链路_切片设计.md](练习主链路_切片设计.md)。**重要更正**：基于 commit 33e625e 复核，仓库实际不存在任何 Flyway 迁移文件（上文「V1–V8 迁移已建表」与「V8__practice_core 已建表」均为失实记载，7 Errors 的根因即迁移缺失）；执行稿改为从零重建 V1–V5 迁移链，并给出 practice 全端点设计、upload 收尾与前端切换方案，以执行稿为准
- 本轮已完成（原型对齐，2026-10-24）：以原型为模板全量对齐前端——去掉强制登录（游客可逛全部主页面，仅 /admin/** 保留门）、新增三题定级（纯本地、可跳过、不贴等级标签）、练习/复习/统计/设置逐屏还原（双栏批注栏、会话条、改对才过、本轮总结弹窗、导出 JSON 等）、管理端九页签（概览/用户/题目/题库/出题/模型/操作日志/异常/运维，mock 演示层）；后端新增 `GET /api/banks/**` 匿名只读端点（PUBLIC_OR_USER，257 run / 0 Failures / 7 Errors 存量不变）；新增前端本地练习引擎（本地判分/排程/统计/降载，游客与登录用户过渡期共用，`lib/api.ts` 的 LOCAL_ROUTE_GROUPS 开关一处切回服务端）。游客真实后端全链路已浏览器实测（匿名拉公开题库 → 定级 → 练习 → 错题入队 → 统计聚合）；管理端在 `VITE_USE_MOCK=true` 演示模式下验收
- 原型覆盖交互与页面结构；标注「服务端」的事项在原型中只能示意

- **管理端 UI 并回主壳层（项目主人 2026-10-31 指示，下个计划执行）**：现 /admin 为独立路由 + AdminLayout 自带框架（router.tsx「管理页不并入壳层」），项目主人要求改为与 今日/练习/复习/统计 一致——主导航内加「管理」项，点击后内容在同一壳层内容区显示（AdminLayout 框架拆除、九页签逻辑保留、AdminGate 登录门保留）；同时管理端真实数据接口（/api/admin/** 日志检索、题库管理、用户管理等）未开发，真实模式下页签报 404 属已知缺口（当前以 VITE_USE_MOCK=true 演示），接口开发随管理端切片。

## 二轮评审 P2 未修余量（023 登记入册）

来源：[docs/analysis/review-verify-2.md](analysis/review-verify-2.md) §三（70 条复评经独立核实：P2 29 条=27 真 / 2 误报）。此前仅 `docs/analysis/review-p2-backlog.md`（本机资产）登记一轮余量，二轮余量无仓内登记（评审 F-02）——脱离跟踪即等于不会修，本节补齐。**已修 5 条**：01#02（review_card @Version 乐观锁）、03#05（reset 开关一次性持久化）、03#06（prod 加固判定扩 profile OR 显式 env 标记）、04#02（分片上传超时会话清扫并入 PartitionMaintenanceJob）见 018 行；06#01（CI 口令随机化）见 ci-fix-20261104。**余 22 条未修**，编号沿用 review-verify-2 §三（证据文件:行与修复方案回源查阅）：

01#01 业务日志明文 userId（MDC 脱敏被消息体参数绕过）；01#03 today 逐 attempt N+1；01#04 wrongPool 每卡 3 次 N+1；01#05 FSRS 优化器同步 SGD 阻塞请求线程；02#01 同值编辑不幂等无条件新增版本行；02#02 批量移动批内指纹互撞预检不覆盖；02#03 移动目标库未排除 DISABLED/ARCHIVED；02#04 编辑 tags 无数量/长度校验；02#05 题库列表逐行 COUNT N+1；02#06 admin 直接依赖 upload dto；03#02 邀请码校验前返回占用错误；03#03 恢复账号被 DTO 强制 reason；03#04 图形验证码未收录 API.md；03#07 LOGIN 审计恒记 SYSTEM 无账号维度；04#03 @Idempotent 整套 AOP 死代码（删除或冻结预留需裁决）；05#02 external_key/level/source/license 无长度校验 + 40904 误翻译；05#03 commit 不收可选 reason + CommitImportRequest 死 DTO；05#04 回滚响应缺 rolledBackAt/By/Reason 三字段；05#05 commit 失败补偿 markFailed 无锁重读；05#07 导入逐行指纹查重 + 逐条 save；06#02 Checkstyle Javadoc 门禁未落实；06#03 CI action 未 pin SHA。

（误报 2 条不修：04#01 ULID 字母表、05#06 utf8mb4 连接charset——判定与证据见 review-verify-2 §三。）

## 029 轮巡余量（双 skill 第一轮消缺批登记入册，2026-11-17）

来源：[code-review-full-20261117](analysis/code-review-full-20261117/00-评审汇总.md)（P3×7）与 [test-audit-full-20261117](analysis/test-audit-full-20261117/00-测试体检报告.md)（DEF-02/02-01/U-01/黑盒扩套件）+ 029 AC-6 实测（DEF-01）+ 第二轮复审（07-第二轮复审.md，R2-01/R2-02）。预披露裁决：P3 与依赖侧只登记不修；CI 配置（保护路径）不动；黑盒扩套件不入 029 批。**F-03 已随 029 AC-1/AC-2 闭合**（终审/复核并发用例落地），登记备查不重复排期；C-01 本批已修注释部分（如实化），余量如下：

| 来源条目 | 一句话 | 级别 |
|---|---|---|
| code-review 01 · A-04 | appeal/dispute 两端点无端点级 @RateLimit，写入口限流口径与 practice-attempt/model-review 不齐（申诉行写入上限只靠 uk_appeal_annotation 兜底） | P3 |
| code-review 02 · B-01 | 目录读-改-写并发（已文档化取舍）在 027B 后最坏情形扩为「目录条目在而密钥行被并发删」——解析链 fail-safe 回退 env，后果可控，注释补记或乐观收敛 | P3 |
| code-review 02 · B-02 | 主密钥「原始口令」形态单次 SHA-256 派生无 KDF 拉伸——公网部署前部署文档应强制 hex/base64 或 ≥32 字节随机口令 | P3 |
| code-review 03 · C-01 余量 | prompt_versions 治理链路只有读半边：evictAll 零生产调用（预留 API）、无应用内写路径/管理入口，版本流转走手工 SQL 无 SOP——写路径+@OperationLog+回归门禁另行排期（本批已如实化「短 TTL」注释） | P2 余量 |
| code-review 04 · D-01 | registry 每次解析现算密钥状态指纹（llm_provider_keys 全行读）+ 027B/028 扩大「旧客户端不 close」取舍作用面——量级可忽略，防未来误修 | P3 |
| code-review 05 · E-01 | LlmProviderKeys 构造器 @param crypto 描述串位（写成了 clock 的职责） | P3 |
| code-review 05 · E-02 | application-local.yml.example 未同步 027B provider-key 段，示例模板落后实际 local 配置 | P3 |
| code-review 06 · F-03 | 终审/复核并发用例——**已随 029 AC-1/AC-2 闭合**（AppealFinalDecisionConcurrencyIntegrationTest / PracticeModelReviewConcurrencyIntegrationTest），备查 | P3 已闭合 |
| test-audit 06 · DEF-01（029 AC-6 实测修订） | 复核/初裁在现网降级的确定性根因=网关不生效 top-level system 字段：受控实验 A 组（system top-level，与产品 SDK 同 wire 形状）2/2 回自由文本、strict JSON 解析必败（响应 11.1s/13.6s 均在预算内），B 组（同文指令并入用户消息）2/2 干净 JSON（5.3s/8.3s）；复核量级直探 2×502@30s（网关上游超时）+一次 200@25.7s 长尾；产品侧 llm_usage 5 次 15s 超时——15s 预算确实被部分调用击穿，但**提至 30s 不改变成功率**（<15s 的响应同样解析必败），故不改预算。修复方向：①复核/初裁 strict JSON 指令并入用户消息（产品侧可修，涉及 V13 种子/prompt builder 契约/API.md，需独立批次）；②网关侧 system 字段支持与上游超时（依赖侧）。实测数据：.workflow/runtime/029/def01-*.json（本机资产）；覆盖轨只读核验未破坏（source=custom/anthropic-messages/glm-5.3-flash） | P1 依赖侧 |
| test-audit 06 · DEF-02 | 复核端点一次裸 500 无信封响应（1/3 次观测，非破坏性复测未复现）——再现时抓 8080 服务端日志定因，留意复核链路异常分支 @ExceptionHandler 覆盖 | P3 |
| test-audit 02 · 02-01 | 前端覆盖率无 CI 级阈值（vite coverage 无 thresholds、CI web job 不采集覆盖，gates.json 增量门禁为流程级）——现有口径为有意设计，登记为公网化/加固时的技术护栏候选项 | P3 |
| test-audit 00/06 · 黑盒扩套件 | 015 套件基于 v0.1.1 时代契约，024/025/027B/028 新端点不在其内（本次靠 S/C 组临时脚本补位）——扩套件 6（review/appeal 链：复核落行/幂等/429、申诉 40900/终审/overlay 判对）登记 backlog，主人点单 | P3 |
| test-audit 00 · U-01 | CI 平台运行历史——**已闭环（2026-11-17，run 43981034284）**：push 38 笔（175d90f→048a2e1）触发 CI，server+web 双 job success 总时长 3m00s（Server 2m58s 跑满=全量测试真实执行完），0 失败无重跑；核验证据 .workflow/runtime/u01-ci-verify/（本机资产） | 已闭合 |
| code-review 07 第二轮 · R2-01 | 初裁落列 `updateAdjudication` WHERE 仅按 id（AppealJpaRepository:77-82）——申诉落库后 ~31.5s LLM 窗口内该行 PENDING 可见于管理端，若管理员窗口内合法终审，随后到达的初裁会覆盖终审理由与 decided_by 且不可自愈（status 已非 PENDING）；「预检代替守卫」同型第三实例（终审已修、复核已修、此为初裁侧），一处 JPQL 加 status 谓词+窗口竞态测试即可 | **P2 已闭合（034）**：WHERE 携带 `status='PENDING'`，行数=0 不落初裁列、回执按存储真相投影；窗口竞态集成 `AppealAdjudicationWindowRaceIntegrationTest` 在案 |
| code-review 07 第二轮 · R2-02 | 复核事务持连接+attempts 行锁贯穿外呼（~31.5s）——≥10 跨账号并发复核触达连接池上限/第 3+ 等待者撞 InnoDB 50s 锁等待超时，均有已捕获降级路径；取舍已留痕（代码注释+API.md），量化登记防未来误修 | P3 |


## P0：上线前必须补

### 账号与安全

| 事项 | 要求 |
|---|---|
| 会话吊销 | refresh 会话存 Spring Session JDBC；登出/改密/停用均按用户吊销全部 refresh 会话；access token 采用 15 分钟短 TTL，不做永久黑名单 |
| 限流 | 登录按 IP + 账号双维度（10 次/分钟，2026-11-04 与代码对齐，API §4.2）；邀请码与深度批改分别限流；`/api/banks**` 匿名读 120 次/分钟（IP）；`/api/admin/fsrs/optimize` 5 次/分钟（账号）；429 带 Retry-After |
| 幂等 | `attemptId`、邀请码、预算扣减以数据库唯一约束兜底 |
| 默认凭据 | 部署环境仍用默认密码时，启动即失败 |
| 密码存储 | 当前 BCrypt（strength 10）；Argon2id 作为后续升级项；~~公网部署的管理员启用 2FA~~ **已随 032 落地（2026-11-18）**：TOTP 自研 RFC 6238 全链路（登录第二步/自助注册/管理端 reset-2fa 兜底），机制对所有用户开放、公网硬前置面是管理员；「强制管理员开 2FA」全局开关不做——公网部署 SOP 用文档+运维清单承接（后续批） |

### 数据与恢复

| 事项 | 要求 |
|---|---|
| 备份 | 每日全量 + binlog；异地保存；RPO ≤ 15 分钟，RTO ≤ 4 小时 |
| 恢复演练 | 每季度隔离恢复并跑一次登录 + 判分，记录实际耗时 |
| 保留策略 | 答题原文 90 天、聚合统计长期、审计 1 年；分批游标删除，可断点续跑 |
| 导出与删除 | 用户可导出 JSON；删号软删 30 天后匿名化 |

### 学习闭环

| 事项 | 要求 |
|---|---|
| 三题定级 | 三题自适应，不报等级，只给起点与「N 题后自动校准」；可跳过 |
| 积压熔断 | 到期超过阈值进入降载，只推 5 道；顺延有上限，超限转重学 |
| 判分可重放 | 每条答题绑定 normalizer / rubric / prompt / model 版本；改判写新记录 |
| 时区日界 | 全 UTC 存储，按用户 IANA 时区计算今日与连续天数 |
| 首败处理 | 答错时说明「还差几处」，并提供「看答案，记为学习中」出口 |

### 内容治理

| 事项 | 要求 |
|---|---|
| 句源与分级 | 记录来源、许可、难度；无来源只能存草稿 |
| 坏题探测 | 统计通过率与区分度；样本足够且通过率过低时暂停出题 |
| 多解库 | 每个语块可维护多个可接受译法；申诉通过后回写 |
| 生命周期 | 题库与题目五态（草稿/发布/停用/回收站/归档）；版本不可变，回滚生成新版本 |
| 去重与批次 | 精确指纹硬拦、近似相似度提示；导入批次可整体回滚 |

### 模型与 Prompt

| 事项 | 要求 |
|---|---|
| 模型目录 | 记录模型快照与上下线时间；生产不跟随 `latest` |
| 任务路由 | 按任务配置主模型、回退链、参数与灰度 |
| Prompt 注册表 | 不可变版本 + 生产/候选标签；发布前跑回归集 |
| 调用账本 | 记录 trace、Prompt 版本、模型快照、token、成本、耗时——**成本与预算护栏已随 033 落地（2026-11-18）**：`estimated_cost_micro_cny` 列（V16）+ 月度预算三态强制门（PRD §17）+ 管理端用量视图（API §8.17）；trace 维度仍缺（M3 观测域） |
| 注入防护 | 用户答案定界为数据，输出强校验，分数钳制 |
| 黄金集 | 50–100 条分层回归集，换模型或改 Prompt 前必跑 |

### 基础设施

| 事项 | 要求 |
|---|---|
| 缓存分层 | Caffeine 一级 + Redis 二级；键带环境与版本；写库后删缓存；Redis 故障时回源 |
| 日志页面 | 操作日志与异常日志两个独立后台页面；记录操作者与 IP；支持时间、模块、关键字筛选；异常页可看堆栈与 traceId |
| 日志保留 | 操作日志 1 年；异常日志 30 天，按时间分区清理 |

### 合规

- 隐私政策与用户协议
- 年龄声明；未成年人需监护人同意
- 题库来源与 license 字段
- 模型服务与数据出境说明

## 上线前硬门槛

1. 空库一键起、迁移可重放、备份恢复实测（RPO 24h / RTO 2h 以内）
2. 默认凭据无法启动；TLS、安全头、会话吊销、限流都有自动化测试；无 critical/high 漏洞
3. 并发幂等压测通过；断网 20 题同步零重复
4. 模型全挂演练：主链路不阻塞、降级可见、预算不穿透
5. 关键告警可触发；发版失败一分钟内换回上一版本；双真机冒烟通过

## P1：之后再做

- 题目浏览器的高级筛选、保存视图、批量撤销
- 工单收件箱与申诉结果回流
- 质量看板与人工抽检
- 灰度发布、影子流量、人审工作台
- 进步周报、语篇产出、考试模式、回译
- PWA 离线队列、更新提示、iOS 存储持久化
- 指标、告警、CI/CD

## 不做

- K8s / 微服务 / 多副本 / 分库分表
- 自研认证、加密、IDP、Prompt IDE、观测平台
- AI 批量造题自动发布
- 自动合并重复题
- 排行榜、惩罚性打卡、抽奖、AI 外教
- 开放 UGC 题库与社区
- 自建支付
- 无限题库与全科扩张

## 部署前必须确定

bootstrap 管理员策略：

| 环境 | 行为 |
|---|---|
| 本机 | 允许默认值，便于恢复 |
| 部署 | 默认值或未设置时启动失败 |
| 忘记密码 | 临时开启 `reset-password-on-startup=true`，用完关闭 |

## 原型与实现的边界

| 能力 | 原型 | 生产实现 |
|---|---|---|
| 判分 | 本地规则可跑 | 规则 + 可选模型 |
| 会话/限流/幂等 | 界面示意 | 服务端 |
| 备份/恢复 | 演练结果模拟 | 脚本 + 定期演练 |
| Prompt 版本/评测 | 版本展示 | 注册表 + 回归门禁 |
| PWA 离线 | 横幅模拟 | Service Worker + 队列 |
| 模型生成题库 | 固定样例 | 真实调用 + 计费 |
