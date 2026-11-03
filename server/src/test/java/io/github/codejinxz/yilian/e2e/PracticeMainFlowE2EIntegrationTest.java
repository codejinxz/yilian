package io.github.codejinxz.yilian.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.ImportedSentence;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.support.InsecureCookieHandler;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 「登录 → 拉题 → 提交 → 批改」主链路端到端集成测试（DoD 硬性项，016 P1·06#04）。
 *
 * <p>此前认证链路（{@code AuthFlowIntegrationTest}，真实 HTTP）与练习链路 （{@code PracticeFlowIntegrationTest}，直调
 * service）互不相连，controller ↔ 鉴权 ↔ 幂等切面 ↔ 真实判分/排程的组合从未在一条链路里验证过。本类在同一进程、同一真实 MySQL（yilian_test） 上用真实
 * HTTP 完成全链路，并断言幂等重放与落库证据。
 *
 * <p>前置条件与其他集成测试一致：可用 MySQL 与 {@code YILIAN_TEST_DB_*} 环境变量 （docs/BOOTSTRAP.md §5）；不满足时显式失败，不 skip。
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
class PracticeMainFlowE2EIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 2, 12, 0, 0));

    private static final String REF = "He is paying more and more attention to this matter.";

    private static final String ADMIN_USERNAME = "admin";

    private static final String TEST_ADMIN_PASSWORD = "test-admin-password";

    private static final Pattern CSRF_TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    @Autowired private Environment environment;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private ContentImportService contentImportService;

    private String baseUrl;

    private ImportedSentence seeded;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 记录随机端口、清理上一用例的会话/账号/内容/练习数据并幂等播种题目。 */
    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
        cleanDatabase();
        jdbcTemplate.update("DELETE FROM spring_session_attributes");
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM users WHERE username <> ?", ADMIN_USERNAME);
        int adminUpdated =
                jdbcTemplate.update(
                        "UPDATE users SET password_hash = ?, role = 'ADMIN', enabled = b'1'"
                                + " WHERE username = ?",
                        passwordEncoder.encode(TEST_ADMIN_PASSWORD),
                        ADMIN_USERNAME);
        assertThat(adminUpdated).as("bootstrap 管理员不存在，无法运行端到端测试").isEqualTo(1);
        seedSentence();
    }

    /** 用例后清库并重建 append-only 触发器，保证重复运行与其他套件互不污染。 */
    @AfterEach
    void cleanAfter() {
        cleanDatabase();
    }

    /** [AC-3] 登录 → 拉题 → 提交 → 批改 → 幂等重放：真实 HTTP 全链路 + 落库证据。 */
    @Test
    @DisplayName("[AC-3] 登录→拉题→提交→批改端到端主链路")
    void ac3_loginNextSubmitGradeEndToEnd() throws Exception {
        // 1. 登录：真实 HTTP 换取 access token 与 refresh Cookie。
        HttpClient client = newClient();
        String csrf = csrfToken(client);
        HttpResponse<String> login =
                send(
                        client,
                        "POST",
                        "/api/auth/login",
                        Map.of("username", ADMIN_USERNAME, "password", TEST_ADMIN_PASSWORD),
                        Map.of("X-XSRF-TOKEN", csrf));
        assertThat(login.statusCode()).as("login body=%s", login.body()).isEqualTo(200);
        assertThat(login.body()).contains("\"code\":0");
        String accessToken = accessToken(login.body());
        csrf = csrfToken(client);

        // 2. 拉题：/api/practice/next?bankId=9001 返回新题与版本 id。
        HttpResponse<String> next =
                get(
                        client,
                        "/api/practice/next?bankId=9001",
                        Map.of("Authorization", bearer(accessToken)));
        assertThat(next.statusCode()).as("next body=%s", next.body()).isEqualTo(200);
        assertThat(next.body()).contains("\"code\":0");
        assertThat(next.body()).contains("\"selection\":\"NEW\"");
        String sentenceId = jsonField(next.body(), "id");
        String sentenceVersionId = jsonField(next.body(), "sentenceVersionId");
        assertThat(sentenceId).isEqualTo(seeded.sentenceId());

        // 3. 提交：CLOZE FIRST 部分错误作答（8/10 命中 → score=80）→ 批改 + 错题排程。
        csrf = csrfToken(client);
        String attemptId = UUID.randomUUID().toString();
        String wrongAnswer = "He is paying less and less attention to this matter.";
        HttpResponse<String> submit =
                send(
                        client,
                        "POST",
                        "/api/practice/attempts",
                        attemptPayload(attemptId, sentenceId, sentenceVersionId, wrongAnswer),
                        Map.of("X-XSRF-TOKEN", csrf, "Authorization", bearer(accessToken)));
        assertThat(submit.statusCode()).as("submit body=%s", submit.body()).isEqualTo(200);
        assertThat(submit.body()).contains("\"code\":0");
        assertThat(submit.body()).contains("\"score\":80");
        assertThat(submit.body()).contains("\"replayed\":false");
        assertThat(submit.body()).contains("\"state\":\"LEARNING\"");
        assertThat(submit.body()).contains("\"rating\":2");
        assertThat(submit.body()).contains("\"retryRequired\":true");

        // 4. 落库证据：attempt / grading / review 卡与日志各一行。
        assertThat(count("attempts", "id", attemptId)).isEqualTo(1);
        assertThat(count("attempt_gradings", "attempt_id", attemptId)).isEqualTo(1);
        assertThat(count("reviews", "sentence_id", sentenceId)).isEqualTo(1);
        assertThat(count("review_logs", "attempt_id", attemptId)).isEqualTo(1);

        // 5. 幂等重放：同 attemptId 重发返回原结果，无新行。
        csrf = csrfToken(client);
        HttpResponse<String> replay =
                send(
                        client,
                        "POST",
                        "/api/practice/attempts",
                        attemptPayload(attemptId, sentenceId, sentenceVersionId, wrongAnswer),
                        Map.of("X-XSRF-TOKEN", csrf, "Authorization", bearer(accessToken)));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("\"replayed\":true");
        assertThat(count("attempts", "id", attemptId)).isEqualTo(1);
        assertThat(count("attempt_gradings", "attempt_id", attemptId)).isEqualTo(1);
    }

    // ---------- HTTP 辅助（AuthFlow 同款） ----------

    private HttpClient newClient() {
        // test profile 下 XSRF-TOKEN 带 Secure 下发（SecurityConfig 仅对 local 关闭），
        // 用忽略 Secure 的测试 CookieHandler 模拟浏览器本地握手（见 InsecureCookieHandler）。
        return HttpClient.newBuilder()
                .cookieHandler(new InsecureCookieHandler())
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private String csrfToken(HttpClient client) throws Exception {
        HttpResponse<String> response = get(client, "/api/auth/csrf", Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        Matcher matcher = CSRF_TOKEN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String accessToken(String body) {
        Matcher matcher = ACCESS_TOKEN.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /**
     * 从 ApiResponse JSON 体提取字符串字段。
     *
     * @param body 响应体
     * @param field 字段名
     * @return 字段值
     */
    private static String jsonField(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("body=%s 缺少字段 %s", body, field).isTrue();
        return matcher.group(1);
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private HttpResponse<String> get(HttpClient client, String path, Map<String, String> headers)
            throws Exception {
        return send(client, "GET", path, null, headers);
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
        } else if (body instanceof String text) {
            // 已序列化的 JSON 文本直接发送（如嵌套结构的 attempt 载荷）。
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(text));
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(writeJson(body)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 组装 CLOZE FIRST 提交体。
     *
     * @param attemptId 幂等键
     * @param sentenceId 题目 id
     * @param sentenceVersionId 版本行 id
     * @param answer 参考译文（正确答案）
     * @return JSON 文本
     */
    private static String attemptPayload(
            String attemptId, String sentenceId, String sentenceVersionId, String answer) {
        return "{\"attemptId\":\""
                + attemptId
                + "\",\"sentenceId\":\""
                + sentenceId
                + "\",\"sentenceVersionId\":\""
                + sentenceVersionId
                + "\",\"mode\":\"CLOZE\",\"kind\":\"FIRST\",\"hintsUsed\":0,"
                + "\"answer\":{\"schemaVersion\":1,\"mode\":\"CLOZE\",\"slots\":[{\"chunkIndex\":0,"
                + "\"value\":\""
                + answer
                + "\"}]}}";
    }

    private static String writeJson(Object body) {
        if (body instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
                    .collect(Collectors.joining(",", "{", "}"));
        }
        throw new IllegalArgumentException("unsupported test payload");
    }

    // ---------- 数据准备与清理 ----------

    /**
     * 统计满足条件的行数。
     *
     * @param table 表名
     * @param column 列名
     * @param value 期望值
     * @return 行数
     */
    private Long count(String table, String column, String value) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Long.class, value);
    }

    /** 清空练习五表、内容三表、批次与会话；先摘 append-only 触发器再删行。 */
    private void cleanDatabase() {
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempts_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempts_no_delete");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempt_gradings_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempt_gradings_no_delete");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_review_logs_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_review_logs_no_delete");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_delete");
        jdbcTemplate.update("DELETE FROM review_logs");
        jdbcTemplate.update("DELETE FROM reviews");
        jdbcTemplate.update("DELETE FROM appeals");
        jdbcTemplate.update("DELETE FROM attempt_gradings");
        jdbcTemplate.update("DELETE FROM attempts WHERE retry_of_id IS NOT NULL");
        jdbcTemplate.update("DELETE FROM attempts");
        jdbcTemplate.update("UPDATE sentences SET current_version_id = NULL");
        jdbcTemplate.update("DELETE FROM import_batch_rows");
        jdbcTemplate.update("DELETE FROM sentence_versions");
        jdbcTemplate.update("DELETE FROM sentences");
        jdbcTemplate.update("DELETE FROM import_batches");
        jdbcTemplate.update("DELETE FROM sentence_banks");
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

    /** 播种公开题库 + 一行真实导入的题目（chunk0=整句首字，可接受答案=参考译文，挖空 [0]）。 */
    private void seedSentence() {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9001, NULL, '端到端测试题库', NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL,"
                        + " NULL, ?, ?, NULL, NULL)",
                T0,
                T0,
                T0);
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, 9001, 'JSON', 'e2e.json', 'imports/e2e.json', ?, 1024,"
                        + " 'COMMITTING', 1, 'admin', ?)",
                "batch-e2e-0000-0000-000000000001",
                "a".repeat(64),
                T0);
        List<SentenceContent> rows =
                List.of(
                        new SentenceContent(
                                "e2e_0001",
                                "他越来越重视这件事情。",
                                REF,
                                "这是端到端测试的解析说明，长度满足最小要求。",
                                List.of(new SentenceChunk(0, "他", List.of(REF), "代词", null)),
                                List.of(0),
                                List.of("代词"),
                                null,
                                null,
                                null));
        seeded =
                contentImportService
                        .importSentences(
                                9001L, "batch-e2e-0000-0000-000000000001", rows, 1L, T0.toInstant())
                        .getFirst();
    }
}
