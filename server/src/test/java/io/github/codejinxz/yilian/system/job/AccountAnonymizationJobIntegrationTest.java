package io.github.codejinxz.yilian.system.job;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 账号匿名化日调度集成测试（真实 MySQL，Flyway V1→V17）[AC-4]。
 *
 * <p>锁定：到期口径（deleted_at+30d 且 anonymized_at IS NULL）、身份字段处置（username 占位/ email
 * 置空/密码哈希无效常量/设置回默认/TOTP 清）、恢复码删行、会话按软删时的用户名吊销、 学习记录（attempts）不动、幂等重入零变更。到期时刻经 {@link
 * AccountAnonymizationJob#anonymizationCutoff()} （构造器注入的 Clock，生产为 systemUTC）回溯 SQL 夹具的 deleted_at
 * 模拟 31 天前。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class AccountAnonymizationJobIntegrationTest {

    /** 恰好到期的软删时刻（截止时刻 - 1 天，落在扫描窗口内）。 */
    private static final String SQL_DELETED_31D_AGO = "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 31 DAY)";

    /** 未到期的软删时刻（截止时刻 + 20 天）。 */
    private static final String SQL_DELETED_10D_AGO = "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 DAY)";

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private AccountAnonymizationJob job;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 清理夹具：临时账号、本人域学习记录与 spring_session 行（先摘 append-only 触发器再删行，删后复原——e2e 先例）。 */
    @BeforeEach
    void setUp() {
        dropAppendOnlyTriggers();
        jdbcTemplate.update("DELETE FROM spring_session_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM user_2fa_recovery_codes");
        jdbcTemplate.update("DELETE FROM attempt_gradings");
        jdbcTemplate.update("DELETE FROM attempts");
        jdbcTemplate.update("DELETE FROM users WHERE username NOT IN ('admin', '游客')");
        restoreAppendOnlyTriggers();
    }

    /** 摘除练习五表的 append-only 触发器（清理用；测试结束前必须复原）。 */
    private void dropAppendOnlyTriggers() {
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempts_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempts_no_delete");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempt_gradings_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempt_gradings_no_delete");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_review_logs_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_review_logs_no_delete");
    }

    /** 复原练习五表的 append-only 触发器（与 V8 定义逐字一致）。 */
    private void restoreAppendOnlyTriggers() {
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
    }

    /** [AC-4] 到期账号匿名化：身份处置 + 学习记录不动 + 会话吊销；未到期与已匿名化零变更。 */
    @Test
    @DisplayName("[AC-4] 到期匿名化主链路与幂等重入")
    void ac4_anonymizesDueUserOnlyAndIsReentrant() {
        insertDeletedUser("due-learner", SQL_DELETED_31D_AGO, true);
        insertDeletedUser("recent-learner", SQL_DELETED_10D_AGO, true);
        Long dueId = userIdOf("due-learner");
        Long recentId = userIdOf("recent-learner");
        insertAttempt("c0000000-0000-0000-0000-00000000000a", dueId);
        insertAttempt("c0000000-0000-0000-0000-00000000000b", recentId);
        insertSession("spring-session-due-1", "due-learner");
        insertSession("spring-session-recent-1", "recent-learner");

        int processed = job.anonymizeDueUsers();

        assertThat(processed).isEqualTo(1);
        Map<String, Object> due = userRow(dueId);
        assertThat(due.get("username")).isEqualTo("deleted_user_" + dueId);
        assertThat(due.get("email")).isNull();
        assertThat(due.get("password_hash")).isEqualTo("!yilian-anonymized-invalid-credential!");
        assertThat(due.get("timezone")).isEqualTo("Asia/Shanghai");
        assertThat(((Number) due.get("daily_goal")).intValue()).isEqualTo(3);
        assertThat(due.get("rating_standard")).isEqualTo("TEM8");
        assertThat(due.get("theme")).isEqualTo("paper");
        assertThat(due.get("totp_enabled")).isEqualTo(Boolean.FALSE);
        assertThat(due.get("totp_secret_cipher")).isNull();
        assertThat(due.get("anonymized_at")).isNotNull();
        // 学习记录 append-only 不动。
        assertThat(attemptCount(dueId)).isEqualTo(1);
        // 会话按软删时的用户名吊销。
        assertThat(sessionCount("due-learner")).isZero();
        // 未到期账号零变更。
        Map<String, Object> recent = userRow(recentId);
        assertThat(recent.get("username")).isEqualTo("recent-learner");
        assertThat(recent.get("anonymized_at")).isNull();
        assertThat(sessionCount("recent-learner")).isEqualTo(1);
        assertThat(attemptCount(recentId)).isEqualTo(1);

        // 幂等重入：第二次调度零变更（anonymized_at 不被改写）。
        Object firstAnonymizedAt = due.get("anonymized_at");
        int second = job.anonymizeDueUsers();
        assertThat(second).isZero();
        assertThat(userRow(dueId).get("anonymized_at")).isEqualTo(firstAnonymizedAt);
        assertThat(userRow(recentId).get("username")).isEqualTo("recent-learner");
    }

    /** [AC-4] 截止时刻=时钟-冷静期（默认 30 天）：恰好等于截止的 deleted_at 视为到期。 */
    @Test
    @DisplayName("[AC-4] 截止时刻口径与冷静期配置")
    void ac4_cutoffMatchesClockMinusGraceDays() {
        assertThat(job.graceDays()).isEqualTo(30);
        Instant cutoff = job.anonymizationCutoff();
        Instant now = Instant.now();
        // 允许执行耗时造成的毫秒级漂移；语义=now-30d。
        assertThat(cutoff)
                .isBetween(
                        now.minus(30, ChronoUnit.DAYS).minusSeconds(5),
                        now.minus(30, ChronoUnit.DAYS).plusSeconds(5));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 直插软删用户夹具：个性化设置 + 2FA 注册态 + 恢复码行（deleted_by 为 NULL 占位—— 真实链路由管理员删号或自助删号端点落值，匿名化不读该列）。
     *
     * @param username 用户名
     * @param deletedAtSql 软删时刻的 SQL 表达式
     * @param withTotp 是否注册 2FA
     */
    private void insertDeletedUser(String username, String deletedAtSql, boolean withTotp) {
        jdbcTemplate.update(
                "INSERT INTO users (username, email, password_hash, role, enabled,"
                        + " password_changed, timezone, daily_goal, rating_standard,"
                        + " desired_retention, theme, notifications_enabled, totp_secret_cipher,"
                        + " totp_enabled, totp_enrolled_at, deleted_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'USER', b'1', b'1', 'UTC', 5, 'IELTS', 0.850,"
                        + " 'night', b'1', ?, b'1', UTC_TIMESTAMP(6), "
                        + deletedAtSql
                        + ","
                        + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                username,
                username + "@example.com",
                "$2a$10$storedhashplaceholderstoredhashplaceholder12",
                withTotp ? "cipher-job-fixture" : null);
        jdbcTemplate.update(
                "INSERT INTO user_2fa_recovery_codes (user_id, code_hash, created_at)"
                        + " VALUES (?, ?, UTC_TIMESTAMP(6))",
                userIdOf(username),
                "$2a$10$recoveryhashplaceholderrecoveryhashplace12");
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
     * 直插一条 attempt。
     *
     * @param attemptId 提交 id
     * @param userId 用户 id
     */
    private void insertAttempt(String attemptId, Long userId) {
        jdbcTemplate.update(
                "INSERT INTO attempts (id, user_id, sentence_id, sentence_version_id,"
                        + " question_version, mode, kind, answer, answer_hash, hints_used,"
                        + " created_at)"
                        + " VALUES (?, ?, '01ARZ3NDEKTSV4RRFFQ69G5FAV', 1, 1, 'CLOZE', 'FIRST',"
                        + " '{\"slots\":[]}', ?, 0, UTC_TIMESTAMP(6))",
                attemptId,
                userId,
                "a".repeat(64));
    }

    /**
     * 直插一条 spring_session 行（会话吊销断言用）。
     *
     * @param sessionId 会话 id
     * @param principalName principal（登录用户名）
     */
    private void insertSession(String sessionId, String principalName) {
        jdbcTemplate.update(
                "INSERT INTO spring_session (PRIMARY_ID, SESSION_ID, CREATION_TIME,"
                        + " LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME)"
                        + " VALUES (?, ?, ?, ?, 1800, ?, ?)",
                sessionId,
                sessionId,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                System.currentTimeMillis() + 1_800_000L,
                principalName);
    }

    /**
     * 读用户整行。
     *
     * @param userId 用户 id
     * @return 行
     */
    private Map<String, Object> userRow(Long userId) {
        return jdbcTemplate.queryForMap(
                "SELECT username, email, password_hash, timezone, daily_goal, rating_standard,"
                        + " theme, totp_enabled, totp_secret_cipher, anonymized_at"
                        + " FROM users WHERE id = ?",
                userId);
    }

    /**
     * 用户 attempt 数。
     *
     * @param userId 用户 id
     * @return 行数
     */
    private Integer attemptCount(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE user_id = ?", Integer.class, userId);
    }

    /**
     * 某 principal 的会话行数。
     *
     * @param principalName principal
     * @return 行数
     */
    private Integer sessionCount(String principalName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM spring_session WHERE PRINCIPAL_NAME = ?",
                Integer.class,
                principalName);
    }
}
