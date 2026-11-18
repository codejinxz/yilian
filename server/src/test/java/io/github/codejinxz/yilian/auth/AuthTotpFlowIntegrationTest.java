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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 两步验证主链路端到端集成测试（真实 HTTP + 本机 MySQL，Flyway V15）[AC-4][AC-5][AC-6]： 注册两步（setup → activate）→ 登录 40305
 * 第二步 → 恢复码一次性消费 → 关闭双确认 → 管理员 reset-2fa 解救；challenge 与 access token 的 purpose 隔离在真实过滤器链下验证。
 *
 * <p>动态码用测试内参考 HOTP 实现计算（算法正确性由 {@code TotpServiceImplTest} 的 RFC 附录 B 向量锁定）；±1 验证窗口覆盖 30
 * 秒边界的翻窗竞态，测试无需 sleep。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class AuthTotpFlowIntegrationTest {

    /** bootstrap 管理员用户名；测试间清理时刻意保留该账号。 */
    private static final String ADMIN_USERNAME = "admin";

    /** 测试用 bootstrap 管理员密码（与 AuthFlowIntegrationTest 同值，application-test.yml 默认）。 */
    private static final String TEST_ADMIN_PASSWORD = "test-admin-password";

    static {
        TestDatabases.ensureDatabase();
    }

    @Autowired private Environment environment;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private PasswordEncoder passwordEncoder;

    private String baseUrl;

    /**
     * 清理会话、临时账号与 2FA 状态：admin 密码写回测试值、三列 TOTP 状态清空、 恢复码全清，保证重复运行幂等（本类每个用例都会让 admin / 临时账号进过 2FA 态）。
     */
    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
        jdbcTemplate.update("DELETE FROM spring_session_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM user_2fa_recovery_codes");
        jdbcTemplate.update("DELETE FROM admin_audit_log");
        jdbcTemplate.update("DELETE FROM users WHERE username <> ?", ADMIN_USERNAME);
        jdbcTemplate.update(
                "UPDATE users SET password_hash = ?, password_changed = b'1', role = 'ADMIN',"
                        + " enabled = b'1', totp_secret_cipher = NULL, totp_enabled = b'0',"
                        + " totp_enrolled_at = NULL WHERE username = ?",
                passwordEncoder.encode(TEST_ADMIN_PASSWORD),
                ADMIN_USERNAME);
    }

    /**
     * [AC-4] 注册往返：setup 错密码 40101 → setup 回一次性密钥 → activate 验码启用并回 10 个恢复码 → status enabled →
     * disable 双确认后回未注册态。
     */
    @Test
    @DisplayName("[AC-4] setup → activate → status → disable 全往返")
    void ac4_enrollStatusDisableRoundTrip() throws Exception {
        HttpClient client = newClient();
        String accessToken = login(client, ADMIN_USERNAME, TEST_ADMIN_PASSWORD);

        // setup 错密码：40101。
        String csrf = csrfToken(client);
        HttpResponse<String> wrongPassword =
                send(
                        client,
                        "POST",
                        "/api/auth/2fa/setup",
                        Map.of("password", "wrong-password"),
                        Map.of("X-XSRF-TOKEN", csrf, "Authorization", bearer(accessToken)));
        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(wrongPassword.body()).contains("\"code\":40101");

        // setup 正确：密钥 + otpauth URI 一次性展示。
        csrf = csrfToken(client);
        HttpResponse<String> setup =
                send(
                        client,
                        "POST",
                        "/api/auth/2fa/setup",
                        Map.of("password", TEST_ADMIN_PASSWORD),
                        Map.of("X-XSRF-TOKEN", csrf, "Authorization", bearer(accessToken)));
        assertThat(setup.statusCode()).as("setup body=%s", setup.body()).isEqualTo(200);
        String secret = jsonField(setup.body(), "secret");
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(setup.body()).contains("otpauth://totp/Yilian:admin?");
        assertThat(setup.body())
                .contains("issuer=Yilian")
                .contains("digits=8")
                .contains("period=30");

        // status：enabled=false（setup 未激活）。
        csrf = csrfToken(client);
        HttpResponse<String> statusBefore =
                get(client, "/api/auth/2fa/status", Map.of("Authorization", bearer(accessToken)));
        assertThat(statusBefore.body()).contains("\"enabled\":false");

        // activate 错码 40101；当前码 200 并回 10 个恢复码。
        csrf = csrfToken(client);
        HttpResponse<String> wrongCode =
                send(
                        client,
                        "POST",
                        "/api/auth/2fa/activate",
                        Map.of("code", "00000000"),
                        Map.of("X-XSRF-TOKEN", csrf, "Authorization", bearer(accessToken)));
        assertThat(wrongCode.statusCode()).isEqualTo(401);
        assertThat(wrongCode.body()).contains("\"code\":40101");

        csrf = csrfToken(client);
        HttpResponse<String> activate =
                send(
                        client,
                        "POST",
                        "/api/auth/2fa/activate",
                        Map.of("code", currentCode(secret)),
                        Map.of("X-XSRF-TOKEN", csrf, "Authorization", bearer(accessToken)));
        assertThat(activate.statusCode()).as("activate body=%s", activate.body()).isEqualTo(200);
        Matcher codes = Pattern.compile("\"recoveryCodes\":\\[([^]]+)\\]").matcher(activate.body());
        assertThat(codes.find()).as("activate 回执含 recoveryCodes").isTrue();
        String[] recoveryCodes = codes.group(1).replace("\"", "").split(",");
        assertThat(recoveryCodes).hasSize(10);
        for (String code : recoveryCodes) {
            assertThat(code.trim()).matches("\\d{8}");
        }

        // status：enabled=true 且剩 10 个恢复码。
        csrf = csrfToken(client);
        HttpResponse<String> statusAfter =
                get(client, "/api/auth/2fa/status", Map.of("Authorization", bearer(accessToken)));
        assertThat(statusAfter.body()).contains("\"enabled\":true");
        assertThat(statusAfter.body()).contains("\"remainingRecoveryCodes\":10");

        // disable：密码 + 当前码双确认 → status enabled=false，恢复码清零。
        csrf = csrfToken(client);
        HttpResponse<String> disable =
                send(
                        client,
                        "POST",
                        "/api/auth/2fa/disable",
                        Map.of("password", TEST_ADMIN_PASSWORD, "code", currentCode(secret)),
                        Map.of("X-XSRF-TOKEN", csrf, "Authorization", bearer(accessToken)));
        assertThat(disable.statusCode()).as("disable body=%s", disable.body()).isEqualTo(200);
        csrf = csrfToken(client);
        HttpResponse<String> statusDisabled =
                get(client, "/api/auth/2fa/status", Map.of("Authorization", bearer(accessToken)));
        assertThat(statusDisabled.body()).contains("\"enabled\":false");
        assertThat(statusDisabled.body()).contains("\"remainingRecoveryCodes\":0");

        // 关闭后登录不再要求第二步（黑盒套件既有登录路径零变化的反向确认）。
        HttpClient plainClient = newClient();
        HttpResponse<String> plainLogin =
                send(
                        plainClient,
                        "POST",
                        "/api/auth/login",
                        Map.of("username", ADMIN_USERNAME, "password", TEST_ADMIN_PASSWORD),
                        Map.of("X-XSRF-TOKEN", csrfToken(plainClient)));
        assertThat(plainLogin.statusCode()).isEqualTo(200);
        assertThat(plainLogin.body()).contains("\"accessToken\"");
    }

    /** [AC-5] 登录第二步：启用后密码步 40305+challengeToken → login/totp 当前码通过；challenge 当 Bearer 用被过滤器链拒收。 */
    @Test
    @DisplayName("[AC-5] 登录第二步与 challenge purpose 隔离")
    void ac5_loginSecondStepAndPurposeIsolation() throws Exception {
        String secret = enrollAdmin().secret();

        // 密码步：40305，data 携 challengeToken，响应无 accessToken。
        HttpClient client = newClient();
        HttpResponse<String> passwordStep = loginRaw(client, ADMIN_USERNAME, TEST_ADMIN_PASSWORD);
        assertThat(passwordStep.statusCode()).isEqualTo(403);
        assertThat(passwordStep.body()).contains("\"code\":40305");
        assertThat(passwordStep.body()).contains("需要两步验证");
        String challengeToken = jsonField(passwordStep.body(), "challengeToken");
        assertThat(challengeToken).isNotBlank();
        assertThat(passwordStep.body()).doesNotContain("accessToken");

        // challenge 冒充 access token：过滤器链拒收（401），第二步不可绕过。
        HttpResponse<String> challengeAsBearer =
                get(client, "/api/auth/me", Map.of("Authorization", bearer(challengeToken)));
        assertThat(challengeAsBearer.statusCode()).isEqualTo(401);
        assertThat(challengeAsBearer.body()).contains("\"code\":40100");

        // 错码 40101；正确动态码 → 200 AuthResponse。
        csrfToken(client);
        HttpResponse<String> wrongCode =
                send(
                        client,
                        "POST",
                        "/api/auth/login/totp",
                        Map.of("challengeToken", challengeToken, "code", "00000000"),
                        Map.of("X-XSRF-TOKEN", csrfToken(client)));
        assertThat(wrongCode.statusCode()).isEqualTo(401);
        assertThat(wrongCode.body()).contains("\"code\":40101");

        HttpResponse<String> secondStep =
                send(
                        client,
                        "POST",
                        "/api/auth/login/totp",
                        Map.of("challengeToken", challengeToken, "code", currentCode(secret)),
                        Map.of("X-XSRF-TOKEN", csrfToken(client)));
        assertThat(secondStep.statusCode()).as("totp body=%s", secondStep.body()).isEqualTo(200);
        assertThat(secondStep.body()).contains("\"accessToken\"");
        assertThat(secondStep.body()).contains("\"username\":\"admin\"");
        // 验证码登录不消费恢复码：无剩余数字段（null）。
        assertThat(secondStep.body()).contains("\"remainingRecoveryCodes\":null");

        // 反向隔离：access token 冒充 challengeToken 调第二步——verifyLoginChallenge 的
        // purpose 校验拒绝（access token 不携带 purpose 声明），统一 40101「请重新登录」口径。
        HttpResponse<String> accessAsChallenge =
                send(
                        client,
                        "POST",
                        "/api/auth/login/totp",
                        Map.of(
                                "challengeToken",
                                accessToken(secondStep.body()),
                                "code",
                                currentCode(secret)),
                        Map.of("X-XSRF-TOKEN", csrfToken(client)));
        assertThat(accessAsChallenge.statusCode()).isEqualTo(401);
        assertThat(accessAsChallenge.body()).contains("\"code\":40101");
        assertThat(accessAsChallenge.body()).contains("登录已过期");
    }

    /** [AC-5] 恢复码登录：一次性消费、剩余数随响应可见、重放同一恢复码被拒；TOTP 码不受影响。 */
    @Test
    @DisplayName("[AC-5] 恢复码一次性消费与剩余数可见")
    void ac5_recoveryCodeSingleUse() throws Exception {
        Enrollment enrollment = enrollAdmin();
        String secret = enrollment.secret();
        String[] recoveryCodes = enrollment.recoveryCodes();

        HttpClient client = newClient();
        HttpResponse<String> passwordStep = loginRaw(client, ADMIN_USERNAME, TEST_ADMIN_PASSWORD);
        assertThat(passwordStep.statusCode()).isEqualTo(403);
        String challengeToken = jsonField(passwordStep.body(), "challengeToken");

        // 恢复码登录：200 且剩余数 9（10 - 1）。
        HttpResponse<String> recoveryLogin =
                send(
                        client,
                        "POST",
                        "/api/auth/login/totp",
                        Map.of("challengeToken", challengeToken, "code", recoveryCodes[0].trim()),
                        Map.of("X-XSRF-TOKEN", csrfToken(client)));
        assertThat(recoveryLogin.statusCode())
                .as("recovery body=%s", recoveryLogin.body())
                .isEqualTo(200);
        assertThat(recoveryLogin.body()).contains("\"accessToken\"");
        assertThat(recoveryLogin.body()).contains("\"remainingRecoveryCodes\":9");

        // status 确认剩 9；已用恢复码重放被拒（一次性语义，used_at 落值不复活）。
        HttpResponse<String> status =
                get(
                        client,
                        "/api/auth/2fa/status",
                        Map.of("Authorization", bearer(accessToken(recoveryLogin.body()))));
        assertThat(status.body()).contains("\"remainingRecoveryCodes\":9");
        // 恢复码重放与 TOTP 二连发共用一个新客户端：CSRF 双提交要求 cookie 与头同源。
        HttpClient replayClient = newClient();
        HttpResponse<String> replay =
                send(
                        replayClient,
                        "POST",
                        "/api/auth/login/totp",
                        Map.of(
                                "challengeToken",
                                jsonField(
                                        loginRaw(replayClient, ADMIN_USERNAME, TEST_ADMIN_PASSWORD)
                                                .body(),
                                        "challengeToken"),
                                "code",
                                recoveryCodes[0].trim()),
                        Map.of("X-XSRF-TOKEN", csrfToken(replayClient)));
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(replay.body()).contains("\"code\":40101");

        // TOTP 码登录不受恢复码消耗影响。
        HttpResponse<String> totpLogin =
                send(
                        replayClient,
                        "POST",
                        "/api/auth/login/totp",
                        Map.of(
                                "challengeToken",
                                jsonField(
                                        loginRaw(replayClient, ADMIN_USERNAME, TEST_ADMIN_PASSWORD)
                                                .body(),
                                        "challengeToken"),
                                "code",
                                currentCode(secret)),
                        Map.of("X-XSRF-TOKEN", csrfToken(replayClient)));
        assertThat(totpLogin.statusCode()).isEqualTo(200);
        assertThat(totpLogin.body()).contains("\"remainingRecoveryCodes\":null");
    }

    /** [AC-5] 同一恢复码两线程并发登录：Dao 条件更新原子化消费，恰一 200 一 40101，库内 used_at 只落一次。 */
    @Test
    @DisplayName("[AC-5] 恢复码并发单次语义（条件更新原子化，镜像并发套件样式）")
    void ac5_recoveryCodeConcurrentSingleUse() throws Exception {
        Enrollment enrollment = enrollAdmin();
        String code = enrollment.recoveryCodes()[0].trim();

        // 两个独立客户端各自走完密码步拿到挑战，再用同一枚恢复码并发提交第二步。
        HttpClient clientA = newClient();
        HttpClient clientB = newClient();
        String challengeA = challengeFor(clientA);
        String challengeB = challengeFor(clientB);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> futureA =
                    pool.submit(() -> totpSubmit(clientA, challengeA, code, start));
            Future<HttpResponse<String>> futureB =
                    pool.submit(() -> totpSubmit(clientB, challengeB, code, start));
            // 双任务均已就位（各自备好 CSRF），放行闸放行：两请求贴近同时到达服务端竞争条件更新。
            start.countDown();
            HttpResponse<String> responseA = futureA.get(30, TimeUnit.SECONDS);
            HttpResponse<String> responseB = futureB.get(30, TimeUnit.SECONDS);

            // 恰一 200 一 401：条件更新使后到者影响行数 0，输家 40101「恢复码无效或已使用」。
            HttpResponse<String> winner = responseA.statusCode() == 200 ? responseA : responseB;
            HttpResponse<String> loser = responseA.statusCode() == 200 ? responseB : responseA;
            assertThat(winner.statusCode()).as("winner body=%s", winner.body()).isEqualTo(200);
            assertThat(loser.statusCode()).isEqualTo(401);
            assertThat(loser.body()).contains("\"code\":40101");
            assertThat(loser.body()).contains("恢复码无效或已使用");

            // 库内该行只落一次 used_at（append-only 行保留，剩余 9 枚未被波及）。
            Long usedRows =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM user_2fa_recovery_codes"
                                    + " WHERE used_at IS NOT NULL",
                            Long.class);
            assertThat(usedRows).as("并发双发同码只落一次 used_at").isEqualTo(1L);

            // 赢家登录态可用：status 剩余恢复码 9。
            HttpResponse<String> status =
                    get(
                            winner == responseA ? clientA : clientB,
                            "/api/auth/2fa/status",
                            Map.of("Authorization", bearer(accessToken(winner.body()))));
            assertThat(status.body()).contains("\"remainingRecoveryCodes\":9");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 走完密码步并返回 40305 的 challengeToken（并发用例夹具）。
     *
     * @param client HTTP 客户端
     * @return challenge token
     */
    private String challengeFor(HttpClient client) throws Exception {
        HttpResponse<String> passwordStep = loginRaw(client, ADMIN_USERNAME, TEST_ADMIN_PASSWORD);
        assertThat(passwordStep.statusCode()).isEqualTo(403);
        assertThat(passwordStep.body()).contains("\"code\":40305");
        return jsonField(passwordStep.body(), "challengeToken");
    }

    /**
     * 并发提交第二步：先取 CSRF，对齐起跑线后发同一枚恢复码。
     *
     * @param client HTTP 客户端
     * @param challengeToken 密码步挑战
     * @param code 恢复码
     * @param start 起跑闸
     * @return 第二步响应
     */
    private HttpResponse<String> totpSubmit(
            HttpClient client, String challengeToken, String code, CountDownLatch start)
            throws Exception {
        String csrf = csrfToken(client);
        start.await();
        return send(
                client,
                "POST",
                "/api/auth/login/totp",
                Map.of("challengeToken", challengeToken, "code", code),
                Map.of("X-XSRF-TOKEN", csrf));
    }

    /** [AC-6] 管理员 reset-2fa：解救启用 2FA 的用户（清注册+吊销会话，登录回到单步）；未注册目标幂等 200；缺失目标 40400。 */
    @Test
    @DisplayName("[AC-6] 管理员重置两步验证解救与幂等")
    void ac6_adminReset2faUnlocksAndIsIdempotent() throws Exception {
        // 注册并给 bob 启用 2FA。
        HttpClient bobClient = newClient();
        String bobToken = register(bobClient, "totpbob", "totp-bob@example.com", "totp-password");
        enroll(bobClient, bobToken, "totp-password");

        // bob 密码步被 40305 挡住（确认已生效）。
        HttpResponse<String> bobBlocked = loginRaw(newClient(), "totpbob", "totp-password");
        assertThat(bobBlocked.statusCode()).isEqualTo(403);
        assertThat(bobBlocked.body()).contains("\"code\":40305");

        // 管理端列表投影 totpEnabled=true。
        HttpClient adminClient = newClient();
        String adminToken = login(adminClient, ADMIN_USERNAME, TEST_ADMIN_PASSWORD);
        HttpResponse<String> users =
                get(
                        adminClient,
                        "/api/admin/users?keyword=totpbob",
                        Map.of("Authorization", bearer(adminToken)));
        assertThat(users.body()).contains("\"totpEnabled\":true");
        String bobId = jsonField(users.body(), "id");

        // reset-2fa：reason 必填、成功提示；bob 登录回到单步 200。
        HttpResponse<String> blankReason =
                send(
                        adminClient,
                        "POST",
                        "/api/admin/users/" + bobId + "/reset-2fa",
                        Map.of("reason", ""),
                        Map.of(
                                "X-XSRF-TOKEN",
                                csrfToken(adminClient),
                                "Authorization",
                                bearer(adminToken)));
        assertThat(blankReason.statusCode()).isEqualTo(400);
        assertThat(blankReason.body()).contains("\"code\":40000");

        HttpResponse<String> reset =
                send(
                        adminClient,
                        "POST",
                        "/api/admin/users/" + bobId + "/reset-2fa",
                        Map.of("reason", "用户恢复码全失，管理员解救"),
                        Map.of(
                                "X-XSRF-TOKEN",
                                csrfToken(adminClient),
                                "Authorization",
                                bearer(adminToken)));
        assertThat(reset.statusCode()).as("reset body=%s", reset.body()).isEqualTo(200);
        assertThat(reset.body()).contains("已重置该用户的两步验证");

        HttpResponse<String> bobUnblocked = loginRaw(newClient(), "totpbob", "totp-password");
        assertThat(bobUnblocked.statusCode()).isEqualTo(200);
        assertThat(bobUnblocked.body()).contains("\"accessToken\"");

        // 幂等：再对未注册的 bob 重置 → 仍 200（无状态变化）。
        HttpResponse<String> resetAgain =
                send(
                        adminClient,
                        "POST",
                        "/api/admin/users/" + bobId + "/reset-2fa",
                        Map.of("reason", "重复提交"),
                        Map.of(
                                "X-XSRF-TOKEN",
                                csrfToken(adminClient),
                                "Authorization",
                                bearer(adminToken)));
        assertThat(resetAgain.statusCode()).isEqualTo(200);

        // 缺失目标：40400。
        HttpResponse<String> missing =
                send(
                        adminClient,
                        "POST",
                        "/api/admin/users/999999/reset-2fa",
                        Map.of("reason", "x"),
                        Map.of(
                                "X-XSRF-TOKEN",
                                csrfToken(adminClient),
                                "Authorization",
                                bearer(adminToken)));
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":40400");
    }

    /** 注册两步完成后的夹具：密钥（计算动态码用）与一次性恢复码明文。 */
    private record Enrollment(String secret, String[] recoveryCodes) {}

    /**
     * 给 admin 完成注册两步（setup + activate），返回密钥与恢复码。
     *
     * @return 注册夹具
     */
    private Enrollment enrollAdmin() throws Exception {
        HttpClient client = newClient();
        String accessToken = login(client, ADMIN_USERNAME, TEST_ADMIN_PASSWORD);
        return enroll(client, accessToken, TEST_ADMIN_PASSWORD);
    }

    /**
     * 给当前登录用户完成注册两步，返回密钥与一次性恢复码。
     *
     * @param client 已登录的客户端
     * @param accessToken access token
     * @param password 注册确认密码
     * @return 注册夹具
     */
    private Enrollment enroll(HttpClient client, String accessToken, String password)
            throws Exception {
        HttpResponse<String> setup =
                send(
                        client,
                        "POST",
                        "/api/auth/2fa/setup",
                        Map.of("password", password),
                        Map.of(
                                "X-XSRF-TOKEN",
                                csrfToken(client),
                                "Authorization",
                                bearer(accessToken)));
        assertThat(setup.statusCode()).as("setup body=%s", setup.body()).isEqualTo(200);
        String secret = jsonField(setup.body(), "secret");
        HttpResponse<String> activate =
                send(
                        client,
                        "POST",
                        "/api/auth/2fa/activate",
                        Map.of("code", currentCode(secret)),
                        Map.of(
                                "X-XSRF-TOKEN",
                                csrfToken(client),
                                "Authorization",
                                bearer(accessToken)));
        assertThat(activate.statusCode()).as("activate body=%s", activate.body()).isEqualTo(200);
        Matcher codes = Pattern.compile("\"recoveryCodes\":\\[([^]]+)\\]").matcher(activate.body());
        assertThat(codes.find()).as("activate 回执含 recoveryCodes").isTrue();
        return new Enrollment(secret, codes.group(1).replace("\"", "").split(","));
    }

    /**
     * 登录并返回 access token（用于夹具准备）。
     *
     * @param client HTTP 客户端
     * @param username 用户名
     * @param password 密码
     * @return access token
     */
    private String login(HttpClient client, String username, String password) throws Exception {
        HttpResponse<String> response = loginRaw(client, username, password);
        assertThat(response.statusCode()).as("login body=%s", response.body()).isEqualTo(200);
        return accessToken(response.body());
    }

    /**
     * 原样登录（不断言状态码，40305 分支用）。
     *
     * @param client HTTP 客户端
     * @param username 用户名
     * @param password 密码
     * @return 登录响应
     */
    private HttpResponse<String> loginRaw(HttpClient client, String username, String password)
            throws Exception {
        return send(
                client,
                "POST",
                "/api/auth/login",
                Map.of("username", username, "password", password),
                Map.of("X-XSRF-TOKEN", csrfToken(client)));
    }

    /**
     * 邀请制关闭下注册新用户并返回 access token。
     *
     * @param client HTTP 客户端
     * @param username 用户名
     * @param email 邮箱
     * @param password 密码
     * @return access token
     */
    private String register(HttpClient client, String username, String email, String password)
            throws Exception {
        HttpResponse<String> response =
                send(
                        client,
                        "POST",
                        "/api/auth/register",
                        Map.of("email", email, "username", username, "password", password),
                        Map.of("X-XSRF-TOKEN", csrfToken(client)));
        assertThat(response.statusCode()).as("register body=%s", response.body()).isEqualTo(200);
        return accessToken(response.body());
    }

    /**
     * 计算密钥的当前动态码（参考实现；±1 验证窗口吸收翻窗竞态）。
     *
     * @param base32Secret Base32 密钥
     * @return 8 位动态码
     */
    private static String currentCode(String base32Secret) {
        long counter = Math.floorDiv(System.currentTimeMillis() / 1000, 30);
        return referenceHotp(base32Decode(base32Secret), counter);
    }

    /**
     * RFC 4648 Base32 解码（测试本地版，标准字母表）。
     *
     * @param encoded Base32 字符串
     * @return 解码字节
     */
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

    /**
     * 参考 HOTP 实现（RFC 4226 §5.3，与被测实现独立书写）。
     *
     * @param secret 密钥字节
     * @param counter 步进计数
     * @return 8 位动态码
     */
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

    /**
     * 读取 CSRF token（登录态轮换后重取）。
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
     * 提取 access token。
     *
     * @param body 认证响应体
     * @return token 字符串
     */
    private static String accessToken(String body) {
        Matcher matcher = ACCESS_TOKEN.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /**
     * 拼接 Bearer 头值。
     *
     * @param token access token
     * @return 头值
     */
    private static String bearer(String token) {
        return "Bearer " + token;
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
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(writeJson(body)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 简单 Map → JSON（测试载荷全为字符串）。
     *
     * @param body 字段集
     * @return JSON 文本
     */
    private static String writeJson(Object body) {
        if (body instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
                    .collect(Collectors.joining(",", "{", "}"));
        }
        throw new IllegalArgumentException("unsupported test payload");
    }
}
