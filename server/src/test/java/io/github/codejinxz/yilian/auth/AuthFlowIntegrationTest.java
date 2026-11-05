package io.github.codejinxz.yilian.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.security.JwtProperties;
import io.github.codejinxz.yilian.common.util.IdUtils;
import io.github.codejinxz.yilian.support.InsecureCookieHandler;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 认证与安全链路的端到端集成测试：真实 HTTP + 本机 MySQL（Flyway 迁移与 ddl-auto=validate）。
 *
 * <p>测试使用独立测试库 {@code yilian_test}（不依赖 Docker / Testcontainers，也不会读写开发库 {@code yilian}）。数据源与
 * bootstrap 凭据由入库的 {@code application-test.yml} 按环境变量 {@code YILIAN_TEST_DB_*} 提供，类初始化时经 {@link
 * TestDatabases#ensureDatabase()} 幂等建库。
 *
 * <p><strong>前置条件</strong>：可用的 MySQL 8 实例与 {@code YILIAN_TEST_DB_PASSWORD} 等环境 变量（见
 * docs/BOOTSTRAP.md §5）。不满足时本类会显式失败，而不是像 Testcontainers 那样跳过。
 *
 * <p>每个用例开始前清理 {@code spring_session}、{@code spring_session_attributes} 以及除 bootstrap 管理员 {@code
 * admin} 之外的应用账号，并把 {@code admin} 的密码重置为测试值、首登改密标记 置为已改密、角色修正为 {@code
 * ADMIN}，保证重复运行与全新库（CI）幂等；{@code admin} 由应用启动时的 bootstrap 流程创建，测试不会删除它。
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
class AuthFlowIntegrationTest {

    /** bootstrap 管理员用户名；测试间清理时刻意保留该账号。 */
    private static final String ADMIN_USERNAME = "admin";

    /** 测试用 bootstrap 管理员密码（application-test.yml 的默认值），每次用例前写回。 */
    private static final String TEST_ADMIN_PASSWORD = "test-admin-password";

    static {
        TestDatabases.ensureDatabase();
    }

    @Autowired private Environment environment;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private ErrorLogRecorder errorLogRecorder;

    /** 与生产同源的 JWT 编码器与配置：过期反向测试用它自签 exp 已过的合法结构 token。 */
    @Autowired private JwtEncoder jwtEncoder;

    @Autowired private JwtProperties jwtProperties;

    private String baseUrl;

    /**
     * 记录随机端口并清理上一个用例留下的会话、临时账号与管理员凭据，保证测试可重复执行。
     *
     * <p>{@code yilian_test} 会跨次运行保留数据；若只保留 admin 不修复其凭据，上一次运行 遗留的哈希会让登录用例失败，遗留的「首登未改密」标记则会让业务接口被
     * 40303 拦下（CI 全新库首次暴露，cifix-20261104）。因此这里把 admin 的密码重置为测试值、
     * 改密标记置为已改密，并确保其启用且为管理员角色，但不删除该账号（bootstrap 负责创建它）。
     */
    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
        // 先删属性表再删会话表，避免触发 spring_session_attributes 的外键约束。
        jdbcTemplate.update("DELETE FROM spring_session_attributes");
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM import_batch_rows");
        jdbcTemplate.update("DELETE FROM import_batches");
        jdbcTemplate.update("DELETE FROM error_log");
        jdbcTemplate.update("DELETE FROM admin_audit_log");
        // 保留 bootstrap 创建的 admin；其他账号（如本类测试用的 bob）全部清理。
        // V5 已把 V1 的 app_user 改名为 users，清理与补数都必须走新表名。
        jdbcTemplate.update("DELETE FROM users WHERE username <> ?", ADMIN_USERNAME);

        // password_changed 一并置位（cifix-20261104）：CI 每次都是全新库，bootstrap admin
        // 停留在「首登未改密」态，业务接口会被 PasswordChangeRequiredInterceptor 以 40303
        // 拦下（ac3 停用吊销用例首次暴露）；本机持久测试库被历史运行置位后掩盖了该差异。
        // 40303 拦截语义由 MeControllerTest#putSettingsRequiresPasswordChangeWith40303
        // 单独覆盖，这里统一收敛为「健康管理员」夹具。
        int adminUpdated =
                jdbcTemplate.update(
                        "UPDATE users SET password_hash = ?, password_changed = b'1',"
                                + " role = 'ADMIN', enabled = b'1' WHERE username = ?",
                        passwordEncoder.encode(TEST_ADMIN_PASSWORD),
                        ADMIN_USERNAME);
        assertThat(adminUpdated).as("bootstrap 管理员 %s 不存在，无法运行认证集成测试", ADMIN_USERNAME).isEqualTo(1);
    }

    @Test
    void healthIsPublicAndUp() throws Exception {
        HttpResponse<String> response = get(newClient(), "/actuator/health", Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    /** [AC-4] 图形验证码端点公开可取且返回挑战结构（本类开关关闭，仅验证端点与形状）。 */
    @Test
    void ac4_captchaImageEndpointIsPublicAndReturnsChallenge() throws Exception {
        HttpResponse<String> response = get(newClient(), "/api/auth/captcha-image", Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":0");
        assertThat(response.body()).contains("\"captchaId\":\"\"");
        assertThat(response.body()).contains("\"enabled\":false");
    }

    /** 未知异常记录器确实把记录写入 error_log，而不是只调用 mock。 */
    @Test
    void unexpectedExceptionIsPersistedToErrorLog() {
        MDC.put("traceId", "it-error-log");
        try {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/api/test/unexpected");
            errorLogRecorder.record(new IllegalStateException("boom"), request);

            Integer count =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM error_log WHERE trace_id = ?",
                            Integer.class,
                            "it-error-log");
            assertThat(count).isEqualTo(1);
        } finally {
            MDC.clear();
        }
    }

    @Test
    void loginRefreshMeAndLogoutFlow() throws Exception {
        HttpClient client = newClient();
        String csrf = csrfToken(client);

        HttpResponse<String> login =
                send(
                        client,
                        "POST",
                        "/api/auth/login",
                        Map.of("username", "admin", "password", TEST_ADMIN_PASSWORD),
                        Map.of("X-XSRF-TOKEN", csrf));
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("\"code\":0");
        assertThat(login.body()).contains("\"username\":\"admin\"");
        assertThat(login.body()).contains("\"role\":\"ADMIN\"");
        assertThat(login.body()).contains("\"tokenType\":\"Bearer\"");
        String accessToken = accessToken(login.body());
        // 登录可能轮换 CSRF 令牌；按真实前端行为重新读取，避免拿登录前的旧 token 继续使用。
        csrf = csrfToken(client);

        HttpResponse<String> me =
                get(client, "/api/auth/me", Map.of("Authorization", bearer(accessToken)));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"code\":0");
        assertThat(me.body()).contains("\"username\":\"admin\"");
        // 认证请求可能触发 CSRF 轮换；在 refresh 前重新读取服务端当前 token。
        csrf = csrfToken(client);

        HttpResponse<String> refresh =
                post(client, "/api/auth/refresh", null, Map.of("X-XSRF-TOKEN", csrf));
        assertThat(refresh.statusCode()).as("refresh body=%s", refresh.body()).isEqualTo(200);
        assertThat(refresh.body()).contains("\"code\":0");
        String refreshedToken = accessToken(refresh.body());
        assertThat(refreshedToken).isNotEqualTo(accessToken);
        csrf = csrfToken(client);

        HttpResponse<String> meWithRefreshedToken =
                get(client, "/api/auth/me", Map.of("Authorization", bearer(refreshedToken)));
        assertThat(meWithRefreshedToken.statusCode()).isEqualTo(200);
        // /me 带 Bearer 认证会再次轮换 CSRF；登出前重新读取。
        csrf = csrfToken(client);

        HttpResponse<String> logout =
                post(
                        client,
                        "/api/auth/logout",
                        null,
                        Map.of("X-XSRF-TOKEN", csrf, "Authorization", bearer(refreshedToken)));
        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(logout.body()).contains("\"code\":0");
        assertThat(logout.body()).contains("已退出登录");
        csrf = csrfToken(client);

        HttpResponse<String> refreshAfterLogout =
                post(client, "/api/auth/refresh", null, Map.of("X-XSRF-TOKEN", csrf));
        assertThat(refreshAfterLogout.statusCode()).isEqualTo(401);
        assertThat(refreshAfterLogout.body()).contains("\"code\":40100");

        HttpResponse<String> meAfterLogout = get(client, "/api/auth/me", Map.of());
        assertThat(meAfterLogout.statusCode()).isEqualTo(401);
        assertThat(meAfterLogout.body()).contains("\"code\":40100");
    }

    @Test
    void wrongPasswordReturnsApiResponseEnvelope() throws Exception {
        HttpClient client = newClient();
        String csrf = csrfToken(client);

        HttpResponse<String> response =
                send(
                        client,
                        "POST",
                        "/api/auth/login",
                        Map.of("username", "admin", "password", "wrong-password"),
                        Map.of("X-XSRF-TOKEN", csrf));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
        assertThat(response.body()).contains("\"code\":40101");
        assertThat(response.body()).contains("\"data\":null");
        assertThat(response.body()).doesNotContain("Exception");
    }

    @Test
    void loginWithoutCsrfIsRejected() throws Exception {
        HttpResponse<String> response =
                send(
                        newClient(),
                        "POST",
                        "/api/auth/login",
                        Map.of("username", "admin", "password", TEST_ADMIN_PASSWORD),
                        Map.of());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":40301");
    }

    /** 伪造/损坏的 Bearer token 按未认证处理，不能进入业务层。 */
    @Test
    void invalidAccessTokenIsUnauthorized() throws Exception {
        HttpResponse<String> response =
                get(newClient(), "/api/auth/me", Map.of("Authorization", "Bearer not-a-jwt"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":40100");
    }

    /**
     * 过期 access token 的反向测试：用与生产同源的 HS256 编码器自签一个 exp 已过的合法 结构 token（签名有效、只是过期），解码器必须按 JwtException
     * 拒绝 → 401。 伪造/损坏 token 用例只证明「解析失败」被拒；本条补上「签名有效但过期」同样被拒。
     */
    @Test
    void expiredAccessTokenIsUnauthorized_023ac2() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(jwtProperties.issuer())
                        .issuedAt(now.minusSeconds(3_600))
                        .expiresAt(now.minusSeconds(1_800))
                        .subject("1")
                        .claim("username", ADMIN_USERNAME)
                        .claim("role", "ADMIN")
                        .claim("pwdChanged", true)
                        .id(IdUtils.newUlid())
                        .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String expiredToken =
                jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        HttpResponse<String> response =
                get(newClient(), "/api/auth/me", Map.of("Authorization", "Bearer " + expiredToken));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":40100");
    }

    @Test
    void adminPathRejectsRegularUser() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (username, password_hash, role, enabled, created_at, updated_at)"
                        + " VALUES (?, ?, 'USER', b'1', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                "bob",
                passwordEncoder.encode("bob-password"));

        HttpClient client = newClient();
        String csrf = csrfToken(client);
        HttpResponse<String> login =
                send(
                        client,
                        "POST",
                        "/api/auth/login",
                        Map.of("username", "bob", "password", "bob-password"),
                        Map.of("X-XSRF-TOKEN", csrf));
        assertThat(login.statusCode()).isEqualTo(200);
        String accessToken = accessToken(login.body());

        HttpResponse<String> adminPath =
                get(client, "/api/admin/ping", Map.of("Authorization", bearer(accessToken)));
        assertThat(adminPath.statusCode()).isEqualTo(403);
        assertThat(adminPath.body()).contains("\"code\":40300");
    }

    /** 注册即登录：邀请制关闭（local 默认）时无邀请码可注册，返回 USER 角色 access token； /me 能用新 token 读回新账号。 */
    @Test
    void registerCreatesUserAndIssuesTokens() throws Exception {
        HttpClient client = newClient();
        String csrf = csrfToken(client);

        HttpResponse<String> register =
                send(
                        client,
                        "POST",
                        "/api/auth/register",
                        Map.of(
                                "email",
                                "register-learner@example.com",
                                "username",
                                "registerlearner",
                                "password",
                                "register-password"),
                        Map.of("X-XSRF-TOKEN", csrf));
        assertThat(register.statusCode()).as("register body=%s", register.body()).isEqualTo(200);
        assertThat(register.body()).contains("\"code\":0");
        assertThat(register.body()).contains("\"role\":\"USER\"");
        assertThat(register.body()).contains("\"username\":\"registerlearner\"");

        String accessToken = accessToken(register.body());
        HttpResponse<String> me =
                get(client, "/api/auth/me", Map.of("Authorization", bearer(accessToken)));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"username\":\"registerlearner\"");
        assertThat(me.body()).contains("\"passwordChanged\":true");

        // 重复注册同一邮箱按 409 收敛为统一信封，不重复建号。
        HttpClient duplicateClient = newClient();
        String nextCsrf = csrfToken(duplicateClient);
        HttpResponse<String> duplicate =
                send(
                        duplicateClient,
                        "POST",
                        "/api/auth/register",
                        Map.of(
                                "email",
                                "register-learner@example.com",
                                "username",
                                "anotherlearner",
                                "password",
                                "register-password"),
                        Map.of("X-XSRF-TOKEN", nextCsrf));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("\"code\":40901");
    }

    /** 注册参数校验：非法邮箱按 40000 收敛为统一信封，不建号。 */
    @Test
    void registerRejectsInvalidEmailWith400() throws Exception {
        HttpClient client = newClient();
        String csrf = csrfToken(client);

        HttpResponse<String> response =
                send(
                        client,
                        "POST",
                        "/api/auth/register",
                        Map.of("email", "not-an-email", "password", "register-password"),
                        Map.of("X-XSRF-TOKEN", csrf));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":40000");
        assertThat(response.body()).contains("errors");
    }

    /** 改密链路：当前密码错误 401；改密成功后旧 refresh 会话被吊销、旧密码失效、新密码可登录。 */
    @Test
    void changePasswordRevokesSessionsAndInvalidatesOldPassword() throws Exception {
        HttpClient client = newClient();
        String csrf = csrfToken(client);
        HttpResponse<String> login =
                send(
                        client,
                        "POST",
                        "/api/auth/login",
                        Map.of("username", "admin", "password", TEST_ADMIN_PASSWORD),
                        Map.of("X-XSRF-TOKEN", csrf));
        assertThat(login.statusCode()).isEqualTo(200);
        String accessToken = accessToken(login.body());

        // 当前密码不正确：401 与统一文案（不区分账号不存在/密码错误）。
        csrf = csrfToken(client);
        HttpResponse<String> wrongCurrent =
                send(
                        client,
                        "PUT",
                        "/api/auth/password",
                        Map.of(
                                "currentPassword",
                                "wrong-current-password",
                                "newPassword",
                                "changed-password-9"),
                        Map.of("X-XSRF-TOKEN", csrf, "Authorization", bearer(accessToken)));
        assertThat(wrongCurrent.statusCode()).isEqualTo(401);
        assertThat(wrongCurrent.body()).contains("\"code\":40101");

        // 改密成功：吊销该用户全部 refresh 会话。
        csrf = csrfToken(client);
        HttpResponse<String> changed =
                send(
                        client,
                        "PUT",
                        "/api/auth/password",
                        Map.of(
                                "currentPassword",
                                TEST_ADMIN_PASSWORD,
                                "newPassword",
                                "changed-password-9"),
                        Map.of("X-XSRF-TOKEN", csrf, "Authorization", bearer(accessToken)));
        assertThat(changed.statusCode()).as("change body=%s", changed.body()).isEqualTo(200);
        assertThat(changed.body()).contains("\"code\":0");
        assertThat(changed.body()).contains("密码已更新");

        // 旧 refresh 会话已吊销：携带改密前 Cookie 的刷新请求必须 401。
        String csrfAfterChange = csrfToken(client);
        HttpResponse<String> refreshAfterChange =
                post(client, "/api/auth/refresh", null, Map.of("X-XSRF-TOKEN", csrfAfterChange));
        assertThat(refreshAfterChange.statusCode()).isEqualTo(401);
        assertThat(refreshAfterChange.body()).contains("\"code\":40100");

        // 旧密码失效、新密码可登录。
        HttpClient oldPasswordClient = newClient();
        String freshCsrf = csrfToken(oldPasswordClient);
        HttpResponse<String> loginWithOldPassword =
                send(
                        oldPasswordClient,
                        "POST",
                        "/api/auth/login",
                        Map.of("username", "admin", "password", TEST_ADMIN_PASSWORD),
                        Map.of("X-XSRF-TOKEN", freshCsrf));
        assertThat(loginWithOldPassword.statusCode()).isEqualTo(401);
        assertThat(loginWithOldPassword.body()).contains("\"code\":40101");

        HttpClient newPasswordClient = newClient();
        String newCsrf = csrfToken(newPasswordClient);
        HttpResponse<String> loginWithNewPassword =
                send(
                        newPasswordClient,
                        "POST",
                        "/api/auth/login",
                        Map.of("username", "admin", "password", "changed-password-9"),
                        Map.of("X-XSRF-TOKEN", newCsrf));
        assertThat(loginWithNewPassword.statusCode()).isEqualTo(200);
        assertThat(loginWithNewPassword.body()).contains("\"code\":0");
    }

    /** [AC-3] 管理员停用账号：refresh 会话立即吊销，旧 Cookie 刷新 40100。 */
    @Test
    void ac3_adminDisableRevokesRefreshSessions() throws Exception {
        // 注册即登录：被停用用户先建立可用的 refresh 会话。
        HttpClient userClient = newClient();
        String userCsrf = csrfToken(userClient);
        HttpResponse<String> register =
                send(
                        userClient,
                        "POST",
                        "/api/auth/register",
                        Map.of(
                                "email",
                                "disable-target@example.com",
                                "username",
                                "disabletarget",
                                "password",
                                "disable-password"),
                        Map.of("X-XSRF-TOKEN", userCsrf));
        assertThat(register.statusCode()).as("register body=%s", register.body()).isEqualTo(200);
        String userId = jsonField(register.body(), "id");
        assertThat(userId).isNotBlank();
        userCsrf = csrfToken(userClient);
        HttpResponse<String> refreshBefore =
                post(userClient, "/api/auth/refresh", null, Map.of("X-XSRF-TOKEN", userCsrf));
        assertThat(refreshBefore.statusCode()).as("停用前 refresh 应可用").isEqualTo(200);

        // 管理员登录并停用该用户（PATCH /api/admin/users/{id}/status）。
        HttpClient adminClient = newClient();
        String adminCsrf = csrfToken(adminClient);
        HttpResponse<String> adminLogin =
                send(
                        adminClient,
                        "POST",
                        "/api/auth/login",
                        Map.of("username", ADMIN_USERNAME, "password", TEST_ADMIN_PASSWORD),
                        Map.of("X-XSRF-TOKEN", adminCsrf));
        assertThat(adminLogin.statusCode()).isEqualTo(200);
        String adminToken = accessToken(adminLogin.body());

        adminCsrf = csrfToken(adminClient);
        HttpResponse<String> disable =
                send(
                        adminClient,
                        "PATCH",
                        "/api/admin/users/" + userId + "/status",
                        Map.of("enabled", false, "reason", "016 集成测试停用账号"),
                        Map.of("X-XSRF-TOKEN", adminCsrf, "Authorization", bearer(adminToken)));
        assertThat(disable.statusCode()).as("disable body=%s", disable.body()).isEqualTo(200);
        assertThat(disable.body()).contains("\"enabled\":false");

        // spring_session 中该 principal 的会话行已删除（吊销全部，不是只挡 enabled）。
        Long sessionCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM spring_session WHERE PRINCIPAL_NAME = ?",
                        Long.class,
                        "disabletarget");
        assertThat(sessionCount).as("停用后必须无残留会话行").isZero();

        // 旧 Cookie 刷新 → 401 + 40100。
        String csrfAfterDisable = csrfToken(userClient);
        HttpResponse<String> refreshAfterDisable =
                post(
                        userClient,
                        "/api/auth/refresh",
                        null,
                        Map.of("X-XSRF-TOKEN", csrfAfterDisable));
        assertThat(refreshAfterDisable.statusCode()).isEqualTo(401);
        assertThat(refreshAfterDisable.body()).contains("\"code\":40100");
    }

    private HttpClient newClient() {
        // test profile 下 XSRF-TOKEN 带 Secure 下发（SecurityConfig 仅对 local 关闭），
        // 用忽略 Secure 的测试 CookieHandler 模拟浏览器本地握手（见 InsecureCookieHandler）。
        return HttpClient.newBuilder()
                .cookieHandler(new InsecureCookieHandler())
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static final Pattern CSRF_TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    /**
     * 从 ApiResponse JSON 体中提取顶层 data 内的字符串字段（如 user.id）。
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

    private String csrfToken(HttpClient client) throws Exception {
        HttpResponse<String> response = get(client, "/api/auth/csrf", Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":0");
        Matcher matcher = CSRF_TOKEN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
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
                    .collect(Collectors.joining(",", "{", "}"));
        }
        throw new IllegalArgumentException("unsupported test payload");
    }

    /**
     * 单值 JSON 字面量：布尔直出，其余按字符串加引号。
     *
     * @param value 字段值
     * @return JSON 字面量
     */
    private static String jsonValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool.toString();
        }
        return "\"" + value + "\"";
    }
}
