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
import io.github.codejinxz.yilian.practice.service.vo.AppealView;
import io.github.codejinxz.yilian.practice.service.vo.AttemptView;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 申诉人工终审并发原子门集成测试（真实 MySQL）[AC-1]。
 *
 * <p>终审 UPDATE 的 WHERE 携带 status='PENDING'（条件 UPDATE 语义），应用层预检与落库之间不再有
 * 「预检都通过、后到者整体覆盖先到者」的双写窗口。两位管理员并发对同一条 PENDING 申诉裁决不同结论时， 恰好一人 200、另一人 40900；先到者的
 * status/终审理由不被覆盖；两次操作（含冲突那次）都在 admin_audit_log 留痕（审计不因冲突丢失）。
 *
 * <p>基建与 {@code PracticeAppealAdjudicationIntegrationTest} 同款：桩注册表 + 脚本化限流器（CI 无 Redis）， 真实 HTTP
 * 双线程经登录会话起跑；审计断言走 admin_audit_log（操作日志切面同步落库）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class AppealFinalDecisionConcurrencyIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 6, 12, 0, 0));

    private static final long USER_ID = 27L;

    private static final long ADMIN_ID = 3L;

    private static final String TEST_ADMIN_PASSWORD = "test-finalizer-password";

    private static final String REF = "He is paying more and more attention to this matter.";

    private static final String PROPOSED = "She is paying more and more attention to this matter.";

    private static final String WRONG = "He is paying less and less attention to this matter.";

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
        seedUser(USER_ID, "it-ac1-appealer");
        seedAdmin();
        seedSentence();
        stubChatClient().reset();
        scriptedRateLimit().permitAll();
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
    }

    /** [AC-1] 双管理员并发终审：恰一 200 一 40900，先到者结论不被覆盖，审计两行都在。 */
    @Test
    @DisplayName("[AC-1] 并发终审恰一成功且先到者结论不被覆盖")
    void ac1_concurrentDecideAllowsExactlyOneWinner() throws Exception {
        AttemptView submitted = submitWrongAttempt();
        AppealView appeal =
                practiceService.submitAppeal(
                        USER_ID, submitted.attemptId(), "slot:0", PROPOSED, "主语可换她");
        assertThat(appeal.status()).isEqualTo("PENDING");

        // 两个管理员会话各自独立（独立 Cookie/CSRF/登录态），登录就绪后在两方屏障上同时起跑。
        CountDownLatch startGate = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        HttpResponse<String> winner;
        HttpResponse<String> loser;
        try {
            Future<HttpResponse<String>> f1 =
                    pool.submit(
                            () ->
                                    decideAsAdmin(
                                            appeal.appealId(),
                                            "ACCEPTED",
                                            "并发甲：同意收进可接受答案",
                                            startGate));
            Future<HttpResponse<String>> f2 =
                    pool.submit(
                            () ->
                                    decideAsAdmin(
                                            appeal.appealId(),
                                            "REJECTED",
                                            "并发乙：与参考语义相反",
                                            startGate));
            HttpResponse<String> first = f1.get(30, TimeUnit.SECONDS);
            HttpResponse<String> second = f2.get(30, TimeUnit.SECONDS);
            winner = first.statusCode() == 200 ? first : second;
            loser = first.statusCode() == 200 ? second : first;
        } finally {
            pool.shutdownNow();
        }

        // 恰一 200 一 40900：条件 UPDATE 的 WHERE status='PENDING' 保证只有一次落库成功；
        // 输家无论走预检还是行数=0 分支都收敛为 40900 信封（文案差异不改变契约语义）。
        assertThat(winner.statusCode()).as("winner body=%s", winner.body()).isEqualTo(200);
        assertThat(winner.body()).contains("\"code\":0");
        assertThat(loser.statusCode()).as("loser body=%s", loser.body()).isEqualTo(409);
        assertThat(loser.body()).contains("\"code\":40900");

        // 胜者结论落库且不被覆盖：status/终审理由/裁决者/时间与 200 方请求一致。
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT status, decision_reason, decided_by, decided_at FROM appeals"
                                + " WHERE id = ?",
                        appeal.appealId());
        String winnerStatus = winner.body().contains("\"ACCEPTED\"") ? "ACCEPTED" : "REJECTED";
        assertThat(row.get("status")).isEqualTo(winnerStatus);
        assertThat(row.get("decided_by")).isEqualTo("ADMIN");
        assertThat(row.get("decided_at")).isNotNull();
        if ("ACCEPTED".equals(winnerStatus)) {
            assertThat(row.get("decision_reason")).isEqualTo("并发甲：同意收进可接受答案");
        } else {
            assertThat(row.get("decision_reason")).isEqualTo("并发乙：与参考语义相反");
        }

        // 审计两行都在（成功与冲突各一条，操作日志不因并发失败丢失）。
        List<Map<String, Object>> auditRows =
                jdbcTemplate.queryForList(
                        "SELECT outcome FROM admin_audit_log WHERE action = 'APPEAL_DECIDE'"
                                + " AND target_id = ?",
                        String.valueOf(appeal.appealId()));
        assertThat(auditRows).hasSize(2);
        assertThat(auditRows)
                .extracting(r -> r.get("outcome"))
                .containsExactlyInAnyOrder("SUCCESS", "FAILURE");
    }

    /** [AC-1] 串行重复终审 40900 信封完整：先到者结论保持，后到者不改写终审理由。 */
    @Test
    @DisplayName("[AC-1] 串行重复终审 40900 且先到者结论保持")
    void ac1_repeatDecideRejectedWith40900Envelope() throws Exception {
        AttemptView submitted = submitWrongAttempt();
        AppealView appeal =
                practiceService.submitAppeal(
                        USER_ID, submitted.attemptId(), "slot:0", PROPOSED, "主语可换她");

        HttpResponse<String> first = decideAsAdmin(appeal.appealId(), "ACCEPTED", "第一次终审", null);
        assertThat(first.statusCode()).isEqualTo(200);
        HttpResponse<String> second = decideAsAdmin(appeal.appealId(), "REJECTED", "第二次终审", null);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("\"code\":40900");
        // 第一次的结论仍在：后到者既没成功也没改写终审理由。
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT status, decision_reason FROM appeals WHERE id = ?",
                        appeal.appealId());
        assertThat(row.get("status")).isEqualTo("ACCEPTED");
        assertThat(row.get("decision_reason")).isEqualTo("第一次终审");
    }

    // ---------- HTTP 会话辅助（PracticeModelReviewIntegrationTest 同款） ----------

    /**
     * 以管理员会话对指定申诉发起终审；startGate 非 null 时先到位计数再等齐（两方屏障：双方都完成登录后 同时起跑，让并发窗口真实发生而不是被登录耗时错开）。
     *
     * @param appealId 申诉 id
     * @param status 终审结论
     * @param reason 终审理由
     * @param startGate 两方起跑屏障（计数=2），可空
     * @return 终审响应
     */
    private HttpResponse<String> decideAsAdmin(
            long appealId, String status, String reason, CountDownLatch startGate)
            throws Exception {
        HttpClient client = newClient();
        String accessToken = loginAdmin(client);
        if (startGate != null) {
            startGate.countDown();
            assertThat(startGate.await(10, TimeUnit.SECONDS)).isTrue();
        }
        // CSRF 令牌逐请求轮换：POST 前必须取新令牌（E2E 同款）。
        String csrf = csrfToken(client);
        return send(
                client,
                "POST",
                "/api/admin/appeals/" + appealId + "/decision",
                Map.of("status", status, "reason", reason),
                Map.of("X-XSRF-TOKEN", csrf, "Authorization", "Bearer " + accessToken));
    }

    /** 管理员登录（按已知口令重置并标记已改密，避开首次登录强制改密拦截）。 */
    private String loginAdmin(HttpClient client) throws Exception {
        String csrf = csrfToken(client);
        HttpResponse<String> login =
                send(
                        client,
                        "POST",
                        "/api/auth/login",
                        Map.of("username", "it-ac1-admin", "password", TEST_ADMIN_PASSWORD),
                        Map.of("X-XSRF-TOKEN", csrf));
        assertThat(login.statusCode()).as("login body=%s", login.body()).isEqualTo(200);
        Matcher matcher = ACCESS_TOKEN.matcher(login.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

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

    // ---------- 桩：LLM 注册表与脚本限流器（初裁与本测试语义无关，仅需可用） ----------

    /** 可编程 LLM 桩：脚本耗尽后默认回合法初裁输出，计数调用次数。 */
    static final class StubChatClient implements LlmChatClient {

        private final Deque<Object> script = new ArrayDeque<>();

        private final AtomicInteger calls = new AtomicInteger();

        /** 复位脚本与计数（逐用例调用）。 */
        void reset() {
            script.clear();
            calls.set(0);
        }

        @Override
        public Reply ask(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            Object next =
                    script.isEmpty()
                            ? new Reply(
                                    "{\"verdict\": \"SUPPORT\", \"reason\": \"并发桩回复\"}", 100, 25)
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
    static class ConcurrencyStubConfig {

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

    private StubChatClient stubChatClient() {
        return ((StubLlmClientRegistry) llmClients).client;
    }

    private ScriptedRateLimitService scriptedRateLimit() {
        return (ScriptedRateLimitService) rateLimitService;
    }

    // ---------- 播种与清库（PracticeAppealAdjudicationIntegrationTest 同款） ----------

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

    /** 幂等播种管理员并按已知口令重置（并发终审的两个会话使用同一管理员账号）。 */
    private void seedAdmin() {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, username, password_hash, role, enabled)"
                        + " VALUES (?, ?, ?, 'seeded', 'ADMIN', TRUE)"
                        + " ON DUPLICATE KEY UPDATE enabled = TRUE, role = 'ADMIN'",
                ADMIN_ID,
                "it-ac1-admin@example.com",
                "it-ac1-admin");
        jdbcTemplate.update(
                "UPDATE users SET password_hash = ?, password_changed = b'1' WHERE id = ?",
                passwordEncoder.encode(TEST_ADMIN_PASSWORD),
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
                        + " VALUES (9203, NULL, '终审并发题库', NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL,"
                        + " NULL, ?, ?, NULL, NULL)",
                T0,
                T0,
                T0);
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, 9203, 'JSON', 'it.json', 'imports/it.json', ?, 1024,"
                        + " 'COMMITTING', ?, 'admin', ?)",
                "batch-it-0290-0000-0000-000000000001",
                "c".repeat(64),
                ADMIN_ID,
                T0);
        List<SentenceContent> rows =
                List.of(
                        new SentenceContent(
                                "s_r0291",
                                "他越来越重视这件事情。",
                                REF,
                                "这是终审并发集成测试的解析说明，长度满足最小要求。",
                                List.of(new SentenceChunk(0, "他", List.of(REF), "代词", null)),
                                List.of(0),
                                List.of("代词"),
                                null,
                                null,
                                null));
        seeded =
                contentImportService
                        .importSentences(
                                9203L,
                                "batch-it-0290-0000-0000-000000000001",
                                rows,
                                ADMIN_ID,
                                T0.toInstant())
                        .get(0);
    }
}
