package io.github.codejinxz.yilian.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 集成测试数据库基建（016 P0 修复）：统一「幂等建库」与凭据读取。
 *
 * <p>为什么存在：全仓只有 AuthFlowIntegrationTest 曾在静态块建库，其余集成测试依赖 Surefire 的类执行顺序恰好让它先跑，单类运行或 CI 空实例上直接
 * {@code Unknown database} 失败（06 评审 #03）；同时各测试类各自硬编码数据源，本机 root 口令被复制了 10 份（06 评审
 * #01/#07）。现在数据源统一由入库的 {@code application-test.yml} 按环境变量提供，本类只负责 在 Spring 上下文启动前把测试库建出来。
 *
 * <p><strong>前置条件</strong>：可用的 MySQL 8 实例与 {@code YILIAN_TEST_DB_*} 环境变量
 * （口令必填，无默认值）。不满足时显式失败并给出可操作的提示——刻意不 skip，避免集成测试 被静默跳过。
 */
public final class TestDatabases {

    /** 测试库主机；与 application-test.yml 的数据源 URL 同源。 */
    private static final String HOST = env("YILIAN_TEST_DB_HOST", "localhost");

    /** 测试库端口。 */
    private static final String PORT = env("YILIAN_TEST_DB_PORT", "3306");

    /** 测试库账号。 */
    private static final String USERNAME = env("YILIAN_TEST_DB_USERNAME", "root");

    /** 测试库口令；无默认值，缺失时按「凭据不可用」显式失败。 */
    private static final String PASSWORD = System.getenv("YILIAN_TEST_DB_PASSWORD");

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
     * <p>连接失败或口令缺失时抛出带修复指引的异常，让「环境没准备好」在类加载阶段显式失败， 而不是被误读为业务缺陷。
     *
     * @throws IllegalStateException MySQL 不可达、凭据缺失或建库失败
     */
    public static void ensureDatabase() {
        if (!ENSURED.compareAndSet(false, true)) {
            return;
        }
        if (PASSWORD == null || PASSWORD.isBlank()) {
            throw new IllegalStateException(
                    "集成测试缺少 MySQL 口令：请设置环境变量 YILIAN_TEST_DB_PASSWORD"
                            + "（本机为 root 的真实口令，CI 由 workflow 注入），"
                            + "详见 docs/BOOTSTRAP.md §5；集成测试不会跳过");
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
                            + "；集成测试要求可用的 MySQL 与 YILIAN_TEST_DB_* 凭据"
                            + "（详见 docs/BOOTSTRAP.md §5），不会跳过",
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
