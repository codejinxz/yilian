package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.practice.llm.LlmClientRegistry;
import io.github.codejinxz.yilian.practice.llm.LlmProviderKeys;
import io.github.codejinxz.yilian.practice.service.LlmProviderAdminService;
import io.github.codejinxz.yilian.practice.service.TutorService;
import io.github.codejinxz.yilian.practice.service.dto.LlmConfigUpdateRequest;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderCreateRequest;
import io.github.codejinxz.yilian.practice.service.vo.LlmConfigView;
import io.github.codejinxz.yilian.practice.service.vo.LlmUsageView;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 028 答疑配置三级解析链集成测试（真实 MySQL）[AC-2]/[AC-3]：
 *
 * <p><strong>装配优先级：目录激活条目（022 语义不变）&gt; tutor 覆盖轨（app_settings 键 llm.tutor 的 baseUrl/model +
 * llm_provider_keys 预留 id=tutor 密钥）&gt; legacy env 轨</strong>——逐级验证：env 轨基线、
 * 覆盖保存即换轨、覆盖修改即时换新模型、目录激活压过覆盖、目录清空后覆盖轨回归；022 语义保留（目录非空未激活 = fail-closed，覆盖休眠不救场）。
 *
 * <p>[AC-3] tutor 密钥复用 llm_provider_keys（预留 id=tutor）：PUT apiKey 加密入库（密文列无明文）、 last4 投影、clearKey
 * 删行后 hasKey 回落 env；[AC-1] enabled=true 被拒时本次覆盖与密钥写入整体回滚（同事务）。
 *
 * <p>legacy env 轨凭据以测试属性注入（{@code yilian.llm.*}），目录与覆盖/密钥行每用例前后清理，恢复 「空目录 + 无覆盖 + 无 tutor 密钥行」基线。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false",
            // legacy env 轨凭据（openai 轨三件套中的两件 + 模型名）；密钥用测试值，不指向真实服务。
            "yilian.llm.base-url=https://env-rail.example.test/v4",
            "yilian.llm.api-key=sk-env-rail-key",
            "yilian.llm.model=glm-4.7"
        })
@ActiveProfiles("test")
class TutorConfigRailIntegrationTest {

    /** 覆盖轨测试端点（127.0.0.1:1 不可路由，只验证装配与换轨，不发起真实调用）。 */
    private static final String OVERRIDE_BASE_URL = "http://127.0.0.1:1/v4";

    /** UI 录入的 tutor 明文密钥（只在本测试内存中出现，断言其不落明文列）。 */
    private static final String TUTOR_KEY = "sk-tutor-it-8811";

    /** 目录条目 id（openai 轨，无 env key 索引）。 */
    private static final String PROVIDER_ID = "it-tutor-rail";

    @Autowired private TutorService tutorService;

    @Autowired private LlmClientRegistry llmClients;

    @Autowired private LlmProviderAdminService providerAdminService;

    @Autowired private JdbcTemplate jdbcTemplate;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 每用例前后清理目录/覆盖/密钥：恢复「空目录 + 无覆盖 + 无 tutor 密钥行」基线，前清防残留、后清防污染。 */
    @BeforeEach
    @AfterEach
    void cleanState() {
        jdbcTemplate.update(
                "DELETE FROM llm_provider_keys WHERE provider_id = ?",
                LlmProviderKeys.TUTOR_PROVIDER_ID);
        jdbcTemplate.update(
                "DELETE FROM app_settings WHERE `key` IN ('llm.tutor', 'llm.providers', 'llm.active', 'llm.budget')");
        // [AC-6] 用量视图聚合全场景：llm_usage 整表清（用量行只由本类播种产生，预算判定 SUM 也按全场景聚合）。
        jdbcTemplate.update("DELETE FROM llm_usage");
    }

    /**
     * 播种预算护栏的月度口径事实：llm.budget 键 + 本月一笔指定成本的用量行。
     *
     * @param budgetCny 月度预算（元）
     * @param spentMicroCny 本月已用成本（微元）
     */
    private void seedBudget(long budgetCny, long spentMicroCny) {
        jdbcTemplate.update(
                "INSERT INTO app_settings (`key`, value, description) VALUES ('llm.budget', ?,"
                        + " 'budget-it') ON DUPLICATE KEY UPDATE value = VALUES(value)",
                "{\"schemaVersion\":1,\"monthlyBudgetCny\":" + budgetCny + ",\"pricing\":null}");
        jdbcTemplate.update(
                "INSERT INTO llm_usage (scene, ok, estimated_cost_micro_cny, created_at)"
                        + " VALUES ('budget-it', 1, ?, UTC_TIMESTAMP(6))",
                spentMicroCny);
    }

