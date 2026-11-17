package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.common.ratelimit.RateLimitDecision;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitService;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.ImportedSentence;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 申诉初裁窗口竞态集成测试（真实 MySQL）[AC-3]（镜像终审并发原子门套件的样式）。
 *
 * <p>初裁落列 UPDATE 的 WHERE 携带 {@code status='PENDING'}——申诉落库后初裁同步外呼期间 （最长 ~31.5s）该行 PENDING
 * 可见于管理端，管理员可在此窗口合法终审；无守卫时后到的初裁 UPDATE 会覆盖终审理由与 decided_by 且不可自愈。加守卫后行数=0，初裁结果整体丢弃，
 * 用户回执按存储真相（终审态）投影。
 *
 * <p>基建与 {@code AppealFinalDecisionConcurrencyIntegrationTest} 同款：桩注册表 + 脚本化限流器 （CI 无 Redis）；终审走
 * {@link AdminAppealService}（HTTP 层鉴权与审计由 029 AC-1 并发套件与切片测试覆盖，本套件聚焦数据行守卫与回执投影）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class AppealAdjudicationWindowRaceIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 18, 12, 0, 0));

    private static final long USER_ID = 31L;

    private static final long ADMIN_ID = 4L;

    private static final String REF = "He is paying more and more attention to this matter.";

    private static final String PROPOSED = "She is paying more and more attention to this matter.";

    private static final String WRONG = "He is paying less and less attention to this matter.";

    /** 合法初裁输出桩回复（窗口释放后返回）。 */
    private static final String SUPPORT_JSON =
            "{\"verdict\": \"SUPPORT\", \"reason\": \"主语可换她，语义等价\"}";

    @Autowired private PracticeService practiceService;

    @Autowired private AdminAppealService adminAppealService;

    @Autowired private ContentImportService contentImportService;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private LlmClientRegistry llmClients;

    @Autowired private RateLimitService rateLimitService;

    @Autowired private PasswordEncoder passwordEncoder;

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
        seedUser(USER_ID, "it-034-racer");
        seedAdmin();
        seedSentence();
        windowStubClient().reset();
        scriptedRateLimit().permitAll();
    }

    /**
     * [AC-3] 窗口竞态：初裁 LLM 外呼期间管理员终审——初裁落列行数=0，终审结论与 decided_by 不被覆盖， 行最终一致（status=ACCEPTED、decision
     * JSON 保持无 AI 意见），用户回执按终审态投影。
     */
    @Test
    @DisplayName("[AC-3] 初裁窗口内终审：初裁丢弃、终审不被覆盖、回执按终审态投影")
    void ac3_adjudicationWithinFinalizedWindowDiscardsResultAndProjectsStoredTruth()
            throws Exception {
        WindowStubChatClient client = windowStubClient();
        client.blockInWindow();

        AttemptView submitted = submitWrongAttempt();
        AtomicReference<AppealView> receipt = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 申诉线程：落库后同步进初裁外呼，在桩窗口内等待。
            Future<?> adjudicating =
                    pool.submit(
                            () -> {
                                try {
                                    receipt.set(
                                            practiceService.submitAppeal(
                                                    USER_ID,
                                                    submitted.attemptId(),
                                                    "slot:0",
                                                    PROPOSED,
                                                    "主语可换她"));
                                } catch (Throwable error) {
                                    failure.set(error);
                                }
                            });
            // 初裁已进入外呼窗口（行 PENDING 且 UPDATE 尚未发生）：此刻管理员终审是合法动作。
            assertThat(client.awaitEntered(10, TimeUnit.SECONDS)).isTrue();
            adminAppealService.decide(
                    findPendingAppealId(submitted.attemptId()),
                    "ACCEPTED",
                    "窗口内终审：同意收进可接受答案",
                    ADMIN_ID);
            // 放行初裁：UPDATE 的 WHERE status='PENDING' 失配，行数=0 → 初裁丢弃。
            client.releaseWindow();
            adjudicating.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(failure.get()).as("申诉线程异常=%s", failure.get()).isNull();

        // 回执按存储真相（终审态）投影：status=ACCEPTED、裁决者=ADMIN、终审理由、终审时间；
        // verdict 为 null——终审不改写 decision JSON，无 AI 意见不伪造。
        assertThat(receipt.get()).isNotNull();
        assertThat(receipt.get().status()).isEqualTo("ACCEPTED");
        assertThat(receipt.get().decidedBy()).isEqualTo("ADMIN");
        assertThat(receipt.get().decisionReason()).isEqualTo("窗口内终审：同意收进可接受答案");
        assertThat(receipt.get().decidedAt()).isNotNull();
        assertThat(receipt.get().verdict()).isNull();

        // 行最终一致：终审结论与 decided_by 不被初裁覆盖，decision JSON 保持无初裁值。
        var row =
                jdbcTemplate.queryForMap(
                        "SELECT status, decision_reason, decided_by, decided_at, decision,"
                                + " model_snapshot FROM appeals WHERE id = ?",
                        receipt.get().appealId());
        assertThat(row.get("status")).isEqualTo("ACCEPTED");
        assertThat(row.get("decision_reason")).isEqualTo("窗口内终审：同意收进可接受答案");
        assertThat(row.get("decided_by")).isEqualTo("ADMIN");
        assertThat(row.get("decided_at")).isNotNull();
        assertThat(row.get("decision")).isNull();
        assertThat(row.get("model_snapshot")).isNull();

        // 留痕：模型调用真实发生（ok=true 记账），但初裁结果被守卫丢弃（结构化日志留痕由服务层输出）。
        Integer usageRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM llm_usage WHERE scene = 'appeal' AND ok = TRUE",
                        Integer.class);
        assertThat(usageRows).isEqualTo(1);
    }

    /**
     * [AC-3] 占位路径同门：初裁失败落 UNAVAILABLE 占位时行已被并发终审——占位不落列，回执与存储行 都保持终审真相（占位文案不覆盖终审理由）。
     *
     * <p>窗口编排：桩先延迟后失败，给主线程留出「轮询到 PENDING 行→终审→初裁重试仍败→占位 UPDATE 撞守卫」的确定性窗口；初裁重试共 2 次
     * （每次延迟后抛异常），终审落在窗口内即可复现行数=0。
     */
    @Test
    @DisplayName("[AC-3] 占位路径撞终审：占位不落列且回执按终审态投影")
    void ac3_placeholderPathRespectsFinalizedWindow() throws Exception {
        AttemptView submitted = submitWrongAttempt();
        WindowStubChatClient client = windowStubClient();
        client.failAfterDelayMs(600);
        AtomicReference<AppealView> receipt = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread racer =
                new Thread(
                        () -> {
                            try {
                                receipt.set(
                                        practiceService.submitAppeal(
                                                USER_ID,
                                                submitted.attemptId(),
                                                "slot:0",
                                                PROPOSED,
                                                "主语可换她"));
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        });
        racer.start();
        // 申诉行已落库（初裁还在失败延迟里）：此刻终审，占位 UPDATE 必撞 PENDING 守卫。
        long appealId = awaitPendingAppealId(submitted.attemptId());
        adminAppealService.decide(appealId, "REJECTED", "窗口内终审：占位路径", ADMIN_ID);
        racer.join(30_000);
        assertThat(failure.get()).as("申诉线程异常=%s", failure.get()).isNull();
        assertThat(receipt.get()).as("申诉应已返回").isNotNull();

        // 回执按存储真相投影：终审理由与状态不被占位文案覆盖，verdict 无 AI 意见。
        assertThat(receipt.get().status()).isEqualTo("REJECTED");
        assertThat(receipt.get().decisionReason()).isEqualTo("窗口内终审：占位路径");
        assertThat(receipt.get().decidedBy()).isEqualTo("ADMIN");
        assertThat(receipt.get().verdict()).isNull();

        // 行真相：占位 UNAVAILABLE JSON 未落 decision 列，终审理由未被覆盖。
        var row =
                jdbcTemplate.queryForMap(
                        "SELECT status, decision_reason, decided_by, decision FROM appeals WHERE id = ?",
                        receipt.get().appealId());
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("decision_reason")).isEqualTo("窗口内终审：占位路径");
        assertThat(row.get("decided_by")).isEqualTo("ADMIN");
        assertThat(row.get("decision")).isNull();
    }

    /**
     * 轮询等待该提交下的 PENDING APPEAL 行落库（申诉线程先落库、后进初裁）。
     *
     * @param attemptId 提交 id
     * @return PENDING APPEAL 行 id
     * @throws InterruptedException 轮询等待被中断
     */
    private long awaitPendingAppealId(String attemptId) throws InterruptedException {
        for (int waited = 0; waited < 50; waited++) {
            List<Long> ids =
                    jdbcTemplate.queryForList(
                            "SELECT id FROM appeals WHERE attempt_id = ? AND kind = 'APPEAL'"
                                    + " AND status = 'PENDING'",
                            Long.class,
                            attemptId);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("申诉行 1s 内未落库");
    }

    /**
     * 定位该提交下唯一 APPEAL 行 id：submitAppeal 落库与初裁外呼之间，管理端终审走同一 id（实际由申诉线程先落库， 主线程按 attempt 反查）。
     *
     * @param attemptId 提交 id
     * @return PENDING APPEAL 行 id
     */
    private long findPendingAppealId(String attemptId) {
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM appeals WHERE attempt_id = ? AND kind = 'APPEAL'"
                                + " AND status = 'PENDING'",
                        Long.class,
                        attemptId);
        assertThat(id).as("PENDING APPEAL 行应已由申诉线程落库").isNotNull();
        return id;
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

    /** 幂等播种普通用户。 */
    private void seedUser(long id, String username) {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, username, password_hash, role, enabled)"
                        + " VALUES (?, ?, ?, 'seeded', 'USER', TRUE)"
                        + " ON DUPLICATE KEY UPDATE enabled = TRUE",
                id,
                username + "@example.com",
                username);
    }

    /** 幂等播种管理员（终审走 service 直调，账号仅作审计操作者）。 */
    private void seedAdmin() {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, username, password_hash, role, enabled)"
                        + " VALUES (?, ?, ?, 'seeded', 'ADMIN', TRUE)"
                        + " ON DUPLICATE KEY UPDATE enabled = TRUE, role = 'ADMIN'",
                ADMIN_ID,
                "it-034-race-admin@example.com",
                "it-034-race-admin");
        jdbcTemplate.update(
                "UPDATE users SET password_hash = ?, password_changed = b'1' WHERE id = ?",
                passwordEncoder.encode("test-race-admin-password"),
                ADMIN_ID);
    }

    /** 清空 practice 五表 + llm_usage + 内容三表并重建 append-only 触发器。 */
    private void cleanDatabase() {
        dropTriggers();
        jdbcTemplate.update("DELETE FROM review_logs");
        jdbcTemplate.update("DELETE FROM reviews");
        jdbcTemplate.update("DELETE FROM appeals");
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

    /** 播种公开发布题库与单题（chunk0=整句，挖空 [0]）。 */
    private void seedSentence() {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9204, NULL, '初裁窗口竞态题库', NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL,"
                        + " NULL, ?, ?, NULL, NULL)",
                T0,
                T0,
                T0);
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, 9204, 'JSON', 'it.json', 'imports/it.json', ?, 1024,"
                        + " 'COMMITTING', ?, 'admin', ?)",
                "batch-it-0340-0000-0000-000000000001",
                "c".repeat(64),
                ADMIN_ID,
                T0);
        List<SentenceContent> rows =
                List.of(
                        new SentenceContent(
                                "s_r0341",
                                "他越来越重视这件事情。",
                                REF,
                                "这是初裁窗口竞态集成测试的解析说明，长度满足最小要求。",
                                List.of(new SentenceChunk(0, "他", List.of(REF), "代词", null)),
                                List.of(0),
                                List.of("代词"),
                                null,
                                null,
                                null));
        seeded =
                contentImportService
                        .importSentences(
                                9204L,
                                "batch-it-0340-0000-0000-000000000001",
                                rows,
                                ADMIN_ID,
                                T0.toInstant())
                        .get(0);
    }

    // ---------- 桩：LLM 注册表与脚本限流器 ----------

    /** 可编程 LLM 桩：支持「阻塞窗口」（模拟初裁外呼耗时，让终审可并发插入）与「确定性失败」 两种模式；复位后默认回合法初裁输出。 */
    static final class WindowStubChatClient implements LlmChatClient {

        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        private final AtomicInteger calls = new AtomicInteger();

        private volatile boolean blocking = false;

        private volatile boolean failing = false;

        /** 失败模式的前置延迟（毫秒）：给主线程留出「轮询到 PENDING 行→终审」的确定性窗口。 */
        private volatile long failDelayMs = 0;

        /** 复位全部模式与计数（逐用例调用）。 */
        void reset() {
            blocking = false;
            failing = false;
            failDelayMs = 0;
            calls.set(0);
        }

        /** 进入阻塞窗口模式：ask() 先报到位再等放行（复现「初裁外呼窗口内可并发终审」）。 */
        void blockInWindow() {
            reset();
            blocking = true;
        }

        /**
         * 进入确定性失败模式：ask() 延迟指定毫秒后抛异常（重试仍败 → 走占位路径；延迟留给主线程完成终审）。
         *
         * @param delayMs 每次调用抛异常前的延迟
         */
        void failAfterDelayMs(long delayMs) {
            reset();
            failing = true;
            failDelayMs = delayMs;
        }

        /**
         * 等待桩进入 ask()（初裁外呼已开始）。
         *
         * @param timeout 超时时长
         * @param unit 时间单位
         * @return 到位返回 true
         * @throws InterruptedException 等待被中断
         */
        boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
            return entered.await(timeout, unit);
        }

        /** 放行阻塞窗口。 */
        void releaseWindow() {
            release.countDown();
        }

        @Override
        public Reply ask(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            if (failing) {
                try {
                    Thread.sleep(failDelayMs);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                throw new IllegalStateException("stub gateway down");
            }
            if (blocking) {
                entered.countDown();
                try {
                    assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return new Reply(SUPPORT_JSON, 100, 25);
        }
    }

    /** 桩注册表：永远可用（stub/stub-race-model），协议视图 openai-chat。 */
    static final class StubLlmClientRegistry implements LlmClientRegistry {

        private final WindowStubChatClient client = new WindowStubChatClient();

        @Override
        public java.util.Optional<ChatHandle> chatHandle() {
            return java.util.Optional.of(new ChatHandle(client, "stub", "stub-race-model"));
        }

        @Override
        public java.util.Optional<EffectiveConfig> effectiveConfig() {
            return java.util.Optional.of(
                    new EffectiveConfig(
                            "stub",
                            "openai-chat",
                            "http://stub.local",
                            "stub-race-model",
                            true,
                            true));
        }

        @Override
        public boolean directoryMode() {
            return false;
        }
    }

    /** 脚本化限流器：恒全量放行（本套件初裁限流与语义无关，仅替换 Redis 依赖）。 */
    static final class ScriptedRateLimitService extends RateLimitService {

        private final AtomicInteger calls = new AtomicInteger();

        private volatile int permitFirst = Integer.MAX_VALUE;

        ScriptedRateLimitService() {
            super(null, new MockEnvironment());
        }

        /** 全量放行并复位计数。 */
        void permitAll() {
            permitFirst = Integer.MAX_VALUE;
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
            return RateLimitDecision.reject(30);
        }
    }

    /** 桩装配：替换真实注册表与 Redis 限流器（CI 无 Redis）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class RaceStubConfig {

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

    private WindowStubChatClient windowStubClient() {
        return ((StubLlmClientRegistry) llmClients).client;
    }

    private ScriptedRateLimitService scriptedRateLimit() {
        return (ScriptedRateLimitService) rateLimitService;
    }
}
