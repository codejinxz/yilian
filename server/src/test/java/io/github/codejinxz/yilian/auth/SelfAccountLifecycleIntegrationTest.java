package io.github.codejinxz.yilian.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.support.InsecureCookieHandler;
import io.github.codejinxz.yilian.support.TestDatabases;

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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 服务端账号导出 + 自助删号主链路端到端集成测试（真实 HTTP + 本机 MySQL，Flyway V1→V17）[AC-2][AC-3]。
 *
 * <p>导出：本人数据整包（attempts/gradings/appeals/review_cards/账号块）、他人数据不可见、 空数据形状、Content-Disposition
 * 附件与非统一信封的裸 JSON 体。删号：密码确认错密 40101、 2FA 开启者双确认（缺码 40000/错码 40101/动态码通过 200）、成功后软删+吊销会话+清 2FA、 回执
 * deletedAtUtc 与 anonymizeAtUtc 相差恰 30 天（配置键 yilian.account.anonymize-grace-days）、 已删重复调用
 * 40900、已删账号导出 401。管理员删除开启 2FA 的账号同样清 2FA 材料（恢复码行清零、TOTP 三列清空）， 与自助删号同口径。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class SelfAccountLifecycleIntegrationTest {

    /** bootstrap 管理员用户名；测试间清理时刻意保留该账号。 */
    private static final String ADMIN_USERNAME = "admin";

    /** 测试用 bootstrap 管理员密码（application-test.yml 的默认值）。 */
    private static final String TEST_ADMIN_PASSWORD = "test-admin-password";

    /** 测试题目 ULID（26 位占位；V10 已删物理外键，无需真实题目行）。 */
    private static final String SENTENCE_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    static {
        TestDatabases.ensureDatabase();
    }

    @Autowired private Environment environment;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private PasswordEncoder passwordEncoder;

    private String baseUrl;

    /** 清理会话、审计与临时账号；本人域学习记录按用户清理（先摘 append-only 触发器再删行，删后复原——e2e 先例）。 */
    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
        Long adminId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM users WHERE username = ?", Long.class, ADMIN_USERNAME);
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempts_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempts_no_delete");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempt_gradings_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempt_gradings_no_delete");
        jdbcTemplate.update("DELETE FROM spring_session_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM admin_audit_log");
        jdbcTemplate.update("DELETE FROM user_2fa_recovery_codes");
        // 学习记录按用户清理：上一轮用例的临时账号行与本轮用例的确定性断言互不污染。
        jdbcTemplate.update(
                "DELETE FROM attempt_gradings WHERE attempt_id IN"
                        + " (SELECT id FROM (SELECT id FROM attempts WHERE user_id <> ?) t)",
                adminId);
        jdbcTemplate.update("DELETE FROM attempts WHERE user_id <> ?", adminId);
        jdbcTemplate.update("DELETE FROM appeals WHERE user_id <> ?", adminId);
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
        jdbcTemplate.update("DELETE FROM users WHERE username <> ?", ADMIN_USERNAME);
        jdbcTemplate.update(
                "UPDATE users SET password_hash = ?, password_changed = b'1', role = 'ADMIN',"
                        + " enabled = b'1', totp_secret_cipher = NULL, totp_enabled = b'0',"
                        + " totp_enrolled_at = NULL, deleted_at = NULL, anonymized_at = NULL"
                        + " WHERE username = ?",
                passwordEncoder.encode(TEST_ADMIN_PASSWORD),
                ADMIN_USERNAME);
    }

    /** [AC-2] 导出返回本人数据整包 + 附件头；他人 attempt 不可见；不含凭据投影字段。 */
    @Test
    @DisplayName("[AC-2] 导出本人数据、隔离他人数据与凭据投影")
    void ac2_exportReturnsOwnDataWithAttachmentHeader() throws Exception {
        String[] access = registerAndLogin("export-learner", "export-password");
        Long myId = userIdOf("export-learner");
        Long otherId = insertTempUser("export-other", "other-password");
        insertAttempt("a0000000-0000-0000-0000-00000000000a", myId);
        insertAttempt("a0000000-0000-0000-0000-00000000000b", otherId);
        insertGrading("a0000000-0000-0000-0000-00000000000a");

        HttpClient client = newClient();
        HttpResponse<String> export =
                get(client, "/api/me/export", Map.of("Authorization", bearer(access[0])));

        assertThat(export.statusCode()).isEqualTo(200);
        String disposition = export.headers().firstValue("Content-Disposition").orElse("");
        assertThat(disposition).contains("attachment");
        assertThat(disposition)
                .containsPattern("filename=\"?yilian-export-\\d{4}-\\d{2}-\\d{2}\\.json");
        String body = export.body();
        // 裸导出 JSON（非统一信封）：文件应可直接归档，不带信封噪声。
        assertThat(body).contains("\"schemaVersion\":1");
        assertThat(body).contains("\"username\":\"export-learner\"");
        assertThat(body).contains("a0000000-0000-0000-0000-00000000000a");
        assertThat(body).contains("\"attemptGradings\"");
        assertThat(body).contains("\"reviewCards\"");
        assertThat(body).contains("\"settings\"");
        assertThat(body).contains("\"timezone\":\"Asia/Shanghai\"");
        // 他人数据不可见（水平越权反向）。
        assertThat(body).doesNotContain("a0000000-0000-0000-0000-00000000000b");
        // 凭据投影不进导出文件。
        assertThat(body).doesNotContain("passwordHash");
        assertThat(body).doesNotContain("totpSecretCipher");
        assertThat(body).doesNotContain("codeHash");

        // 反向：无 access token 401。
        HttpClient anonymous = newClient();
        HttpResponse<String> unauthorized = get(anonymous, "/api/me/export", Map.of());
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(unauthorized.body()).contains("\"code\":40100");
    }

    /** [AC-2] 空数据形状：新号导出返回空数组与账号块，不因无数据失败。 */
    @Test
    @DisplayName("[AC-2] 空数据导出形状")
    void ac2_exportEmptyShapeForFreshAccount() throws Exception {
        String[] access = registerAndLogin("export-empty", "export-password");

        HttpResponse<String> export =
                get(newClient(), "/api/me/export", Map.of("Authorization", bearer(access[0])));

        assertThat(export.statusCode()).isEqualTo(200);
        String body = export.body();
        assertThat(body).contains("\"attempts\":[]");
        assertThat(body).contains("\"attemptGradings\":[]");
        assertThat(body).contains("\"appeals\":[]");
        assertThat(body).contains("\"reviewCards\":[]");
        assertThat(body).contains("\"username\":\"export-empty\"");
    }

    /** [AC-3] 密码错误 40101；账号不被删除。 */
    @Test
    @DisplayName("[AC-3] 错误密码 40101 且不删号")
    void ac3_deleteAccountRejectsWrongPassword() throws Exception {
        String[] access = registerAndLogin("delete-learner", "delete-password");

        HttpResponse<String> response =
                deleteAccount(newClient(), access[0], "wrong-password", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":40101");
        assertThat(deletedAtOf("delete-learner")).isNull();
    }

    /** [AC-3] 2FA 双确认：开启者缺码 40000、错码 40101、动态码通过 200 回执。 */
    @Test
    @DisplayName("[AC-3] 2FA 开启者删号双确认分支")
    void ac3_deleteAccountWith2faRequiresValidCode() throws Exception {
        String[] access = registerAndLogin("delete-totp", "delete-password");
        String base32Secret = enrollTotp(newClient(), access[0], "delete-password");

        // 缺码：40000。
        HttpResponse<String> missing =
                deleteAccount(newClient(), access[0], "delete-password", null);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("\"code\":40000");

        // 错码：40101。
        HttpResponse<String> wrong =
                deleteAccount(newClient(), access[0], "delete-password", "00000000");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).contains("\"code\":40101");

        // 正确动态码：200 回执。
        HttpResponse<String> ok =
                deleteAccount(newClient(), access[0], "delete-password", currentCode(base32Secret));
        assertThat(ok.statusCode()).as("ok body=%s", ok.body()).isEqualTo(200);
        assertThat(ok.body()).contains("\"deletedAtUtc\"");
        assertThat(deletedAtOf("delete-totp")).isNotNull();
    }

    /** [AC-3] 删号成功：软删+吊销会话+清 2FA、回执相差 30 天、重复调用 40900、已删导出 401。 */
    @Test
    @DisplayName("[AC-3] 删号主链路与幂等口径")
    void ac3_deleteAccountSoftDeletesRevokesAndConflictsOnRepeat() throws Exception {
        String[] access = registerAndLogin("delete-main", "delete-password");
        Long myId = userIdOf("delete-main");
        insertAttempt("b0000000-0000-0000-0000-00000000000a", myId);

        HttpResponse<String> response =
                deleteAccount(newClient(), access[0], "delete-password", null);
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(body).contains("\"deletedAtUtc\"");
        assertThat(body).contains("\"anonymizeAtUtc\"");
        Instant deletedAtUtc = Instant.parse(jsonField(body, "deletedAtUtc"));
        Instant anonymizeAtUtc = Instant.parse(jsonField(body, "anonymizeAtUtc"));
        assertThat(anonymizeAtUtc).isEqualTo(deletedAtUtc.plus(30, ChronoUnit.DAYS));

        // 库态：软删落值、2FA 清空（即便从未注册）。
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT deleted_by, anonymized_at, totp_enabled FROM users WHERE id = ?",
                        myId);
        assertThat(((Number) row.get("deleted_by")).longValue()).isEqualTo(myId);
        assertThat(row.get("anonymized_at")).isNull();
        assertThat(row.get("totp_enabled")).isEqualTo(Boolean.FALSE);

        // refresh 会话被吊销：立即刷新 401。
        HttpClient refreshClient = newClient();
        String csrf = csrfToken(refreshClient);
        HttpResponse<String> refresh =
                post(refreshClient, "/api/auth/refresh", null, Map.of("X-XSRF-TOKEN", csrf));
        assertThat(refresh.statusCode()).isEqualTo(401);
        assertThat(refresh.body()).contains("\"code\":40100");

        // 已删账号登录被拒（与软删登录口径一致）。
        HttpClient loginClient = newClient();
        String loginCsrf = csrfToken(loginClient);
        HttpResponse<String> login =
                post(
                        loginClient,
                        "/api/auth/login",
                        Map.of("username", "delete-main", "password", "delete-password"),
                        Map.of("X-XSRF-TOKEN", loginCsrf));
        assertThat(login.statusCode()).isEqualTo(401);
        assertThat(login.body()).contains("\"code\":40101");

        // 学习记录不动：attempt 仍在（append-only，匿名化也不触碰）。
        Integer attempts =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM attempts WHERE user_id = ?", Integer.class, myId);
        assertThat(attempts).isEqualTo(1);

        // access token 余命内的重复删号：40900「账号已删除」。
        HttpResponse<String> repeat =
                deleteAccount(newClient(), access[0], "delete-password", null);
        assertThat(repeat.statusCode()).as("repeat body=%s", repeat.body()).isEqualTo(409);
        assertThat(repeat.body()).contains("\"code\":40900");

        // 已删账号导出按未登录收敛。
        HttpResponse<String> exportAfter =
                get(newClient(), "/api/me/export", Map.of("Authorization", bearer(access[0])));
        assertThat(exportAfter.statusCode()).isEqualTo(401);
        assertThat(exportAfter.body()).contains("\"code\":40100");
    }

    /** [AC-1] 管理员删除开启 2FA 的账号：恢复码行清零、TOTP 三列清空（密钥材料不留给已删账号）。 */
    @Test
    @DisplayName("[AC-1] 管理员删号清理 2FA 材料")
    void ac1_adminDeleteUserClears2faMaterials() throws Exception {
        String[] access = registerAndLogin("admin-del-totp", "delete-password");
        Long targetId = userIdOf("admin-del-totp");
        enrollTotp(newClient(), access[0], "delete-password");
        Integer enrolled =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_2fa_recovery_codes WHERE user_id = ?",
                        Integer.class,
                        targetId);
        assertThat(enrolled).isEqualTo(10);

        HttpClient adminClient = newClient();
        String adminToken = login(adminClient, ADMIN_USERNAME, TEST_ADMIN_PASSWORD);
        HttpResponse<String> deleted =
                send(
                        adminClient,
                        "DELETE",
                        "/api/admin/users/" + targetId,
                        Map.of("reason", "违规账号清退"),
                        Map.of(
                                "X-XSRF-TOKEN",
                                csrfToken(adminClient),
                                "Authorization",
                                bearer(adminToken)));
        assertThat(deleted.statusCode()).as("delete body=%s", deleted.body()).isEqualTo(200);

        Integer remaining =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_2fa_recovery_codes WHERE user_id = ?",
                        Integer.class,
                        targetId);
        assertThat(remaining).isZero();
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT totp_enabled, totp_secret_cipher, totp_enrolled_at, deleted_at"
                                + " FROM users WHERE id = ?",
                        targetId);
        assertThat(row.get("totp_enabled")).isEqualTo(Boolean.FALSE);
        assertThat(row.get("totp_secret_cipher")).isNull();
        assertThat(row.get("totp_enrolled_at")).isNull();
        assertThat(row.get("deleted_at")).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 注册并登录，返回 access token 与用户名。
     *
     * @param username 用户名
     * @param password 密码
     * @return [accessToken, username]
     */
    private String[] registerAndLogin(String username, String password) throws Exception {
        HttpClient client = newClient();
        String csrf = csrfToken(client);
        HttpResponse<String> register =
                post(
                        client,
                        "/api/auth/register",
                        Map.of(
                                "email",
                                username + "@example.com",
                                "username",
                                username,
                                "password",
                                password),
                        Map.of("X-XSRF-TOKEN", csrf));
        assertThat(register.statusCode()).as("register body=%s", register.body()).isEqualTo(200);
        return new String[] {accessToken(register.body()), username};
    }

    /**
     * 查询用户 id。
     *
     * @param username 用户名
     * @return id
     */
    private Long userIdOf(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    /**
     * 以既有账号登录，返回 access token。
     *
     * @param client HTTP 客户端
     * @param username 用户名
     * @param password 密码
     * @return access token
     */
    private String login(HttpClient client, String username, String password) throws Exception {
        HttpResponse<String> response =
                post(
                        client,
                        "/api/auth/login",
                        Map.of("username", username, "password", password),
                        Map.of("X-XSRF-TOKEN", csrfToken(client)));
        assertThat(response.statusCode()).as("login body=%s", response.body()).isEqualTo(200);
        return accessToken(response.body());
    }

    /**
     * 直插临时用户（他人数据隔离用）。
     *
     * @param username 用户名
     * @param password 密码
     * @return 用户 id
     */
    private Long insertTempUser(String username, String password) {
        jdbcTemplate.update(
                "INSERT INTO users (username, password_hash, role, enabled, password_changed,"
                        + " timezone, daily_goal, rating_standard, desired_retention, theme,"
                        + " notifications_enabled, created_at, updated_at)"
                        + " VALUES (?, ?, 'USER', b'1', b'1', 'Asia/Shanghai', 3, 'TEM8', 0.900,"
                        + " 'paper', b'0', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                username,
                passwordEncoder.encode(password));
        return userIdOf(username);
    }

    /**
     * 直插一条 attempt（V10 已删物理外键，ULID 占位即可）。
     *
     * @param attemptId 提交 id
     * @param userId 用户 id
     */
    private void insertAttempt(String attemptId, Long userId) {
        jdbcTemplate.update(
                "INSERT INTO attempts (id, user_id, sentence_id, sentence_version_id,"
                        + " question_version, mode, kind, answer, answer_hash, hints_used,"
                        + " created_at)"
                        + " VALUES (?, ?, ?, 1, 1, 'CLOZE', 'FIRST', '{\"slots\":[]}', ?, 0,"
                        + " UTC_TIMESTAMP(6))",
                attemptId,
                userId,
                SENTENCE_ID,
                "a".repeat(64));
    }

    /**
     * 给指定 attempt 插一条本地判分行。
     *
     * @param attemptId 提交 id
     */
    private void insertGrading(String attemptId) {
        jdbcTemplate.update(
                "INSERT INTO attempt_gradings (attempt_id, sequence, grader, score, max_score,"
                        + " result, normalizer_version, created_at)"
                        + " VALUES (?, 1, 'LOCAL', 100, 100, '{\"schemaVersion\":1}', 'norm-1',"
                        + " UTC_TIMESTAMP(6))",
                attemptId);
    }

    /**
     * 发起自助删号请求。
     *
     * @param client HTTP 客户端
     * @param accessToken access token
     * @param password 当前密码
     * @param totpCode 动态码，可空
     * @return 响应
     */
    private HttpResponse<String> deleteAccount(
            HttpClient client, String accessToken, String password, String totpCode)
            throws Exception {
        String csrf = csrfToken(client);
        StringBuilder payload = new StringBuilder("{\"password\":\"").append(password).append("\"");
        if (totpCode != null) {
            payload.append(",\"totpCode\":\"").append(totpCode).append("\"");
        }
        payload.append("}");
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/delete-account"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", csrf)
                        .header("Authorization", bearer(accessToken))
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 读取用户软删时刻。
     *
     * @param username 用户名
     * @return deleted_at（UTC）或 null
     */
    private Instant deletedAtOf(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM users WHERE username = ?",
                (rs, rowNum) ->
                        rs.getTimestamp("deleted_at") == null
                                ? null
                                : rs.getTimestamp("deleted_at").toInstant(),
                username);
    }

    /**
     * 注册 2FA：setup（密码确认）→ activate（动态码启用），返回 Base32 密钥供测试本地算码。
     *
     * @param client 已登录的 HTTP 客户端
     * @param accessToken access token
     * @param password 登录密码
     * @return Base32 密钥
     */
    private String enrollTotp(HttpClient client, String accessToken, String password)
            throws Exception {
        String csrf = csrfToken(client);
        HttpResponse<String> setup =
                post(
                        client,
                        "/api/auth/2fa/setup",
                        Map.of("password", password),
                        Map.of("X-XSRF-TOKEN", csrf, "Authorization", bearer(accessToken)));
        assertThat(setup.statusCode()).as("setup body=%s", setup.body()).isEqualTo(200);
        String secret = jsonField(setup.body(), "secret");

        String activateCsrf = csrfToken(client);
        HttpResponse<String> activate =
                post(
                        client,
                        "/api/auth/2fa/activate",
                        Map.of("code", currentCode(secret)),
                        Map.of("X-XSRF-TOKEN", activateCsrf, "Authorization", bearer(accessToken)));
        assertThat(activate.statusCode()).as("activate body=%s", activate.body()).isEqualTo(200);
        return secret;
    }

    /** 计算密钥的当前动态码（参考实现；±1 验证窗口吸收翻窗竞态）。 */
    private static String currentCode(String base32Secret) {
        long counter = Math.floorDiv(System.currentTimeMillis() / 1000, 30);
        return referenceHotp(base32Decode(base32Secret), counter);
    }

    /** RFC 4648 Base32 解码（测试本地版，标准字母表）。 */
    private static byte[] base32Decode(String encoded) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int bits = 0;
        int buffer = 0;
        byte[] output = new byte[encoded.length() * 5 / 8];
        int length = 0;
        for (int i = 0; i < encoded.length(); i++) {
            int value = alphabet.indexOf(encoded.charAt(i));
            assertThat(value).as("非法 Base32 字符 %s", encoded.charAt(i)).isGreaterThanOrEqualTo(0);
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                output[length++] = (byte) (buffer >>> (bits - 8));
                bits -= 8;
            }
        }
        byte[] trimmed = new byte[length];
        System.arraycopy(output, 0, trimmed, 0, length);
        return trimmed;
    }

    /** 参考 HOTP 实现（RFC 4226 §5.3，与被测实现独立书写）。 */
    private static String referenceHotp(byte[] secret, long counter) {
        byte[] message = new byte[8];
        for (int i = 0; i < 8; i++) {
            message[i] = (byte) (counter >>> (8 * (7 - i)));
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] digest = mac.doFinal(message);
            int offset = digest[digest.length - 1] & 0x0F;
            int binary =
                    ((digest[offset] & 0x7F) << 24)
                            | ((digest[offset + 1] & 0xFF) << 16)
                            | ((digest[offset + 2] & 0xFF) << 8)
                            | (digest[offset + 3] & 0xFF);
            return String.format("%08d", binary % 100_000_000);
        } catch (Exception exception) {
            throw new IllegalStateException("参考实现 HMAC 初始化失败", exception);
        }
    }

    private HttpClient newClient() {
        // test profile 下 XSRF-TOKEN 带 Secure 下发，用忽略 Secure 的 CookieHandler 模拟浏览器握手。
        return HttpClient.newBuilder()
                .cookieHandler(new InsecureCookieHandler())
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static final Pattern CSRF_TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    /**
     * 取当前 CSRF token（登录/认证请求可能轮换；调用点按真实前端行为重读）。
     *
     * @param client HTTP 客户端
     * @return CSRF token
     */
    private String csrfToken(HttpClient client) throws Exception {
        HttpResponse<String> response = get(client, "/api/auth/csrf", Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        Matcher matcher = CSRF_TOKEN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /**
     * 从 ApiResponse JSON 体提取顶层字符串字段。
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

    private static String accessToken(String body) {
        Matcher matcher = ACCESS_TOKEN.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private HttpResponse<String> get(HttpClient client, String path, Map<String, String> headers)
            throws Exception {
        return send(client, "GET", path, null, headers);
    }

    private HttpResponse<String> post(
            HttpClient client, String path, Object body, Map<String, String> headers)
            throws Exception {
        return send(client, "POST", path, body, headers);
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

    private static String writeJson(Object body) {
        if (body instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> "\"" + entry.getKey() + "\":" + jsonValue(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        throw new IllegalArgumentException("unsupported test payload");
    }

    private static String jsonValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool.toString();
        }
        return "\"" + value + "\"";
    }
}