    /** [AC-2] 三级优先序（1/3）：目录空 + 无覆盖 → legacy env 轨生效（端点/模型为 env 值，source=env）。 */
    @Test
    @DisplayName("[AC-2] 无覆盖时 legacy env 轨生效")
    void ac2_envRailBaseline() {
        LlmConfigView view = tutorService.getAdminConfig();

        assertThat(view.source()).isEqualTo(LlmConfigView.SOURCE_ENV);
        assertThat(view.baseUrl()).isEqualTo("https://env-rail.example.test/v4");
        assertThat(view.model()).isEqualTo("glm-4.7");
        assertThat(view.hasKey()).isTrue();
        assertThat(view.providerId()).isEqualTo(LlmClientRegistry.LEGACY_PROVIDER_ID);
        assertThat(llmClients.chatHandle())
                .hasValueSatisfying(handle -> assertThat(handle.model()).isEqualTo("glm-4.7"));
    }

    /** [AC-2] 三级优先序（2/3）：保存覆盖即换轨（端点/模型生效于句柄与视图，source=custom），改模型即时换轨。 */
    @Test
    @DisplayName("[AC-2] 覆盖保存/修改即时换轨")
    void ac2_overrideRailSwitchesImmediately() {
        tutorService.updateAdminConfig(
                new LlmConfigUpdateRequest(false, OVERRIDE_BASE_URL, "glm-override", null, null),
                1L);

        assertThat(llmClients.chatHandle())
                .hasValueSatisfying(handle -> assertThat(handle.model()).isEqualTo("glm-override"));
        assertThat(llmClients.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.baseUrl()).isEqualTo(OVERRIDE_BASE_URL);
                            assertThat(config.model()).isEqualTo("glm-override");
                        });
        LlmConfigView view = tutorService.getAdminConfig();
        assertThat(view.source()).isEqualTo(LlmConfigView.SOURCE_CUSTOM);
        assertThat(view.baseUrl()).isEqualTo(OVERRIDE_BASE_URL);

        // 修改覆盖模型 → 下一次解析即换轨到新模型（即时生效，无需重启）。
        tutorService.updateAdminConfig(
                new LlmConfigUpdateRequest(false, OVERRIDE_BASE_URL, "glm-override-2", null, null),
                1L);
        assertThat(llmClients.chatHandle())
                .hasValueSatisfying(
                        handle -> assertThat(handle.model()).isEqualTo("glm-override-2"));
    }

    /** [AC-2] 三级优先序（3/3）：目录激活条目压过覆盖；目录清空后覆盖轨回归；目录非空未激活仍 fail-closed（022 语义不变）。 */
    @Test
    @DisplayName("[AC-2] 目录激活 > 覆盖 > env，且 022 fail-closed 语义保留")
    void ac2_directoryBeatsOverrideAndFailClosedPreserved() {
        tutorService.updateAdminConfig(
                new LlmConfigUpdateRequest(false, OVERRIDE_BASE_URL, "glm-override", null, null),
                1L);
        assertThat(llmClients.chatHandle())
                .hasValueSatisfying(handle -> assertThat(handle.model()).isEqualTo("glm-override"));

        // 目录非空但未激活：fail-closed（覆盖休眠，不救场）。
        providerAdminService.create(
                new LlmProviderCreateRequest(
                        PROVIDER_ID,
                        "覆盖轨集成测试",
                        "openai-chat",
                        "https://directory.example.test/v4",
                        "glm-directory",
                        null),
                1L);
        assertThat(llmClients.chatHandle()).isEmpty();
        assertThat(llmClients.effectiveConfig()).isEmpty();

        // 激活 → 目录条目压过覆盖（022 语义不变）。
        providerAdminService.activate(PROVIDER_ID, 1L);
        assertThat(llmClients.chatHandle()).isEmpty(); // 目录条目缺密钥：句柄缺
        assertThat(llmClients.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.providerId()).isEqualTo(PROVIDER_ID);
                            assertThat(config.baseUrl())
                                    .isEqualTo("https://directory.example.test/v4");
                            assertThat(config.model()).isEqualTo("glm-directory");
                        });
        LlmConfigView view = tutorService.getAdminConfig();
        assertThat(view.baseUrl()).isEqualTo("https://directory.example.test/v4");
        assertThat(view.model()).isEqualTo("glm-directory");

        // 目录清空（删除受保护条目走管理端需先切激活，这里直接清目录键模拟「目录被清空」）→ 覆盖轨立即回归。
        jdbcTemplate.update(
                "DELETE FROM app_settings WHERE `key` IN ('llm.providers', 'llm.active')");
        assertThat(llmClients.chatHandle())
                .hasValueSatisfying(handle -> assertThat(handle.model()).isEqualTo("glm-override"));
    }

    /** [AC-2] 覆盖清除回 env：空/缺省的 baseUrl/model 落 null，句柄回落 env 轨（端点=env 值）。 */
    @Test
    @DisplayName("[AC-2] 覆盖清除回 env 轨")
    void ac2_clearingOverridesFallsBackToEnv() {
        tutorService.updateAdminConfig(
                new LlmConfigUpdateRequest(false, OVERRIDE_BASE_URL, "glm-override", null, null),
                1L);
        assertThat(llmClients.effectiveConfig())
                .hasValueSatisfying(
                        config -> assertThat(config.baseUrl()).isEqualTo(OVERRIDE_BASE_URL));

        // 开启答疑（env 轨凭据齐备，合法）且不携带覆盖字段 → 覆盖清除，轨回落 env。
        LlmConfigView view =
                tutorService.updateAdminConfig(
                        new LlmConfigUpdateRequest(true, null, null, null, null), 1L);

        assertThat(view.enabled()).isTrue();
        assertThat(view.source()).isEqualTo(LlmConfigView.SOURCE_ENV);
        assertThat(view.baseUrl()).isEqualTo("https://env-rail.example.test/v4");
        assertThat(llmClients.effectiveConfig())
                .hasValueSatisfying(
                        config ->
                                assertThat(config.baseUrl())
                                        .isEqualTo("https://env-rail.example.test/v4"));
    }

    /**
     * [AC-2] model-only 覆盖（清端点保留模型）：覆盖轨仍生效——端点回退 env 端点、模型用覆盖值， 句柄与 GET
     * 投影同源（source=custom、model=覆盖值、baseUrl=env 回退值），不出现「视图报 custom 而轨道走纯 env」的矛盾。
     */
    @Test
    @DisplayName("[AC-2] model-only 覆盖生效且视图与轨道一致")
    void ac2_modelOnlyOverrideConsistentWithRail() {
        // 清端点覆盖 + 保留模型覆盖 → 存储 {baseUrl:null, model:"glm-override"}。
        LlmConfigView view =
                tutorService.updateAdminConfig(
                        new LlmConfigUpdateRequest(false, "", "glm-override", null, null), 1L);

        // 视图：source=custom（任一覆盖存在）、模型=覆盖值、端点=env 回退值。
        assertThat(view.source()).isEqualTo(LlmConfigView.SOURCE_CUSTOM);
        assertThat(view.model()).isEqualTo("glm-override");
        assertThat(view.baseUrl()).isEqualTo("https://env-rail.example.test/v4");
        assertThat(view.hasKey()).isTrue();

        // 轨道：句柄模型=覆盖值（模型覆盖真实生效），视图端点/模型与轨道逐字段一致。
        assertThat(llmClients.chatHandle())
                .hasValueSatisfying(handle -> assertThat(handle.model()).isEqualTo("glm-override"));
        assertThat(llmClients.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.baseUrl())
                                    .isEqualTo("https://env-rail.example.test/v4");
                            assertThat(config.model()).isEqualTo("glm-override");
                            assertThat(config.hasKey()).isTrue();
                            assertThat(config.credentialsComplete()).isTrue();
                        });

        // GET 再次回读：投影与轨道仍同源（覆盖轨缓存不掩盖一致性）。
        LlmConfigView reRead = tutorService.getAdminConfig();
        assertThat(reRead.source()).isEqualTo(LlmConfigView.SOURCE_CUSTOM);
        assertThat(reRead.model()).isEqualTo("glm-override");
        assertThat(reRead.baseUrl()).isEqualTo("https://env-rail.example.test/v4");
    }

    /** [AC-3]/[AC-1] tutor 密钥：PUT apiKey 加密入库（密文列无明文、last4 投影）、clearKey 删行后回落 env。 */
    @Test
    @DisplayName("[AC-3] tutor 密钥加密入库/last4 投影/清除删行")
    void ac3_tutorKeyVaultLifecycle() {
        // 录入密钥（enabled=false 不触发开启检查）：llm_provider_keys 预留 id=tutor，密文列无明文。
        tutorService.updateAdminConfig(
                new LlmConfigUpdateRequest(false, OVERRIDE_BASE_URL, null, TUTOR_KEY, null), 1L);

        String cipher =
                jdbcTemplate.queryForObject(
                        "SELECT secret_cipher FROM llm_provider_keys WHERE provider_id = ?",
                        String.class,
                        LlmProviderKeys.TUTOR_PROVIDER_ID);
        assertThat(cipher).isNotEqualTo(TUTOR_KEY).doesNotContain(TUTOR_KEY);
        String last4 =
                jdbcTemplate.queryForObject(
                        "SELECT key_last4 FROM llm_provider_keys WHERE provider_id = ?",
                        String.class,
                        LlmProviderKeys.TUTOR_PROVIDER_ID);
        assertThat(last4).isEqualTo("8811");

        // 覆盖轨 + tutor 密钥：hasKey=true（tutor 入库链）、keyLast4 投影、句柄可用（openai 轨凭证齐备）。
        LlmConfigView view = tutorService.getAdminConfig();
        assertThat(view.hasKey()).isTrue();
        assertThat(view.keyLast4()).isEqualTo("8811");
        assertThat(view.source()).isEqualTo(LlmConfigView.SOURCE_CUSTOM);
        assertThat(llmClients.chatHandle()).isPresent();

        // 清除密钥（clearKey=true）→ 删行，密钥链回落 env（hasKey 仍 true，last4 无行不投影）。
        LlmConfigView afterClear =
                tutorService.updateAdminConfig(
                        new LlmConfigUpdateRequest(false, OVERRIDE_BASE_URL, null, null, true), 1L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM llm_provider_keys WHERE provider_id = ?",
                                Long.class,
                                LlmProviderKeys.TUTOR_PROVIDER_ID))
                .isZero();
        assertThat(afterClear.hasKey()).isTrue();
        assertThat(afterClear.keyLast4()).isNull();
    }

    /** [AC-1] enabled=true 且无可用客户端（目录激活条目凭证不齐）→ 40000 且本次覆盖/密钥写入整体回滚。 */
    @Test
    @DisplayName("[AC-1] 开启被拒时覆盖与密钥写回滚")
    void ac1_enableBlockedRollsBackWrites() {
        // 目录非空 + 激活条目（openai 轨无密钥）：无论覆盖轨如何，答疑都开不了。
        providerAdminService.create(
                new LlmProviderCreateRequest(
                        PROVIDER_ID,
                        "回滚集成测试",
                        "openai-chat",
                        "https://directory.example.test/v4",
                        "glm-directory",
                        null),
                1L);
        providerAdminService.activate(PROVIDER_ID, 1L);

        assertThatThrownBy(
                        () ->
                                tutorService.updateAdminConfig(
                                        new LlmConfigUpdateRequest(
                                                true,
                                                OVERRIDE_BASE_URL,
                                                "glm-override",
                                                TUTOR_KEY,
                                                null),
                                        1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getMessage()).contains("缺少密钥"));

        // 同事务回滚：覆盖未落库（llm.tutor 无行）、tutor 密钥行未入库。
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM app_settings WHERE `key` = 'llm.tutor'",
                                Long.class))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM llm_provider_keys WHERE provider_id = ?",
                                Long.class,
                                LlmProviderKeys.TUTOR_PROVIDER_ID))
                .isZero();
    }

    /** [AC-1] 开启成功路径：覆盖 + 密钥一次 PUT 携带 enabled=true → 覆盖轨齐备即开（200、开关落库）。 */
    @Test
    @DisplayName("[AC-1] 覆盖与密钥齐备时开启成功")
    void ac1_enableSucceedsWithCompleteOverrideRail() {
        LlmConfigView view =
                tutorService.updateAdminConfig(
                        new LlmConfigUpdateRequest(
                                true, OVERRIDE_BASE_URL, "glm-override", TUTOR_KEY, null),
                        1L);

        assertThat(view.enabled()).isTrue();
        assertThat(view.source()).isEqualTo(LlmConfigView.SOURCE_CUSTOM);
        assertThat(view.hasKey()).isTrue();
        assertThat(view.keyLast4()).isEqualTo("8811");
        assertThat(llmClients.chatHandle())
                .hasValueSatisfying(handle -> assertThat(handle.model()).isEqualTo("glm-override"));
    }

    /** [AC-1] 覆盖校验拒绝：URL 形状非法 40000，且不产生任何写（app_settings 与密钥行保持基线）。 */
    @Test
    @DisplayName("[AC-1] 覆盖形状校验拒绝且零写入")
    void ac1_invalidOverrideShapeRejectedWithoutWrites() {
        assertThatThrownBy(
                        () ->
                                tutorService.updateAdminConfig(
                                        new LlmConfigUpdateRequest(
                                                false,
                                                "ftp://127.0.0.1:1/v4",
                                                null,
                                                TUTOR_KEY,
                                                null),
                                        1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getMessage()).contains("http:// 或 https://"));

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM app_settings WHERE `key` = 'llm.tutor'",
                                Long.class))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM llm_provider_keys WHERE provider_id = ?",
                                Long.class,
                                LlmProviderKeys.TUTOR_PROVIDER_ID))
                .isZero();
    }

    /** [AC-5] 预算强制门（答疑）：本月已用 ≥100% 时 ask 一律 40306 固定文案——预算门先于三态门与可见性校验，不触达 LLM。 */
    @Test
    @DisplayName("[AC-5] 预算用尽答疑 40306（预算门先于三态门）")
    void ac5_askRejectedWith40306WhenBudgetExhausted() {
        // 预算 1 元，本月已用 10 元 → EXCEEDED；未播种题目：门若未生效会落到 40402 而非 40306。
        seedBudget(1L, 10_000_000L);

        assertThatThrownBy(() -> tutorService.ask(7L, "01HXXXBUDGETITSENTENCE0000", "为什么？"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode())
                                    .isEqualTo(ApiCode.MODEL_BUDGET_EXHAUSTED);
                            assertThat(exception.getMessage())
                                    .isEqualTo("本月模型预算已用尽，请下月再试或联系管理员调整预算");
                        });
    }

    /** [AC-5] 预算未用尽：护栏直通不误拦——ask 走既有链路（无播种题目落到 40402），WARN 区间同样放行。 */
    @Test
    @DisplayName("[AC-5] 预算未用尽（含 80%+ WARN）护栏直通")
    void ac5_askPassesWhenBudgetNotExhausted() {
        // 预算 100 元，已用 10 元（10%）→ OK 直通；WARN 边界语义另由 BudgetGuardServiceTest 锁定。
        seedBudget(100L, 10_000_000L);

        // 直通后的落点：本类未开答疑（三态门之二 40304）——预算门没有把请求拦在 40306，
        // 链路照常走到下一道门（若误拦会得到 40306 而非 40304）。
        assertThatThrownBy(() -> tutorService.ask(7L, "01HXXXBUDGETITSENTENCE0000", "为什么？"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode()).isEqualTo(ApiCode.ASK_NOT_OPEN));
    }

    /**
     * 播种一条 llm_usage 用量行（预算护栏/用量视图的事实数据）。
     *
     * @param scene 场景标识
     * @param promptTokens 提示词 token
     * @param completionTokens 回复 token
     * @param costMicroCny 估算成本微元；null=未定价行
     * @param createdAtSql created_at 的 SQL 表达式（如 {@code UTC_TIMESTAMP(6)}）
     */
    private void seedUsage(
            String scene,
            int promptTokens,
            int completionTokens,
            Long costMicroCny,
            String createdAtSql) {
        jdbcTemplate.update(
                "INSERT INTO llm_usage (scene, ok, prompt_tokens, completion_tokens,"
                        + " estimated_cost_micro_cny, created_at) VALUES (?, 1, ?, ?, ?, "
                        + createdAtSql
                        + ")",
                scene,
                promptTokens,
                completionTokens,
                costMicroCny);
    }

    /** [AC-6] 用量视图（空表 + 未设预算）：status=UNSET、预算/百分比为 null、已用 0、三场景零值兜底。 */
    @Test
    @DisplayName("[AC-6] 未设预算空表用量视图 UNSET 且三场景零值兜底")
    void ac6_usageViewUnsetOnEmptyTable() {
        LlmUsageView view = tutorService.getAdminUsage();

        assertThat(view.monthUtc()).isEqualTo(YearMonth.now(ZoneOffset.UTC).toString());
        assertThat(view.budgetCny()).isNull();
        assertThat(view.spentCny()).isEqualByComparingTo("0");
        assertThat(view.percent()).isNull();
        assertThat(view.status()).isEqualTo(LlmUsageView.STATUS_UNSET);
        Map<String, LlmUsageView.SceneUsage> byScene = view.byScene();
        assertThat(byScene.keySet())
                .containsExactlyInAnyOrder(
                        LlmUsageView.SCENE_TUTOR_ASK,
                        LlmUsageView.SCENE_REVIEW,
                        LlmUsageView.SCENE_APPEAL);
        byScene.values().forEach(scene -> assertThat(scene.tokens()).isZero());
    }

    /**
     * [AC-6] 用量视图（跨场景聚合 + WARN）：多场景多行聚合（失败行与未知场景按实计入）、成本 NULL 行不进 已用金额、上月行被 UTC 月口径排除；已设预算时
     * status/percent 按实算回显。
     */
    @Test
    @DisplayName("[AC-6] 跨场景聚合已用并按 WARN 状态回显")
    void ac6_usageViewAggregatesBySceneAndWarns() {
        // 本月事实：tutor-ask 两行（其中一行未定价 cost=NULL，只进 token/次数不进金额）、
        // review/appeal 各一行、未知场景一行、上月一行（跨月排除）。
        seedUsage("tutor-ask", 100, 50, 12_000_000L, "UTC_TIMESTAMP(6)");
        seedUsage("tutor-ask", 10, 0, null, "UTC_TIMESTAMP(6)");
        seedUsage("review", 200, 100, 30_000_000L, "UTC_TIMESTAMP(6)");
        seedUsage("appeal", 1, 1, 1_500_000L, "UTC_TIMESTAMP(6)");
        seedUsage("custom-x", 0, 0, 1_000_000L, "UTC_TIMESTAMP(6)");
        seedUsage("tutor-ask", 999, 999, 999_999_999L, "UTC_TIMESTAMP(6) - INTERVAL 1 MONTH");
        jdbcTemplate.update(
                "INSERT INTO app_settings (`key`, value, description) VALUES ('llm.budget', ?,"
                        + " 'budget-it') ON DUPLICATE KEY UPDATE value = VALUES(value)",
                "{\"schemaVersion\":1,\"monthlyBudgetCny\":50,\"pricing\":null}");

        LlmUsageView view = tutorService.getAdminUsage();

        // 已用 = 12M + 30M + 1.5M + 1M = 44.5 元（NULL 行与上月行不计入）。
        assertThat(view.spentCny()).isEqualByComparingTo("44.5");
        assertThat(view.budgetCny()).isEqualByComparingTo("50");
        assertThat(view.percent()).isEqualTo(89);
        assertThat(view.status()).isEqualTo(LlmUsageView.STATUS_WARN);
        assertThat(view.byScene().get("tutor-ask").tokens()).isEqualTo(160);
        assertThat(view.byScene().get("tutor-ask").costMicroCny()).isEqualTo(12_000_000L);
        assertThat(view.byScene().get("tutor-ask").calls()).isEqualTo(2);
        assertThat(view.byScene().get(LlmUsageView.SCENE_REVIEW).tokens()).isEqualTo(300);
        assertThat(view.byScene().get(LlmUsageView.SCENE_REVIEW).costMicroCny())
                .isEqualTo(30_000_000L);
        assertThat(view.byScene().get(LlmUsageView.SCENE_APPEAL).tokens()).isEqualTo(2);
        assertThat(view.byScene().get(LlmUsageView.SCENE_APPEAL).calls()).isEqualTo(1);
        // 未知场景按实追加。
        assertThat(view.byScene().get("custom-x").costMicroCny()).isEqualTo(1_000_000L);
    }
}
