package io.github.codejinxz.yilian.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * spring_session_attributes 孤儿清理集成测试 [AC-V11-1]（真实 MySQL，V11 后无物理外键）。
 *
 * <p>反连接 DELETE 是 MySQL 多表语法，Mock 单测锁不住执行面——本测试播种「有归属的属性行 + 孤儿属性行」后真跑 {@link
 * PartitionMaintenanceJob#purgeOrphanSessionAttributes()}， 验证：孤儿 行被删、有归属行保留、重复执行幂等（可重入语义的数据库级实证）。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class PartitionMaintenanceJobIntegrationTest {

    private static final String LIVE_SESSION = "it-live-session-primary";
    private static final String GONE_SESSION = "it-orphan-session-primary";

    @Autowired private PartitionMaintenanceJob job;

    @Autowired private JdbcTemplate jdbcTemplate;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 清场并播种一个存活会话（带属性行）。 */
    @BeforeEach
    void seed() {
        clean();
        insertSession(LIVE_SESSION, "it-live-session-id");
        insertAttribute(LIVE_SESSION, "attr");
    }

    /** 清理本类播种的会话与属性行（含清理后残留）。 */
    @AfterEach
    void clean() {
        jdbcTemplate.update(
                "DELETE FROM spring_session_attributes WHERE SESSION_PRIMARY_ID IN (?, ?)",
                LIVE_SESSION,
                GONE_SESSION);
        jdbcTemplate.update(
                "DELETE FROM spring_session WHERE PRIMARY_ID IN (?, ?)",
                LIVE_SESSION,
                GONE_SESSION);
    }

    /**
     * 插入一条属性行。
     *
     * @param sessionPrimaryId 归属会话主键
     * @param name 属性名
     */
    private void insertAttribute(String sessionPrimaryId, String name) {
        jdbcTemplate.update(
                "INSERT INTO spring_session_attributes (SESSION_PRIMARY_ID, ATTRIBUTE_NAME,"
                        + " ATTRIBUTE_BYTES)"
                        + " VALUES (?, ?, CONCAT('blob-', ?))",
                sessionPrimaryId,
                name,
                name);
    }

    /**
     * 插入一条不过期（远未来）的会话行。
     *
     * @param primaryId 会话主键
     * @param sessionId 会话 id
     */
    private void insertSession(String primaryId, String sessionId) {
        jdbcTemplate.update(
                "INSERT INTO spring_session (PRIMARY_ID, SESSION_ID, CREATION_TIME,"
                        + " LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME)"
                        + " VALUES (?, ?, 0, 0, 1800, 9000000000000000, NULL)",
                primaryId,
                sessionId);
    }

    /** [AC-V11-1] 孤儿属性行被清、有归属行保留；再跑一轮零命中（幂等）。 */
    @Test
    @DisplayName("[AC-V11-1] 反连接清理：孤儿删、活者留、重跑空转")
    void orphanAttributesPurgedLiveKeptAndIdempotent_v11() {
        // 模拟 V10 之后的残留路径：会话行被直接删掉，属性行成为孤儿。
        insertSession(GONE_SESSION, "it-orphan-session-id");
        insertAttribute(GONE_SESSION, "kept-while-alive");
        jdbcTemplate.update("DELETE FROM spring_session WHERE PRIMARY_ID = ?", GONE_SESSION);

        job.purgeOrphanSessionAttributes();

        Integer orphanLeft =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM spring_session_attributes"
                                + " WHERE SESSION_PRIMARY_ID = ?",
                        Integer.class,
                        GONE_SESSION);
        Integer liveLeft =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM spring_session_attributes"
                                + " WHERE SESSION_PRIMARY_ID = ? AND ATTRIBUTE_NAME = 'attr'",
                        Integer.class,
                        LIVE_SESSION);
        assertThat(orphanLeft).as("孤儿属性行必须被清空").isZero();
        assertThat(liveLeft).as("有归属的属性行不受影响").isEqualTo(1);

        // 幂等：再跑一轮是空转，不报错、不再删行。
        job.purgeOrphanSessionAttributes();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM spring_session_attributes"
                                        + " WHERE SESSION_PRIMARY_ID = ?",
                                Integer.class,
                                LIVE_SESSION))
                .isEqualTo(1);
    }
}
