package io.github.codejinxz.yilian.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 认证与安全链路的端到端集成测试：真实 HTTP + 本机 MySQL（Flyway 迁移与 ddl-auto=validate）。
 *
 * <p>测试使用本机 MySQL 的独立测试库 {@code yilian_test}，不依赖 Docker / Testcontainers， 也不会读写开发库 {@code
 * yilian}。类初始化时会用固定测试账号 {@code root / 123456} 连接 {@code localhost:3306} 并执行 {@code CREATE DATABASE
 * IF NOT EXISTS yilian_test}。
 *
 * <p><strong>前置条件</strong>：本机 MySQL 8 已在 {@code localhost:3306} 启动，且 {@code root / 123456}
 * 可以登录。不满足时本类会直接失败，而不是像 Testcontainers 那样跳过。
 *
 * <p>每个用例开始前清理 {@code spring_session}、{@code spring_session_attributes} 以及除 bootstrap 管理员 {@code
 * admin} 之外的应用账号，并把 {@code admin} 的密码重置为测试值、 角色修正为 {@code ADMIN}，保证重复运行幂等；{@code admin} 由应用启动时的
 * bootstrap 流程创建，测试不会删除它。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.out-of-order=true",
            "yilian.ratelimit.enabled=false",
            "management.health.redis.enabled=false",
            "server.servlet.session.cookie.secure=false",
            "yilian.bootstrap-admin.username=admin",
            "yilian.bootstrap-admin.password=test-admin-password",
            "yilian.security.jwt.secret=test-only-yilian-jwt-secret-0123456789abcdef"
        })
class AuthFlowIntegrationTest {

    /** 本机 MySQL 主机；可用 YILIAN_TEST_DB_HOST 覆盖，默认只连接 localhost。 */
    private static final String MYSQL_HOST = env("YILIAN_TEST_DB_HOST", "localhost");

    /** 本机 MySQL 端口。 */
    private static final int MYSQL_PORT = Integer.parseInt(env("YILIAN_TEST_DB_PORT", "3306"));

    /** 本机 MySQL 测试账号；CI/其他机器可用 YILIAN_TEST_DB_USERNAME 覆盖。 */
    private static final String MYSQL_USERNAME = env("YILIAN_TEST_DB_USERNAME", "root");

    /** 本机 MySQL 测试密码；CI/其他机器可用 YILIAN_TEST_DB_PASSWORD 覆盖。 */
    private static final String MYSQL_PASSWORD = env("YILIAN_TEST_DB_PASSWORD", "123456");

    /** 独立测试库，避免污染开发库 {@code yilian}。 */
    private static final String TEST_DATABASE = env("YILIAN_TEST_DB_NAME", "yilian_test");

    /** 与 application-local.yml 一致的连接参数，保证 UTC 与时区行为一致。 */
    private static final String MYSQL_PARAMETERS =
            "useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC"
                    + "&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true"
                    + "&useSSL=false&connectTimeout=5000&socketTimeout=10000";

    /** 指向独立测试库的 JDBC URL，由 {@link #datasource} 注入 Spring 环境。 */
    private static final String TEST_DATABASE_URL =
            "jdbc:mysql://"
                    + MYSQL_HOST
                    + ":"
                    + MYSQL_PORT
                    + "/"
                    + TEST_DATABASE
                    + "?"
                    + MYSQL_PARAMETERS;

    /** bootstrap 管理员用户名；测试间清理时刻意保留该账号。 */
    private static final String ADMIN_USERNAME = "admin";

    /** 测试用 bootstrap 管理员密码，每次用例前写回，避免上次运行的旧密码污染测试。 */
    private static final String TEST_ADMIN_PASSWORD = "test-admin-password";

    static {
        createTestDatabase();
    }

    @Autowired private Environment environment;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private ErrorLogRecorder errorLogRecorder;

    /**
     * 读取可覆盖的测试环境变量，未设置时使用本机默认值。
     *
     * @param name 环境变量名
     * @param fallback 默认值
     * @return 非空环境变量值或默认值
     */
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String baseUrl;

    /**
     * 在 Spring 容器启动前创建独立测试库。
     *
     * <p>连接失败会抛出异常，使测试明确失败而不是跳过；类 Javadoc 已说明前置条件。
     */
    private static void createTestDatabase() {
        String serverUrl =
                "jdbc:mysql://" + MYSQL_HOST + ":" + MYSQL_PORT + "/?" + MYSQL_PARAMETERS;
        try (Connection connection =
                        DriverManager.getConnection(serverUrl, MYSQL_USERNAME, MYSQL_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE DATABASE IF NOT EXISTS `"
                            + TEST_DATABASE
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "无法连接本机 MySQL（"
                            + MYSQL_HOST
                            + ":"
                            + MYSQL_PORT
                            + "，账号 "
                            + MYSQL_USERNAME
                            + "）或无法创建测试库 "
                            + TEST_DATABASE
                            + "；集成测试要求本机 MySQL 已启动且测试凭据可用，不会跳过",
                    exception);
        }
    }

    /**
     * 把数据源固定到本机独立测试库，覆盖 application-local.yml 中的开发库配置。
     *
     * @param registry Spring 动态属性注册表
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
        registry.add("spring.datasource.username", () -> MYSQL_USERNAME);
        registry.add("spring.datasource.password", () -> MYSQL_PASSWORD);
    }

    /**
     * 记录随机端口并清理上一个用例留下的会话、临时账号与管理员凭据，保证测试可重复执行。
     *
     * <p>{@code yilian_test} 会跨次运行保留数据；若只保留 admin 不修复其密码，上一次运行 遗留的哈希会让登录用例失败。因此这里把 admin
     * 的密码重置为测试值，并确保其启用且为 管理员角色，但不删除该账号（bootstrap 负责创建它）。
     */
    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
        // 先删属性表再删会话表，避免触发 spring_session_attributes 的外键约束。
        jdbcTemplate.update("DELETE FROM spring_session_attributes");
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM error_log");
        jdbcTemplate.update("DELETE FROM operation_log");
        // 保留 bootstrap 创建的 admin；其他账号（如本类测试用的 bob）全部清理。
        jdbcTemplate.update("DELETE FROM app_user WHERE username <> ?", ADMIN_USERNAME);

        int adminUpdated =
                jdbcTemplate.update(
                        "UPDATE app_user SET password_hash = ?, role = 'ADMIN', enabled = b'1'"
                                + " WHERE username = ?",
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

    @Test
    void adminPathRejectsRegularUser() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO app_user (username, password_hash, role, enabled, created_at, updated_at)"
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

    private HttpClient newClient() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static final Pattern CSRF_TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

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
                    .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
                    .collect(Collectors.joining(",", "{", "}"));
        }
        throw new IllegalArgumentException("unsupported test payload");
    }
}
