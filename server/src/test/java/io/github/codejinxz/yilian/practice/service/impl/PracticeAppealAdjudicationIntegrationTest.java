package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.common.ratelimit.RateLimitDecision;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitService;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.ImportedSentence;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.practice.dao.PromptVersionDao;
import io.github.codejinxz.yilian.practice.llm.LlmChatClient;
import io.github.codejinxz.yilian.practice.llm.LlmClientRegistry;
import io.github.codejinxz.yilian.practice.service.AdminAppealService;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.vo.AppealView;
import io.github.codejinxz.yilian.practice.service.vo.AttemptView;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 申诉初裁与 overlay 判分集成测试（真实 MySQL）[AC-2]/[AC-4]/[AC-5]/[AC-6]（[AC-4] 为 029 补：占位回执 verdict=UNAVAILABLE
 * 可达）。
 *
 * <p>初裁（[AC-2]）：APPEAL 落库后同步调 LLM，成功落 decision/decision_reason/decided_by=LLM/
 * decided_at/model_snapshot/prompt_version_id 而 status 保持 PENDING；失败/非法输出（重试 1 次）与限流（429 语义）落
 * {"verdict":"UNAVAILABLE"} 占位，申诉本身不拒、不阻塞提交；llm_usage scene=appeal 双写旁路。overlay（[AC-5]）：admin 终审
 * ACCEPTED 后，本句后续 attempt 以 proposed_answer 作答该槽判对；PENDING/REJECTED
 * 不影响。prompt_version_id（[AC-6]）：初裁行与 MODEL 复核行落 PromptRegistry 的版本 id。
 *
 * <p>LLM 用可编程桩客户端经桩注册表注入；限流用脚本化限流器锁「超限跳过初裁」的完整语义 （与 {@code PracticeModelReviewIntegrationTest}
 * 同款基建）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class PracticeAppealAdjudicationIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 6, 12, 0, 0));

    private static final long USER_ID = 26L;

    private static final long ADMIN_ID = 2L;

    private static final String REF = "He is paying more and more attention to this matter.";

    /** 用户主张的替代写法：初裁 SUPPORT、终审 ACCEPTED 后应判对的槽位答案。 */
    private static final String PROPOSED = "She is paying more and more attention to this matter.";

    private static final String WRONG = "He is paying less and less attention to this matter.";

    @Autowired private PracticeService practiceService;

    @Autowired private AdminAppealService adminAppealService;

    @Autowired private ContentImportService contentImportService;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private LlmClientRegistry llmClients;

    @Autowired private PromptVersionDao promptVersionDao;

    @Autowired private RateLimitService rateLimitService;

    @Autowired private Environment environment;

    private String baseUrl;

    private ImportedSentence seeded;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 用例后清库并重建触发器，保证重复运行与其他套件互不污染。 */
    @AfterEach
    void cleanAfter() {
        cleanDatabase();
    }

    /** 幂等播种用户与题目；桩客户端与脚本限流器逐用例复位。 */
    @BeforeEach
    void cleanAndSeed() {
        cleanDatabase();
        seedUser(USER_ID, "it-appealer");
        seedSentence();
        stubChatClient().reset();
        scriptedRateLimit().permitAll();
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
    }

    /**
     * [AC-5] 预算强制门（申诉初裁）：本月已用 ≥100% 时跳过初裁（零模型调用零记账），落 {"verdict":"UNAVAILABLE","reason":"BUDGET"}
     * 占位——申诉本身不拒不阻塞（200 + PENDING 等人工终审）， 与限流跳过同形态，reason=BUDGET 让读端可归因于预算而非通用不可用。
     */
    @Test
    @DisplayName("[AC-5] 预算用尽初裁落 BUDGET 占位且申诉不阻塞")
    void ac5_budgetExhaustedPlacesBudgetPlaceholder() {
        AttemptView submitted = submitWrongAttempt();
        // 预算 1 元，本月已用 10 元 → EXCEEDED。
        jdbcTemplate.update(
                "INSERT INTO app_settings (`key`, value, description) VALUES ('llm.budget', ?, 'it')"
                        + " ON DUPLICATE KEY UPDATE value = VALUES(value)",
                "{\"schemaVersion\":1,\"monthlyBudgetCny\":1,\"pricing\":null}");
        jdbcTemplate.update(
                "INSERT INTO llm_usage (scene, ok, estimated_cost_micro_cny, created_at)"
                        + " VALUES ('budget-it', 1, 10000000, UTC_TIMESTAMP(6))");

        AppealView appeal =
                practiceService.submitAppeal(
                        USER_ID, submitted.attemptId(), "slot:0", PROPOSED, "主语可换她");

        // 申诉不阻塞：200 + PENDING，占位回执 verdict=UNAVAILABLE（decidedBy=null=无 AI 意见）。
        assertThat(appeal.status()).isEqualTo("PENDING");
        assertThat(appeal.verdict()).isEqualTo("UNAVAILABLE");
        assertThat(appeal.decidedBy()).isNull();
        assertThat(appeal.decisionReason())
                .isEqualTo(PracticeServiceImpl.APPEAL_ADJUDICATION_BUDGET_REASON);
        // 存储真相：decision 列落 BUDGET 占位 JSON。
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT decision FROM appeals WHERE id = ?", appeal.appealId());
        // JSON 列落库后键序与空白被 MySQL 归一：只断言两个语义值在位（UNAVAILABLE + BUDGET 归因）。
        assertThat((String) row.get("decision")).contains("UNAVAILABLE").contains("BUDGET");
        // 零模型调用零记账：预算门在调用前拦下。
        assertThat(stubChatClient().calls.get()).isZero();
        Long appealUsageRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM llm_usage WHERE scene = 'appeal'", Long.class);
        assertThat(appealUsageRows).isZero();
    }

    // ---------- [AC-2] 初裁成功 ----------

    /** [AC-2] 初裁成功：裁决列落值且 status 保持 PENDING；回执带 AI 意见；llm_usage scene=appeal 记账。 */
    @Test
    @DisplayName("[AC-2] 初裁成功落裁决列且 status 保持 PENDING")
    void ac2_adjudicationSuccessPersistsColumnsAndKeepsPending() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().reply("{\"verdict\": \"SUPPORT\", \"reason\": \"语义等价，可以接受\"}");

        AppealView appeal =
                practiceService.submitAppeal(
                        USER_ID, submitted.attemptId(), "slot:0", PROPOSED, "主语可换她");

        assertThat(appeal.status()).isEqualTo("PENDING");
        assertThat(appeal.kind()).isEqualTo("APPEAL");
        assertThat(appeal.verdict()).isEqualTo("SUPPORT");
        assertThat(appeal.decisionReason()).isEqualTo("语义等价，可以接受");
        assertThat(appeal.decidedBy()).isEqualTo("LLM");
        assertThat(appeal.decidedAt()).isNotNull();

        // 落库证据：裁决六列按口径落值；status/提交数据不动。
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT status, decision, decision_reason, decided_by, decided_at,"
                                + " model_snapshot, prompt_version_id, proposed_answer"
                                + " FROM appeals WHERE id = ?",
                        appeal.appealId());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat((String) row.get("decision")).contains("\"verdict\"").contains("SUPPORT");
        assertThat(row.get("decision_reason")).isEqualTo("语义等价，可以接受");
        assertThat(row.get("decided_by")).isEqualTo("LLM");
        assertThat(row.get("decided_at")).isNotNull();
        assertThat(row.get("model_snapshot")).isEqualTo("stub:stub-review-model");
        assertThat(row.get("proposed_answer")).isEqualTo(PROPOSED);
        // [AC-6] 初裁行落 PromptRegistry 的 appeal 版本 id。
        Long appealPromptVersionId = ((Number) row.get("prompt_version_id")).longValue();
        assertThat(appealPromptVersionId)
                .isEqualTo(promptVersionDao.findActiveByName("appeal").orElseThrow().getId());

        Long usageRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM llm_usage WHERE scene = 'appeal' AND ok = b'1'",
                        Long.class);
        assertThat(usageRows).isEqualTo(1L);
    }

    /**
     * [AC-3] 已定价模型的初裁用量行落估算成本列：llm.budget 配置 stub-review-model 单价（输入 1 元/百万、 输出 2 元/百万），桩用量
     * prompt=100/completion=25 → 100×1 + 25×2 = 150 微元（预算未设，护栏不生效）。
     */
    @Test
    @DisplayName("[AC-3] 已定价模型初裁落估算成本 150 微元")
    void ac3_appealUsageRowRecordsEstimatedCost() {
        AttemptView submitted = submitWrongAttempt();
        jdbcTemplate.update(
                "INSERT INTO app_settings (`key`, value, description) VALUES ('llm.budget', ?, 'it')"
                        + " ON DUPLICATE KEY UPDATE value = VALUES(value)",
                "{\"schemaVersion\":1,\"monthlyBudgetCny\":null,"
                        + "\"pricing\":{\"stub-review-model\":"
                        + "{\"inputPerMillionCny\":1,\"outputPerMillionCny\":2}}}");
        stubChatClient().reply("{\"verdict\": \"SUPPORT\", \"reason\": \"语义等价，可以接受\"}");

        practiceService.submitAppeal(USER_ID, submitted.attemptId(), "slot:0", PROPOSED, "主语可换她");

        BigDecimal costMicroCny =
                jdbcTemplate.queryForObject(
                        "SELECT estimated_cost_micro_cny FROM llm_usage WHERE scene = 'appeal'",
                        BigDecimal.class);
        assertThat(costMicroCny).isEqualByComparingTo("150");
    }

    /** [AC-2] 调用失败重试 1 次仍败：落 UNAVAILABLE 占位，decided_by 为 NULL（无 AI 意见），申诉不被拒。 */
    @Test
    @DisplayName("[AC-2] 初裁失败落 UNAVAILABLE 占位且申诉不拒")
    void ac2_adjudicationFailureRecordsPlaceholder() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().failure();
        stubChatClient().failure();

        AppealView appeal =
                practiceService.submitAppeal(
                        USER_ID, submitted.attemptId(), "slot:0", PROPOSED, null);

        assertThat(appeal.status()).isEqualTo("PENDING");
        // 029 [AC-4]：回执 verdict 对齐存储真相——decision 落 UNAVAILABLE JSON，回执也发 UNAVAILABLE。
        assertThat(appeal.verdict()).isEqualTo("UNAVAILABLE");
        assertThat(appeal.decidedBy()).isNull();

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT status, decision, decision_reason, decided_by, decided_at,"
                                + " model_snapshot, prompt_version_id"
                                + " FROM appeals WHERE id = ?",
                        appeal.appealId());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat((String) row.get("decision")).contains("UNAVAILABLE");
        assertThat((String) row.get("decision_reason")).isNotBlank();
        assertThat(row.get("decided_by")).isNull();
        assertThat(row.get("decided_at")).isNull();
        assertThat(row.get("model_snapshot")).isNull();
        assertThat(row.get("prompt_version_id")).isNull();
        Long usageRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM llm_usage WHERE scene = 'appeal' AND ok = b'0'",
                        Long.class);
        assertThat(usageRows).isEqualTo(2L);
    }

    /** [AC-2] 输出非法（两次都不可解析）：token 消耗真实发生（ok=true），仍落占位。 */
    @Test
    @DisplayName("[AC-2] 非法输出重试仍败落占位且用量记 ok=true")
    void ac2_invalidOutputRecordsPlaceholderWithUsage() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().reply("这不是 JSON");
        stubChatClient().reply("{\"verdict\": \"MAYBE\", \"reason\": \"骑墙\"}");

        AppealView appeal =
                practiceService.submitAppeal(
                        USER_ID, submitted.attemptId(), "slot:0", PROPOSED, null);

        assertThat(appeal.verdict()).isEqualTo("UNAVAILABLE");
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT decision, decided_by FROM appeals WHERE id = ?", appeal.appealId());
        assertThat((String) row.get("decision")).contains("UNAVAILABLE");
        assertThat(row.get("decided_by")).isNull();
        Long usageRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM llm_usage WHERE scene = 'appeal' AND ok = b'1'",
                        Long.class);
        assertThat(usageRows).isEqualTo(2L);
    }

    /** [AC-2] 重试后成功：第一次异常、第二次合法输出 → 正常落裁决列。 */
    @Test
    @DisplayName("[AC-2] 重试后成功正常落裁决")
    void ac2_retryRecovers() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().failure();
        stubChatClient().reply("{\"verdict\": \"OPPOSE\", \"reason\": \"语义偏离\"}");

        AppealView appeal =
                practiceService.submitAppeal(
                        USER_ID, submitted.attemptId(), "slot:0", PROPOSED, null);

        assertThat(appeal.verdict()).isEqualTo("OPPOSE");
        assertThat(appeal.decidedBy()).isEqualTo("LLM");
        assertThat(stubChatClient().calls.get()).isEqualTo(2);
    }

    /** [AC-2] 限流（独立 scene，5 次/分钟/账号）：初裁跳过落占位，申诉本身不拒、模型零调用。 */
    @Test
    @DisplayName("[AC-2] 限流跳过初裁且申诉不拒")
    void ac2_rateLimitedAdjudicationSkippedWithoutRejectingAppeal() {
        AttemptView submitted = submitWrongAttempt();
        scriptedRateLimit().rejectAfterNPermits(0, 42);

        AppealView appeal =
                practiceService.submitAppeal(
                        USER_ID, submitted.attemptId(), "slot:0", PROPOSED, null);

        // 申诉本体成功落库（不因限流被拒），初裁占位。
        assertThat(appeal.status()).isEqualTo("PENDING");
        assertThat(appeal.verdict()).isEqualTo("UNAVAILABLE");
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT decision, decision_reason, decided_by FROM appeals WHERE id = ?",
                        appeal.appealId());
        assertThat((String) row.get("decision")).contains("UNAVAILABLE");
        assertThat(row.get("decided_by")).isNull();
        assertThat(stubChatClient().calls.get()).isZero();
    }

    /** [AC-2] DISPUTE 不触发初裁：模型零调用，回执裁决字段为空。 */
    @Test
    @DisplayName("[AC-2] DISPUTE 不触发初裁")
    void ac2_disputeDoesNotAdjudicate() {
        AttemptView submitted = submitWrongAttempt();

        AppealView dispute =
                practiceService.submitDispute(USER_ID, submitted.attemptId(), "slot:0", "我认为也对");

        assertThat(dispute.status()).isEqualTo("ACCEPTED");
        assertThat(dispute.verdict()).isNull();
        assertThat(dispute.decidedBy()).isNull();
        assertThat(stubChatClient().calls.get()).isZero();
    }

    /**
     * [AC-4] 「初裁不可用」回执态真实可达（029 F-01 修复）：占位路径回执 verdict=UNAVAILABLE 且与 decision 列的
     * {"verdict":"UNAVAILABLE"} 同源——前端「AI 初裁暂不可用」分支对真实服务端有效， 用户端不再退化为兜底文案「申诉已记录」。
     */
    @Test
    @DisplayName("[AC-4] 占位回执 verdict=UNAVAILABLE 与存储 decision JSON 同源")
    void ac4_unavailableVerdictIsExpressedInReceipt_ac4() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().failure();
        stubChatClient().failure();

        AppealView appeal =
                practiceService.submitAppeal(
                        USER_ID, submitted.attemptId(), "slot:0", PROPOSED, null);

        // 回执四元组：verdict=UNAVAILABLE、占位理由、无裁决者与时间（无 AI 意见的判据保持 decided_by=null）。
        assertThat(appeal.status()).isEqualTo("PENDING");
        assertThat(appeal.verdict()).isEqualTo("UNAVAILABLE");
        assertThat(appeal.decisionReason()).isEqualTo("AI 初裁暂不可用，等待人工终审");
        assertThat(appeal.decidedBy()).isNull();
        assertThat(appeal.decidedAt()).isNull();
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT decision, decision_reason, decided_by FROM appeals WHERE id = ?",
                        appeal.appealId());
        // decision 是 MySQL JSON 列（读回带归一化空格），按 JSON 语义断言 verdict 字段而不是字面串。
        assertThat(objectMapper.readTree((String) row.get("decision")).get("verdict").asString())
                .isEqualTo("UNAVAILABLE");
        assertThat((String) row.get("decision_reason")).isEqualTo("AI 初裁暂不可用，等待人工终审");
        assertThat(row.get("decided_by")).isNull();
    }

    // ---------- [AC-5] overlay 判分 ----------

    /** [AC-5] 终审 ACCEPTED 后 overlay 生效：同句重打以 proposed_answer 作答判对。 */
    @Test
    @DisplayName("[AC-5] ACCEPTED 申诉的写法进入 overlay 判对")
    void ac5_acceptedAppealJoinsGradingOverlay() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().reply("{\"verdict\": \"SUPPORT\", \"reason\": \"语义等价\"}");
        AppealView appeal =
                practiceService.submitAppeal(
                        USER_ID, submitted.attemptId(), "slot:0", PROPOSED, null);

        // 终审前：PENDING 不参与 overlay，同写法仍判错。
        AttemptView beforeAcceptance = retryWithProposed(submitted);
        assertThat(beforeAcceptance.score()).isLessThan(100);

        adminAppealService.decide(appeal.appealId(), "ACCEPTED", "语义等价，收进可接受答案", ADMIN_ID);

        // 终审后：同写法判对（经 overlay 注入可接受答案，与 answers[] 同归一化）。
        AttemptView afterAcceptance = retryWithProposed(submitted);
        assertThat(afterAcceptance.score()).isEqualTo(100);
        assertThat(afterAcceptance.result().annotations().get(0).correct()).isTrue();
    }

    /** [AC-5] REJECTED 不参与 overlay：驳回后同写法仍判错，题目数据未被改写。 */
    @Test
    @DisplayName("[AC-5] REJECTED 申诉不影响判分")
    void ac5_rejectedAppealDoesNotAffectGrading() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().reply("{\"verdict\": \"OPPOSE\", \"reason\": \"语义偏离\"}");
        AppealView appeal =
                practiceService.submitAppeal(
                        USER_ID, submitted.attemptId(), "slot:0", PROPOSED, null);

        adminAppealService.decide(appeal.appealId(), "REJECTED", "与参考语义相反", ADMIN_ID);

        AttemptView retried = retryWithProposed(submitted);
        assertThat(retried.score()).isLessThan(100);
        // 题目内容未改写（D-015）：sentence_versions 的可接受答案不含用户主张。
        Long variantRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentence_versions sv"
                                + " JOIN sentences s ON s.current_version_id = sv.id"
                                + " WHERE s.id = ?"
                                + " AND JSON_SEARCH(sv.chunks, 'one', ?) IS NOT NULL",
                        Long.class,
                        seeded.sentenceId(),
                        PROPOSED);
        assertThat(variantRows).isZero();
    }

    /** [AC-5] DISPUTE 的 ACCEPTED 是排除语义：不进判分 overlay。 */
    @Test
    @DisplayName("[AC-5] DISPUTE 不进判分 overlay")
    void ac5_disputeNotInGradingOverlay() {
        AttemptView submitted = submitWrongAttempt();
        practiceService.submitDispute(USER_ID, submitted.attemptId(), "slot:0", "排除这条");

        // DISPUTE 无 proposed_answer，overlay 无可注入项；判分口径不变（仍按参考写法判错）。
        AttemptView retried = retryWithProposed(submitted);
        assertThat(retried.score()).isLessThan(100);
    }

    // ---------- [AC-6] MODEL 复核行 prompt_version_id ----------

    /** [AC-6] 复核 MODEL 行落 PromptRegistry 的 review 版本 id；本地首判行保持 NULL。 */
    @Test
    @DisplayName("[AC-6] MODEL 复核行落 prompt 版本 id")
    void ac6_modelReviewRowRecordsPromptVersionId() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().reply("{\"score\": 88, \"reason\": \"整体可懂\"}");

        practiceService.reviewAttempt(USER_ID, submitted.attemptId());

        Map<String, Object> modelRow =
                jdbcTemplate.queryForMap(
                        "SELECT grader, prompt_version_id FROM attempt_gradings"
                                + " WHERE attempt_id = ? AND grader = 'MODEL'",
                        submitted.attemptId());
        assertThat(modelRow.get("grader")).isEqualTo("MODEL");
        assertThat(((Number) modelRow.get("prompt_version_id")).longValue())
                .isEqualTo(promptVersionDao.findActiveByName("review").orElseThrow().getId());
        Map<String, Object> localRow =
                jdbcTemplate.queryForMap(
                        "SELECT grader, prompt_version_id FROM attempt_gradings"
                                + " WHERE attempt_id = ? AND grader = 'LOCAL'",
                        submitted.attemptId());
        assertThat(localRow.get("prompt_version_id")).isNull();
    }

    // ---------- 桩：LLM 注册表与脚本限流器 ----------

    /** 可编程 LLM 桩：按脚本依次回复或抛错，计数调用次数。 */
    static final class StubChatClient implements LlmChatClient {

        private final Deque<Object> script = new ArrayDeque<>();

        private final AtomicInteger calls = new AtomicInteger();

        /** 复位脚本与计数（逐用例调用）。 */
        void reset() {
            script.clear();
            calls.set(0);
        }

        /**
         * 追加一条合法回复。
         *
         * @param json 模型输出正文
         */
        void reply(String json) {
            script.add(new Reply(json, 100, 25));
        }

        /** 追加一次调用异常（网络/超时/供应商错误的模拟）。 */
        void failure() {
            script.add(new RuntimeException("stub-llm-down"));
        }

        @Override
        public Reply ask(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            Object next = script.isEmpty() ? new Reply("stub-default", 100, 25) : script.poll();
            if (next instanceof RuntimeException error) {
                throw error;
            }
            return (Reply) next;
        }
    }

    /** 桩注册表：永远可用（stub/stub-review-model），协议视图 openai-chat。 */
    static final class StubLlmClientRegistry implements LlmClientRegistry {

        private final StubChatClient client = new StubChatClient();

        @Override
        public java.util.Optional<ChatHandle> chatHandle() {
            return java.util.Optional.of(new ChatHandle(client, "stub", "stub-review-model"));
        }

        @Override
        public java.util.Optional<EffectiveConfig> effectiveConfig() {
            return java.util.Optional.of(
                    new EffectiveConfig(
                            "stub",
                            "openai-chat",
                            "http://stub.local",
                            "stub-review-model",
                            true,
                            true));
        }

        @Override
        public boolean directoryMode() {
            return false;
        }
    }

    /** 脚本化限流器：前 N 次放行、之后拒绝并给出固定 Retry-After。 */
    static final class ScriptedRateLimitService extends RateLimitService {

        private final AtomicInteger calls = new AtomicInteger();

        private volatile int permitFirst = Integer.MAX_VALUE;

        private volatile int retryAfterSeconds = 30;

        ScriptedRateLimitService() {
            super(null, new MockEnvironment());
        }

        /** 全量放行并复位计数。 */
        void permitAll() {
            permitFirst = Integer.MAX_VALUE;
            calls.set(0);
        }

        /**
         * 放行前 N 次后拒绝。
         *
         * @param permits 放行次数
         * @param seconds Retry-After 秒数
         */
        void rejectAfterNPermits(int permits, int seconds) {
            permitFirst = permits;
            retryAfterSeconds = seconds;
            calls.set(0);
        }

        @Override
        public RateLimitDecision acquire(
                String scene,
                List<RateLimitDimension> dimensions,
                int limit,
                int windowSeconds,
                String clientIp,
                String account) {
            if (calls.incrementAndGet() <= permitFirst) {
                return RateLimitDecision.permit();
            }
            return RateLimitDecision.reject(retryAfterSeconds);
        }
    }

    /** 桩装配：替换真实注册表与 Redis 限流器（CI 无 Redis；窗口算法语义由 common 层单测覆盖）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class AppealStubConfig {

        /**
         * 桩注册表（初裁/复核/答疑共用注入点）。
         *
         * @return 桩注册表
         */
        @Bean
        @Primary
        LlmClientRegistry stubLlmClientRegistry() {
            return new StubLlmClientRegistry();
        }

        /**
         * 脚本化限流器（默认全量放行）。
         *
         * @return 脚本化限流器
         */
        @Bean
        @Primary
        RateLimitService scriptedRateLimitService() {
            return new ScriptedRateLimitService();
        }
    }

    // ---------- 测试支撑 ----------

    private StubChatClient stubChatClient() {
        return ((StubLlmClientRegistry) llmClients).client;
    }

    private ScriptedRateLimitService scriptedRateLimit() {
        return (ScriptedRateLimitService) rateLimitService;
    }

    /** 以 PROPOSED 作答 slot:0 重打（RETRY，不触发排程分支差异）。 */
    private AttemptView retryWithProposed(AttemptView original) {
        return practiceService.submitAttempt(
                USER_ID,
                new AttemptCommand(
                        UUID.randomUUID().toString(),
                        seeded.sentenceId(),
                        String.valueOf(seeded.sentenceVersionId()),
                        AttemptCommand.Mode.CLOZE,
                        AttemptCommand.Kind.RETRY,
                        original.attemptId(),
                        new AnswerPayload(
                                1, "CLOZE", List.of(new AnswerPayload.Slot(0, PROPOSED)), null),
                        0,
                        1000,
                        null));
    }

    /** 提交一次 CLOZE FIRST 错题作答并返回判分视图。 */
    private AttemptView submitWrongAttempt() {
        return practiceService.submitAttempt(
                USER_ID,
                new AttemptCommand(
                        UUID.randomUUID().toString(),
                        seeded.sentenceId(),
                        String.valueOf(seeded.sentenceVersionId()),
                        AttemptCommand.Mode.CLOZE,
                        AttemptCommand.Kind.FIRST,
                        null,
                        new AnswerPayload(
                                1, "CLOZE", List.of(new AnswerPayload.Slot(0, WRONG)), null),
                        0,
                        1000,
                        null));
    }

    /** 幂等播种测试用户。 */
    private void seedUser(long id, String username) {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, username, password_hash, role, enabled)"
                        + " VALUES (?, ?, ?, 'seeded', 'USER', TRUE)"
                        + " ON DUPLICATE KEY UPDATE enabled = TRUE",
                id,
                username + "@example.com",
                username);
    }

    /** 清空 practice 五表 + llm_usage + 内容三表并重建 append-only 触发器。 */
    private void cleanDatabase() {
        dropTriggers();
        jdbcTemplate.update("DELETE FROM review_logs");
        jdbcTemplate.update("DELETE FROM reviews");
        jdbcTemplate.update("DELETE FROM appeals");
        jdbcTemplate.update("DELETE FROM app_settings WHERE `key` = 'llm.budget'");
        jdbcTemplate.update("DELETE FROM attempt_gradings");
        jdbcTemplate.update("DELETE FROM llm_usage");
        jdbcTemplate.update("DELETE FROM attempts WHERE retry_of_id IS NOT NULL");
        jdbcTemplate.update("DELETE FROM attempts");
        jdbcTemplate.update("UPDATE sentences SET current_version_id = NULL");
        jdbcTemplate.update("DELETE FROM import_batch_rows");
        jdbcTemplate.update("DELETE FROM sentence_versions");
        jdbcTemplate.update("DELETE FROM sentences");
        jdbcTemplate.update("DELETE FROM import_batches");
        jdbcTemplate.update("DELETE FROM sentence_banks");
        createTriggers();
    }

    /** 摘除 append-only 与 sentence_versions 触发器（清理期允许 DELETE）。 */
    private void dropTriggers() {
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempts_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempts_no_delete");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempt_gradings_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempt_gradings_no_delete");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_review_logs_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_review_logs_no_delete");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_delete");
    }

    /** 重建 append-only 与 sentence_versions 触发器（与 V8/内容迁移语义一致）。 */
    private void createTriggers() {
        jdbcTemplate.update(
                "CREATE TRIGGER trg_attempts_no_update BEFORE UPDATE ON attempts"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'attempts is append-only'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_attempts_no_delete BEFORE DELETE ON attempts"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'attempts is append-only'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_attempt_gradings_no_update BEFORE UPDATE ON attempt_gradings"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'attempt_gradings is append-only'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_attempt_gradings_no_delete BEFORE DELETE ON attempt_gradings"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'attempt_gradings is append-only'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_review_logs_no_update BEFORE UPDATE ON review_logs"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'review_logs is append-only'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_review_logs_no_delete BEFORE DELETE ON review_logs"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'review_logs is append-only'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_sentence_versions_no_update BEFORE UPDATE ON sentence_versions"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'sentence_versions is immutable'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_sentence_versions_no_delete BEFORE DELETE ON sentence_versions"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'sentence_versions is immutable'");
    }

    /** 播种公开发布题库与单题（chunk0=整句，可接受答案=参考译文，挖空 [0]）。 */
    private void seedSentence() {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9202, NULL, '申诉初裁集成题库', NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL,"
                        + " NULL, ?, ?, NULL, NULL)",
                T0,
                T0,
                T0);
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, 9202, 'JSON', 'it.json', 'imports/it.json', ?, 1024,"
                        + " 'COMMITTING', ?, 'admin', ?)",
                "batch-it-0250-0000-0000-000000000001",
                "b".repeat(64),
                USER_ID,
                T0);
        List<SentenceContent> rows =
                List.of(
                        new SentenceContent(
                                "s_r0251",
                                "他越来越重视这件事情。",
                                REF,
                                "这是申诉初裁集成测试的解析说明，长度满足最小要求。",
                                List.of(new SentenceChunk(0, "他", List.of(REF), "代词", null)),
                                List.of(0),
                                List.of("代词"),
                                null,
                                null,
                                null));
        seeded =
                contentImportService
                        .importSentences(
                                9202L,
                                "batch-it-0250-0000-0000-000000000001",
                                rows,
                                USER_ID,
                                T0.toInstant())
                        .get(0);
    }
}
