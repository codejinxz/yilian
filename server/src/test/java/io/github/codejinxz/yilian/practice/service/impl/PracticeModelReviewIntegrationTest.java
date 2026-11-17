package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitDecision;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitService;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.ImportedSentence;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.practice.llm.LlmChatClient;
import io.github.codejinxz.yilian.practice.llm.LlmClientRegistry;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.vo.AttemptView;
import io.github.codejinxz.yilian.practice.service.vo.ModelReviewView;
import io.github.codejinxz.yilian.practice.service.vo.TodayView;
import io.github.codejinxz.yilian.support.InsecureCookieHandler;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型复核集成测试（真实 MySQL）：复核落行（sequence+1/grader=MODEL/钳制/快照/耗时）、幂等读、 可见性拒绝、降级不落行、llm_usage 三终态、限流
 * 429、回看统计取最新 sequence 且排程/错题口径不变。
 *
 * <p>LLM 用可编程桩客户端经桩注册表注入（答疑单测的桩法搬到 Spring 上下文里）；限流滑动窗口本身 由 {@code RateLimitServiceTest}/{@code
 * RateLimitAspectTest} 覆盖，这里用脚本化限流器锁 「5 次后 429 + Retry-After」的完整 HTTP 行为——CI 无 Redis，真实 ZSET 计数会
 * fail-open 不可复现。
 *
 * <p>自清模式与 {@code PracticeFlowIntegrationTest} 同口径：append-only 触发器先摘后重建， practice 五表 + llm_usage +
 * 内容三表逐用例清空，users 幂等播种。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class PracticeModelReviewIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 6, 12, 0, 0));

    private static final long USER_ID = 24L;

    private static final long OTHER_USER_ID = 25L;

    private static final String TEST_USER_PASSWORD = "test-reviewer-password";

    private static final String REF = "He is paying more and more attention to this matter.";

    private static final String REVIEW_REASON = "整体可懂，介词搭配需要调整";

    private static final Pattern CSRF_TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    @Autowired private PracticeService practiceService;

    @Autowired private ContentImportService contentImportService;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private LlmClientRegistry llmClients;

    @Autowired private RateLimitService rateLimitService;

    @Autowired private PasswordEncoder passwordEncoder;

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
        seedUser(USER_ID, "it-reviewer");
        seedUser(OTHER_USER_ID, "it-other");
        seedSentence();
        stubChatClient().reset();
        scriptedRateLimit().permitAll();
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
    }

    // ---------- [AC-1] 复核成功落行 ----------

    /** [AC-1] 复核成功：追加 sequence=2/grader=MODEL 行，响应含新分+理由+快照+耗时+首判分，首判行不动。 */
    @Test
    @DisplayName("[AC-1] 复核成功追加 MODEL 判分行并落快照/耗时")
    void ac1_reviewAppendsModelGradingRow() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().reply("{\"score\": 88, \"reason\": \"" + REVIEW_REASON + "\"}");

        ModelReviewView review = practiceService.reviewAttempt(USER_ID, submitted.attemptId());

        assertThat(review.score()).isEqualTo(88);
        assertThat(review.reason()).isEqualTo(REVIEW_REASON);
        assertThat(review.modelSnapshot()).isEqualTo("stub:stub-review-model");
        assertThat(review.latencyMs()).isNotNull();
        assertThat(review.latencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(review.sequence()).isEqualTo(2);
        assertThat(review.originalScore()).isEqualTo(submitted.score());

        // 落库证据：两行判分；首判 LOCAL 不动，复核 MODEL 行带快照与耗时。
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT sequence, grader, score, max_score, model_snapshot, latency_ms,"
                                + " normalizer_version, rubric_version, result"
                                + " FROM attempt_gradings WHERE attempt_id = ? ORDER BY sequence",
                        submitted.attemptId());
        assertThat(rows).hasSize(2);
        Map<String, Object> firstRow = rows.get(0);
        Map<String, Object> reviewRow = rows.get(1);
        assertThat(firstRow.get("grader")).isEqualTo("LOCAL");
        assertThat(firstRow.get("score")).isEqualTo(submitted.score());
        assertThat(reviewRow.get("sequence")).isEqualTo(2);
        assertThat(reviewRow.get("grader")).isEqualTo("MODEL");
        assertThat(reviewRow.get("score")).isEqualTo(88);
        assertThat(reviewRow.get("max_score")).isEqualTo(100);
        assertThat(reviewRow.get("model_snapshot")).isEqualTo("stub:stub-review-model");
        assertThat(reviewRow.get("latency_ms")).isNotNull();
        // 归一化/评分口径版本沿用首判行；prompt 版本注册表落地前不落 prompt_version_id。
        assertThat(reviewRow.get("normalizer_version"))
                .isEqualTo(firstRow.get("normalizer_version"));
        assertThat(reviewRow.get("rubric_version")).isEqualTo(firstRow.get("rubric_version"));
        var parsed = objectMapper.readTree((String) reviewRow.get("result"));
        assertThat(parsed.get("score").asInt()).isEqualTo(88);
        assertThat(parsed.get("summary").asString()).isEqualTo(REVIEW_REASON);
        // 复核行承载首判批注结构：批注条数与首判一致（分数与摘要被替换）。
        var firstParsed = objectMapper.readTree((String) firstRow.get("result"));
        assertThat(parsed.get("annotations").size())
                .isEqualTo(firstParsed.get("annotations").size());
    }

    // ---------- [AC-2] 幂等读 ----------

    /** [AC-2] 已有 MODEL 复核行时再次请求返回既有结果，LLM 只调一次、不追加新行。 */
    @Test
    @DisplayName("[AC-2] 幂等读返回既有复核且 LLM 只调一次")
    void ac2_repeatReviewIsIdempotentRead() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().reply("{\"score\": 88, \"reason\": \"" + REVIEW_REASON + "\"}");

        ModelReviewView first = practiceService.reviewAttempt(USER_ID, submitted.attemptId());
        ModelReviewView second = practiceService.reviewAttempt(USER_ID, submitted.attemptId());

        assertThat(second.score()).isEqualTo(first.score());
        assertThat(second.reason()).isEqualTo(first.reason());
        assertThat(second.sequence()).isEqualTo(first.sequence());
        assertThat(second.modelSnapshot()).isEqualTo(first.modelSnapshot());
        assertThat(stubChatClient().calls.get()).isEqualTo(1);
        Long gradingRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM attempt_gradings WHERE attempt_id = ?",
                        Long.class,
                        submitted.attemptId());
        assertThat(gradingRows).isEqualTo(2L);
    }

    // ---------- [AC-3] 可见性与存在性拒绝 ----------

    /** [AC-3] 他人 attempt 40300、不存在 attempt 40403、REVEAL 无判分 40000，均不触达模型。 */
    @Test
    @DisplayName("[AC-3] 他人/不存在/REVEAL 提交拒绝且不触达模型")
    void ac3_visibilityAndExistenceRejections() {
        AttemptView mine = submitWrongAttempt();

        // 他人 attempt：40300，模型零调用。
        assertThatThrownBy(() -> practiceService.reviewAttempt(OTHER_USER_ID, mine.attemptId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode()).isEqualTo(ApiCode.ACCESS_DENIED));
        // 不存在 attempt：40403（答题记录不存在，复用练习史口径），模型零调用。
        assertThatThrownBy(
                        () -> practiceService.reviewAttempt(USER_ID, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.ATTEMPT_NOT_FOUND));
        // REVEAL 提交没有判分：40000，模型零调用。
        AttemptView reveal = submitRevealAttempt();
        assertThatThrownBy(() -> practiceService.reviewAttempt(USER_ID, reveal.attemptId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode()).isEqualTo(ApiCode.VALIDATION_FAILED);
                            assertThat(exception.getMessage()).isEqualTo("该提交没有可复核的判分");
                        });
        assertThat(stubChatClient().calls.get()).isZero();
    }

    /** [AC-3] 匿名请求复核：401 信封（code=40100），不进入业务。 */
    @Test
    @DisplayName("[AC-3] 匿名复核请求 40100")
    void ac3_anonymousReviewRejectedWith401() throws Exception {
        HttpClient client = newClient();
        // 匿名也要带 CSRF 头：缺 CSRF 是 403（AccessDeniedHandler），不带 Authorization 才落到 401 入口点。
        String csrf = csrfToken(client);
        HttpResponse<String> response =
                send(
                        client,
                        "POST",
                        "/api/practice/attempts/" + UUID.randomUUID() + "/review",
                        null,
                        Map.of("X-XSRF-TOKEN", csrf));
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":" + ApiCode.AUTH_REQUIRED.code());
    }

    // ---------- [AC-4] 降级不落行 ----------

    /** [AC-4] 模型异常重试 1 次后仍败：50000 固定文案、不落行、原判分保留、llm_usage 记 ok=false。 */
    @Test
    @DisplayName("[AC-4] 模型异常重试仍败降级 50000 不落行")
    void ac4_modelFailureDegradesWithoutNewRow() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().failure();
        stubChatClient().failure();

        assertThatThrownBy(() -> practiceService.reviewAttempt(USER_ID, submitted.attemptId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode()).isEqualTo(ApiCode.INTERNAL_ERROR);
                            assertThat(exception.getMessage()).isEqualTo("模型复核暂时不可用，请稍后再试");
                        });

        Long gradingRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM attempt_gradings WHERE attempt_id = ?",
                        Long.class,
                        submitted.attemptId());
        assertThat(gradingRows).isEqualTo(1L);
        Long usageRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM llm_usage WHERE scene = 'review' AND ok = b'0'",
                        Long.class);
        assertThat(usageRows).isEqualTo(2L);
    }

    /** [AC-4] 输出非法（两次都不可解析）：同样降级不落行；token 消耗真实发生，用量记 ok=true。 */
    @Test
    @DisplayName("[AC-4] 输出非法重试仍败降级不落行")
    void ac4_invalidOutputDegradesWithoutNewRow() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().reply("这不是 JSON");
        stubChatClient().reply("{\"score\": \"88\"}");

        assertThatThrownBy(() -> practiceService.reviewAttempt(USER_ID, submitted.attemptId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode()).isEqualTo(ApiCode.INTERNAL_ERROR);
                            assertThat(exception.getMessage()).isEqualTo("模型复核暂时不可用，请稍后再试");
                        });

        Long gradingRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM attempt_gradings WHERE attempt_id = ?",
                        Long.class,
                        submitted.attemptId());
        assertThat(gradingRows).isEqualTo(1L);
        Long usageRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM llm_usage WHERE scene = 'review' AND ok = b'1'",
                        Long.class);
        assertThat(usageRows).isEqualTo(2L);
    }

    // ---------- [AC-3] 记账补成本 ----------

    /**
     * [AC-3] 已定价模型的复核用量行落估算成本列：llm.budget 配置 stub-review-model 单价（输入 1 元/百万、 输出 2 元/百万），桩用量
     * prompt=120/completion=30 → 120×1 + 30×2 = 180 微元。
     */
    @Test
    @DisplayName("[AC-3] 已定价模型复核落估算成本 180 微元")
    void ac3_reviewUsageRowRecordsEstimatedCost() {
        AttemptView submitted = submitWrongAttempt();
        jdbcTemplate.update(
                "INSERT INTO app_settings (`key`, value, description) VALUES ('llm.budget', ?, 'it')"
                        + " ON DUPLICATE KEY UPDATE value = VALUES(value)",
                "{\"schemaVersion\":1,\"monthlyBudgetCny\":null,"
                        + "\"pricing\":{\"stub-review-model\":"
                        + "{\"inputPerMillionCny\":1,\"outputPerMillionCny\":2}}}");
        stubChatClient().reply("{\"score\": 88, \"reason\": \"" + REVIEW_REASON + "\"}");

        practiceService.reviewAttempt(USER_ID, submitted.attemptId());

        BigDecimal costMicroCny =
                jdbcTemplate.queryForObject(
                        "SELECT estimated_cost_micro_cny FROM llm_usage WHERE scene = 'review'",
                        BigDecimal.class);
        assertThat(costMicroCny).isEqualByComparingTo("180");
    }

    /** [AC-3] 未定价模型的复核用量行成本列落 NULL（fail-open：成本 NULL=不计入预算，不拦调用）。 */
    @Test
    @DisplayName("[AC-3] 未定价模型复核成本列落 NULL")
    void ac3_reviewUsageRowNullCostWithoutPricing() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().reply("{\"score\": 88, \"reason\": \"" + REVIEW_REASON + "\"}");

        practiceService.reviewAttempt(USER_ID, submitted.attemptId());

        BigDecimal costMicroCny =
                jdbcTemplate.queryForObject(
                        "SELECT estimated_cost_micro_cny FROM llm_usage WHERE scene = 'review'",
                        BigDecimal.class);
        assertThat(costMicroCny).isNull();
    }

    /**
     * [AC-5] 预算强制门（模型复核）：本月已用 ≥100% 时复核直接走既有降级路径（50000 + 文案带预算原因），
     * 不调模型（零调用）也不落复核行/用量行——幂等回读语义不受影响，原判分保留。
     */
    @Test
    @DisplayName("[AC-5] 预算用尽复核降级 50000 零模型调用零落行")
    void ac5_budgetExhaustedDegradesWithoutModelCallOrRow() {
        AttemptView submitted = submitWrongAttempt();
        // 预算 1 元，本月已用 10 元 → EXCEEDED。
        jdbcTemplate.update(
                "INSERT INTO app_settings (`key`, value, description) VALUES ('llm.budget', ?, 'it')"
                        + " ON DUPLICATE KEY UPDATE value = VALUES(value)",
                "{\"schemaVersion\":1,\"monthlyBudgetCny\":1,\"pricing\":null}");
        jdbcTemplate.update(
                "INSERT INTO llm_usage (scene, ok, estimated_cost_micro_cny, created_at)"
                        + " VALUES ('budget-it', 1, 10000000, UTC_TIMESTAMP(6))");

        assertThatThrownBy(() -> practiceService.reviewAttempt(USER_ID, submitted.attemptId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode()).isEqualTo(ApiCode.INTERNAL_ERROR);
                            assertThat(exception.getMessage())
                                    .isEqualTo(PracticeServiceImpl.MODEL_REVIEW_BUDGET_MESSAGE);
                        });

        // 零模型调用：预算门在调用前拦下。
        assertThat(stubChatClient().calls.get()).isZero();
        // 零落行：无新 MODEL 判分行（只有首判 LOCAL 行）、无 review 用量行。
        Long gradingRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM attempt_gradings WHERE attempt_id = ?",
                        Long.class,
                        submitted.attemptId());
        assertThat(gradingRows).isEqualTo(1L);
        Long reviewUsageRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM llm_usage WHERE scene = 'review'", Long.class);
        assertThat(reviewUsageRows).isZero();
    }

    /** [AC-4] 重试后成功：第一次异常、第二次合法输出 → 复核照常落行（重试路径可用）。 */
    @Test
    @DisplayName("[AC-4] 重试后成功正常落行")
    void ac4_retryRecoversAndPersists() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().failure();
        stubChatClient().reply("{\"score\": 101, \"reason\": \"" + REVIEW_REASON + "\"}");

        ModelReviewView review = practiceService.reviewAttempt(USER_ID, submitted.attemptId());

        // 模型轻微越界纠偏而不是整次降级：101 钳制为 100。
        assertThat(review.score()).isEqualTo(100);
        assertThat(stubChatClient().calls.get()).isEqualTo(2);
        Long gradingRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM attempt_gradings WHERE attempt_id = ?",
                        Long.class,
                        submitted.attemptId());
        assertThat(gradingRows).isEqualTo(2L);
    }

    // ---------- [AC-5] llm_usage 三终态 ----------

    /** [AC-5] llm_usage scene=review 三终态：成功/空回复/异常各落一条，失败不阻塞主链路。 */
    @Test
    @DisplayName("[AC-5] llm_usage 三终态双写")
    void ac5_llmUsageRecordsThreeTerminalStates() {
        // 成功：ok=true，token 与耗时落列。
        AttemptView successAttempt = submitWrongAttempt();
        stubChatClient().reply("{\"score\": 88, \"reason\": \"" + REVIEW_REASON + "\"}");
        practiceService.reviewAttempt(USER_ID, successAttempt.attemptId());

        // 空回复：两次空回复后降级，ok=false。
        AttemptView emptyAttempt = submitWrongAttempt();
        stubChatClient().emptyReply();
        stubChatClient().emptyReply();
        assertThatThrownBy(() -> practiceService.reviewAttempt(USER_ID, emptyAttempt.attemptId()))
                .isInstanceOf(BusinessException.class);

        // 异常：两次异常后降级，ok=false。
        AttemptView failureAttempt = submitWrongAttempt();
        stubChatClient().failure();
        stubChatClient().failure();
        assertThatThrownBy(() -> practiceService.reviewAttempt(USER_ID, failureAttempt.attemptId()))
                .isInstanceOf(BusinessException.class);

        List<Map<String, Object>> usages =
                jdbcTemplate.queryForList(
                        "SELECT user_id, sentence_id, scene, protocol, model, prompt_tokens,"
                                + " completion_tokens, duration_ms, ok FROM llm_usage"
                                + " WHERE scene = 'review' ORDER BY id");
        assertThat(usages).hasSize(5);
        Map<String, Object> success = usages.get(0);
        assertThat(success.get("user_id")).isEqualTo(USER_ID);
        assertThat(success.get("sentence_id")).isEqualTo(seeded.sentenceId());
        assertThat(success.get("protocol")).isEqualTo("openai-chat");
        assertThat(success.get("model")).isEqualTo("stub-review-model");
        assertThat(success.get("prompt_tokens")).isEqualTo(120);
        assertThat(success.get("completion_tokens")).isEqualTo(30);
        assertThat(success.get("duration_ms")).isNotNull();
        assertThat((Boolean) success.get("ok")).isTrue();
        assertThat((Boolean) usages.get(1).get("ok")).isFalse();
        assertThat(usages.get(1).get("user_id")).isEqualTo(USER_ID);
        assertThat(usages.get(3).get("user_id")).isEqualTo(USER_ID);
        assertThat((Boolean) usages.get(4).get("ok")).isFalse();
    }

    // ---------- [AC-6] 限流 429 ----------

    /** [AC-6] 第 6 次复核请求 429 + Retry-After，被拒请求不触达业务（不调模型、不落数据）。 */
    @Test
    @DisplayName("[AC-6] 限流 5 次后 429 + Retry-After")
    void ac6_rateLimitRejectsWithRetryAfter() throws Exception {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().reply("{\"score\": 88, \"reason\": \"" + REVIEW_REASON + "\"}");

        HttpClient client = newClient();
        // 提交人本人登录（限流按账号计数、复核校验本人归属）：按已知口令重置并标记已改密，避开首次登录强制改密拦截。
        jdbcTemplate.update(
                "UPDATE users SET password_hash = ?, password_changed = b'1' WHERE id = ?",
                passwordEncoder.encode(TEST_USER_PASSWORD),
                USER_ID);
        String csrf = csrfToken(client);
        HttpResponse<String> login =
                send(
                        client,
                        "POST",
                        "/api/auth/login",
                        Map.of("username", "it-reviewer", "password", TEST_USER_PASSWORD),
                        Map.of("X-XSRF-TOKEN", csrf));
        assertThat(login.statusCode()).as("login body=%s", login.body()).isEqualTo(200);
        Matcher matcher = ACCESS_TOKEN.matcher(login.body());
        assertThat(matcher.find()).isTrue();
        String accessToken = matcher.group(1);

        // 登录端点自身也有限流计数：脚本化放行额度在登录成功后再设置，保证「前 5 次放行」只数复核请求。
        scriptedRateLimit().rejectAfterNPermits(5, 42);
        int llmCallsBefore = stubChatClient().calls.get();
        Long gradingsBefore =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attempt_gradings", Long.class);
        // CSRF 令牌逐请求轮换：每次 POST 前都要取新令牌（E2E 同款）。
        for (int call = 1; call <= 5; call++) {
            csrf = csrfToken(client);
            HttpResponse<String> permitted =
                    send(
                            client,
                            "POST",
                            "/api/practice/attempts/" + submitted.attemptId() + "/review",
                            null,
                            Map.of("X-XSRF-TOKEN", csrf, "Authorization", "Bearer " + accessToken));
            assertThat(permitted.statusCode())
                    .as("第 %d 次调用 body=%s", call, permitted.body())
                    .isEqualTo(200);
        }
        csrf = csrfToken(client);
        HttpResponse<String> rejected =
                send(
                        client,
                        "POST",
                        "/api/practice/attempts/" + submitted.attemptId() + "/review",
                        null,
                        Map.of("X-XSRF-TOKEN", csrf, "Authorization", "Bearer " + accessToken));
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.body()).contains("\"code\":42900");
        String retryAfter = rejected.headers().firstValue("Retry-After").orElse(null);
        assertThat(retryAfter).isNotNull();
        assertThat(Integer.parseInt(retryAfter)).isGreaterThanOrEqualTo(1);

        // 被限流请求不触达业务：模型不再被调；判分行只有首判 + 第一次成功复核，无被拒请求产生的新行。
        assertThat(stubChatClient().calls.get()).isEqualTo(llmCallsBefore + 1);
        Long gradingsAfter =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attempt_gradings", Long.class);
        assertThat(gradingsAfter).isEqualTo(gradingsBefore + 1);
        Long modelRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM attempt_gradings WHERE attempt_id = ?"
                                + " AND grader = 'MODEL'",
                        Long.class,
                        submitted.attemptId());
        assertThat(modelRows).isEqualTo(1L);
    }

    // ---------- [AC-7] 回看/统计取最新 sequence，排程与错题口径不变 ----------

    /** [AC-7] 复核后回看与今日全对率取复核分；复习排程、review_logs 与错题池维持首判口径。 */
    @Test
    @DisplayName("[AC-7] 回看统计取最新分且排程错题不变")
    void ac7_lookbackUsesLatestSequenceWhileScheduleUntouched() {
        AttemptView submitted = submitWrongAttempt();
        Map<String, Object> cardBefore =
                jdbcTemplate.queryForMap(
                        "SELECT state, reps, lapses, due_at FROM reviews"
                                + " WHERE user_id = ? AND sentence_id = ?",
                        USER_ID,
                        seeded.sentenceId());
        Long logsBefore =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM review_logs", Long.class);
        stubChatClient().reply("{\"score\": 100, \"reason\": \"整体可懂，表达自然\"}");
        practiceService.reviewAttempt(USER_ID, submitted.attemptId());

        // 回看取最新 sequence 的复核分。
        AttemptView lookback = practiceService.getAttempt(USER_ID, submitted.attemptId());
        assertThat(lookback.score()).isEqualTo(100);

        // 今日全对率按最新判分统计：复核 100 分后计入全对（首判 <100 不计入）。
        TodayView today = practiceService.today(USER_ID);
        assertThat(today.doneCount()).isEqualTo(1L);
        assertThat(today.correctRate()).isEqualTo(100);

        // 排程不变：卡 state/reps/lapses/due_at 与 review_logs 行数维持首判口径。
        Map<String, Object> cardAfter =
                jdbcTemplate.queryForMap(
                        "SELECT state, reps, lapses, due_at FROM reviews"
                                + " WHERE user_id = ? AND sentence_id = ?",
                        USER_ID,
                        seeded.sentenceId());
        assertThat(cardAfter).isEqualTo(cardBefore);
        Long logsAfter =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM review_logs", Long.class);
        assertThat(logsAfter).isEqualTo(logsBefore);

        // 错题池按首判口径：复核 100 分后题目仍在错题列表，lastScore 仍是首判分。
        var wrongQuestions =
                practiceService.wrongQuestions(USER_ID, 1, 20, false, null, null).items();
        assertThat(wrongQuestions)
                .anySatisfy(
                        item -> {
                            assertThat(item.sentenceId()).isEqualTo(seeded.sentenceId());
                            assertThat(item.lastScore()).isEqualTo(submitted.score());
                        });
    }

    // ---------- 桩：LLM 注册表与脚本限流器 ----------

    /** 可编程 LLM 桩：按脚本依次回复或抛错，计数调用次数；脚本耗尽后默认回合法输出。 */
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
            script.add(new Reply(json, 120, 30));
        }

        /** 追加一条空正文回复（供应商吃到 max_tokens 的模拟）。 */
        void emptyReply() {
            script.add(new Reply("  ", 120, 0));
        }

        /** 追加一次调用异常（网络/超时/供应商错误的模拟）。 */
        void failure() {
            script.add(new RuntimeException("stub-llm-down"));
        }

        @Override
        public Reply ask(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            Object next =
                    script.isEmpty()
                            ? new Reply("{\"score\": 88, \"reason\": \"默认桩回复\"}", 120, 30)
                            : script.poll();
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

    /** 脚本化限流器：前 N 次放行、之后拒绝并给出固定 Retry-After（锁 HTTP 行为，窗口算法另有单测）。 */
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
         * 放行前 N 次后拒绝，Retry-After 用给定秒数。
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
    static class ReviewStubConfig {

        /**
         * 桩注册表（复核与答疑共用注入点）。
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

    // ---------- 测试支撑（PracticeFlowIntegrationTest 同款） ----------

    private StubChatClient stubChatClient() {
        return ((StubLlmClientRegistry) llmClients).client;
    }

    private ScriptedRateLimitService scriptedRateLimit() {
        return (ScriptedRateLimitService) rateLimitService;
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
                                1,
                                "CLOZE",
                                List.of(
                                        new AnswerPayload.Slot(
                                                0,
                                                "He is paying less and less attention to this"
                                                        + " matter.")),
                                null),
                        0,
                        1000,
                        null));
    }

    /** 提交一次 REVEAL（无判分）作答并返回视图。 */
    private AttemptView submitRevealAttempt() {
        return practiceService.submitAttempt(
                USER_ID,
                new AttemptCommand(
                        UUID.randomUUID().toString(),
                        seeded.sentenceId(),
                        String.valueOf(seeded.sentenceVersionId()),
                        AttemptCommand.Mode.CLOZE,
                        AttemptCommand.Kind.REVEAL,
                        null,
                        new AnswerPayload(1, "CLOZE", null, null),
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

    /** 播种公开发布题库与单题（chunk0=整句首字，可接受答案=参考译文，挖空 [0]）。 */
    private void seedSentence() {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9201, NULL, '复核集成题库', NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL,"
                        + " NULL, ?, ?, NULL, NULL)",
                T0,
                T0,
                T0);
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, 9201, 'JSON', 'it.json', 'imports/it.json', ?, 1024,"
                        + " 'COMMITTING', ?, 'admin', ?)",
                "batch-it-0240-0000-0000-000000000001",
                "a".repeat(64),
                USER_ID,
                T0);
        List<SentenceContent> rows =
                List.of(
                        new SentenceContent(
                                "s_r0001",
                                "他越来越重视这件事情。",
                                REF,
                                "这是复核集成测试的解析说明，长度满足最小要求。",
                                List.of(new SentenceChunk(0, "他", List.of(REF), "代词", null)),
                                List.of(0),
                                List.of("代词"),
                                null,
                                null,
                                null));
        seeded =
                contentImportService
                        .importSentences(
                                9201L,
                                "batch-it-0240-0000-0000-000000000001",
                                rows,
                                USER_ID,
                                T0.toInstant())
                        .get(0);
    }

    // ---------- HTTP 辅助（E2E 同款） ----------

    private HttpClient newClient() {
        // test profile 下 XSRF-TOKEN 带 Secure 下发，用忽略 Secure 的 CookieHandler 模拟浏览器握手。
        return HttpClient.newBuilder()
                .cookieHandler(new InsecureCookieHandler())
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private String csrfToken(HttpClient client) throws Exception {
        HttpResponse<String> response = send(client, "GET", "/api/auth/csrf", null, Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        Matcher matcher = CSRF_TOKEN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private HttpResponse<String> send(
            HttpClient client, String method, String path, Object body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json");
        headers.forEach(builder::header);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(writeJson(body)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String writeJson(Object body) {
        return objectMapper.writeValueAsString(body);
    }
}
