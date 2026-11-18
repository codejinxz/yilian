package io.github.codejinxz.yilian.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 集成测试数据库基建：统一「幂等建库」与凭据读取。
 *
 * <p>为什么存在：全仓只有 AuthFlowIntegrationTest 曾在静态块建库，其余集成测试依赖 Surefire 的类执行顺序恰好让它先跑，单类运行或 CI 空实例上直接
 * {@code Unknown database} 失败；同时各测试类各自硬编码数据源，本机 root 口令被复制了 10 份。现在数据源统一由入库的 {@code
 * application-test.yml} 按环境变量提供，本类只负责 在 Spring 上下文启动前把测试库建出来。
 *
 * <p><strong>前置条件</strong>：可用的 MySQL 8 实例。凭据按「环境变量 &gt; 文档化 dev 默认值」 读取（与 application-test.yml
 * 同步）：未设置 {@code YILIAN_TEST_DB_*} 时回落到本机开发默认 {@code root / 123456}——该值是**文档化本机 dev 凭据，不是机密**，仅限
 * 本机开发，禁止复用到共享/生产环境；CI 必须用 {@code YILIAN_TEST_DB_PASSWORD} 覆盖注入。 MySQL 不可达时显式失败并给出可操作的提示——刻意不
 * skip，避免集成测试被静默跳过。
 */
public final class TestDatabases {

    /** 测试库主机；与 application-test.yml 的数据源 URL 同源。 */
    private static final String HOST = env("YILIAN_TEST_DB_HOST", "localhost");

    /** 测试库端口。 */
    private static final String PORT = env("YILIAN_TEST_DB_PORT", "3306");

    /** 测试库账号。 */
    private static final String USERNAME = env("YILIAN_TEST_DB_USERNAME", "root");

    /** 测试库口令；缺省回落到文档化的本机 dev 默认值（非机密，仅限本机开发，禁止复用到 共享/生产；CI 必须用环境变量覆盖注入随机口令）。 */
    private static final String PASSWORD = env("YILIAN_TEST_DB_PASSWORD", "123456");

    /** 测试库名。 */
    private static final String DATABASE = env("YILIAN_TEST_DB_NAME", "yilian_test");

    /** 与 application-test.yml 一致的连接参数（UTC 会话时区 + 显式超时，快速失败）。 */
    private static final String PARAMETERS =
            "useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC"
                    + "&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true"
                    + "&useSSL=false&connectTimeout=5000&socketTimeout=10000";

    private static final AtomicBoolean ENSURED = new AtomicBoolean(false);

    private TestDatabases() {}

    /**
     * 幂等创建测试库；同 JVM 内多个测试类共享，只建一次。
     *
     * <p>连接失败时抛出带修复指引的异常，让「环境没准备好」在类加载阶段显式失败， 而不是被误读为业务缺陷。
     *
     * @throws IllegalStateException MySQL 不可达或建库失败
     */
    public static void ensureDatabase() {
        if (!ENSURED.compareAndSet(false, true)) {
            return;
        }
        String serverUrl = "jdbc:mysql://" + HOST + ":" + PORT + "/?" + PARAMETERS;
        try (Connection connection = DriverManager.getConnection(serverUrl, USERNAME, PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE DATABASE IF NOT EXISTS `"
                            + DATABASE
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "无法连接 MySQL（"
                            + HOST
                            + ":"
                            + PORT
                            + "，账号 "
                            + USERNAME
                            + "）或无法创建测试库 "
                            + DATABASE
                            + "；本机默认按文档化 dev 凭据 root/123456 连接，"
                            + "可用 YILIAN_TEST_DB_* 环境变量覆盖（详见 docs/BOOTSTRAP.md §5）；"
                            + "集成测试不会跳过",
                    exception);
        }
    }

    /**
     * 读取可覆盖的环境变量，未设置或空白时用默认值。
     *
     * @param name 环境变量名
     * @param fallback 默认值
     * @return 非空环境变量值或默认值
     */
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
