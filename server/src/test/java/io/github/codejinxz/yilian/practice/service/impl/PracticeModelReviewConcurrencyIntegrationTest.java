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
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.vo.AttemptView;
import io.github.codejinxz.yilian.practice.service.vo.ModelReviewView;
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
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型复核并发串行化集成测试（真实 MySQL）[AC-2]。
 *
 * <p>reviewAttempt 事务内先对 attempts 父行悲观锁（SELECT ... FOR UPDATE），幂等闸、 sequence 计算与 MODEL 行落库整体原子化——同
 * attempt 两线程并发复核时，后到者在行锁上等到先到者 提交后再做幂等检查，直接按幂等回读返回既有复核结果：MODEL 行恰一条、双方都拿到结果（其一为幂等回读）、
 * 模型只调一次（不双份计费），首判行 append-only 不受影响。
 *
 * <p>基建与 {@code PracticeModelReviewIntegrationTest} 同款：桩注册表 + 脚本化限流器（CI 无 Redis），
 * 服务层双线程直接起跑（事务代理对任意线程生效）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class PracticeModelReviewConcurrencyIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 6, 12, 0, 0));

    private static final long USER_ID = 28L;

    private static final String REF = "He is paying more and more attention to this matter.";

    private static final String REVIEW_REASON = "并发复核：整体可懂，介词搭配需要调整";

    @Autowired private PracticeService practiceService;

    @Autowired private ContentImportService contentImportService;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private LlmClientRegistry llmClients;

    @Autowired private RateLimitService rateLimitService;

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
        seedUser(USER_ID, "it-ac2-reviewer");
        seedSentence();
        stubChatClient().reset();
        scriptedRateLimit().permitAll();
    }

    /** [AC-2] 同 attempt 两线程并发复核：MODEL 行恰 1、双方拿到同一结果、模型只调一次、首判行不动。 */
    @Test
    @DisplayName("[AC-2] 并发复核 MODEL 行恰一且模型只调一次")
    void ac2_concurrentReviewPersistsExactlyOneModelRow() throws Exception {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().reply("{\"score\": 88, \"reason\": \"" + REVIEW_REASON + "\"}");

        // 两方屏障：两线程都到达后再同时起跑，让幂等闸与落库的并发窗口真实发生。
        CountDownLatch startGate = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ModelReviewView first;
        ModelReviewView second;
        try {
            Future<ModelReviewView> f1 =
                    pool.submit(
                            () -> {
                                startGate.countDown();
                                startGate.await(10, TimeUnit.SECONDS);
                                return practiceService.reviewAttempt(
                                        USER_ID, submitted.attemptId());
                            });
            Future<ModelReviewView> f2 =
                    pool.submit(
                            () -> {
                                startGate.countDown();
                                startGate.await(10, TimeUnit.SECONDS);
                                return practiceService.reviewAttempt(
                                        USER_ID, submitted.attemptId());
                            });
            first = f1.get(30, TimeUnit.SECONDS);
            second = f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 双方 200 语义（服务层不抛）：得分/理由/序号一致，其一为插入、其一为幂等回读。
        assertThat(first.score()).isEqualTo(88);
        assertThat(second.score()).isEqualTo(88);
        assertThat(second.reason()).isEqualTo(first.reason());
        assertThat(second.sequence()).isEqualTo(first.sequence());
        assertThat(second.modelSnapshot()).isEqualTo(first.modelSnapshot());

        // MODEL 行恰一条（竞态修复前两条：后到者以新 sequence 绕开 uk_grading_attempt_sequence）。
        Long modelRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM attempt_gradings WHERE attempt_id = ?"
                                + " AND grader = 'MODEL'",
                        Long.class,
                        submitted.attemptId());
        assertThat(modelRows).isEqualTo(1L);
        // 模型只调一次：后到者不再重复消耗 token（修复前闸与落库之间无原子性会双份调用）。
        assertThat(stubChatClient().calls.get()).isEqualTo(1);
        // 判分行只有首判 + 复核两行，sequence 依次为 1/2；首判 LOCAL 行 append-only 不受影响。
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT sequence, grader, score FROM attempt_gradings"
                                + " WHERE attempt_id = ? ORDER BY sequence",
                        submitted.attemptId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("grader")).isEqualTo("LOCAL");
        assertThat(rows.get(0).get("score")).isEqualTo(submitted.score());
        assertThat(rows.get(1).get("grader")).isEqualTo("MODEL");
        assertThat(rows.get(1).get("score")).isEqualTo(88);
    }

    /** [AC-2] 串行重复复核幂等回读回归：锁与事务化不改变既有幂等语义（不追加行、不再调模型）。 */
    @Test
    @DisplayName("[AC-2] 串行重复复核幂等回读语义不变")
    void ac2_serialRepeatReviewStillIdempotentRead() {
        AttemptView submitted = submitWrongAttempt();
        stubChatClient().reply("{\"score\": 76, \"reason\": \"" + REVIEW_REASON + "\"}");

        ModelReviewView first = practiceService.reviewAttempt(USER_ID, submitted.attemptId());
        ModelReviewView second = practiceService.reviewAttempt(USER_ID, submitted.attemptId());

        assertThat(second.score()).isEqualTo(first.score());
        assertThat(second.sequence()).isEqualTo(first.sequence());
        assertThat(stubChatClient().calls.get()).isEqualTo(1);
        Long gradingRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM attempt_gradings WHERE attempt_id = ?",
                        Long.class,
                        submitted.attemptId());
        assertThat(gradingRows).isEqualTo(2L);
    }

    // ---------- 桩：LLM 注册表与脚本限流器（PracticeModelReviewIntegrationTest 同款） ----------

    /** 可编程 LLM 桩：脚本耗尽后默认回合法输出，计数调用次数。 */
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

    /** 脚本化限流器：前 N 次放行、之后拒绝（本套件恒全量放行，仅替换 Redis 依赖）。 */
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
    static class ReviewConcurrencyStubConfig {

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

    private StubChatClient stubChatClient() {
        return ((StubLlmClientRegistry) llmClients).client;
    }

    private ScriptedRateLimitService scriptedRateLimit() {
        return (ScriptedRateLimitService) rateLimitService;
    }

    // ---------- 播种与清库（PracticeModelReviewIntegrationTest 同款） ----------

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
                        + " VALUES (9204, NULL, '复核并发题库', NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL,"
                        + " NULL, ?, ?, NULL, NULL)",
                T0,
                T0,
                T0);
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, 9204, 'JSON', 'it.json', 'imports/it.json', ?, 1024,"
                        + " 'COMMITTING', ?, 'admin', ?)",
                "batch-it-0291-0000-0000-000000000001",
                "d".repeat(64),
                USER_ID,
                T0);
        List<SentenceContent> rows =
                List.of(
                        new SentenceContent(
                                "s_r0292",
                                "他越来越重视这件事情。",
                                REF,
                                "这是复核并发集成测试的解析说明，长度满足最小要求。",
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
                                "batch-it-0291-0000-0000-000000000001",
                                rows,
                                USER_ID,
                                T0.toInstant())
                        .get(0);
    }
}
