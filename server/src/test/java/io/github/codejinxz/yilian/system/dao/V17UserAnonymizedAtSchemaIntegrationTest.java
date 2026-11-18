package io.github.codejinxz.yilian.system.dao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.support.TestDatabases;
import io.github.codejinxz.yilian.system.entity.AppUser;

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
 * V17 迁移落库形状与 {@link AppUser#anonymize} 匿名化链路集成测试（真实 MySQL，Flyway V1→V17 自动跑）[AC-1]。
 *
 * <p>锁定：anonymized_at 列形状（可空 DATETIME(6)、NULL 默认）、实体 anonymize() 全生命周期 （身份字段处置 + 设置回默认 + 落值）与 Dao
 * 往返一致。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class V17UserAnonymizedAtSchemaIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private AppUserDao appUserDao;

    static {
        TestDatabases.ensureDatabase();
    }

    /** [AC-1] anonymized_at 列形状：可空、DATETIME(6)、NULL 默认、紧跟 deleted_by。 */
    @Test
    @DisplayName("[AC-1] users.anonymized_at 列形状锁定")
    void ac1_anonymizedAtColumnShapeLocked() {
        Map<String, Object> column =
                jdbcTemplate.queryForMap(
                        "SELECT IS_NULLABLE, DATA_TYPE, DATETIME_PRECISION, COLUMN_DEFAULT,"
                                + " ORDINAL_POSITION FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'"
                                + " AND COLUMN_NAME = 'anonymized_at'");
        assertThat(column.get("IS_NULLABLE")).isEqualTo("YES");
        assertThat(column.get("DATA_TYPE")).isEqualTo("datetime");
        assertThat(((Number) column.get("DATETIME_PRECISION")).intValue()).isEqualTo(6);
        assertThat(column.get("COLUMN_DEFAULT")).isNull();
        // 位置紧跟 deleted_by（V17 明确 AFTER deleted_by）：迁移可回滚重放，列序稳定。
        Map<String, Object> deletedBy =
                jdbcTemplate.queryForMap(
                        "SELECT ORDINAL_POSITION FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'"
                                + " AND COLUMN_NAME = 'deleted_by'");
        assertThat(((Number) column.get("ORDINAL_POSITION")).intValue())
                .isEqualTo(((Number) deletedBy.get("ORDINAL_POSITION")).intValue() + 1);
    }

    /** [AC-1] 实体链路：软删 → 匿名化（身份字段处置 + 设置回默认）→ Dao 往返落值一致。 */
    @Test
    @DisplayName("[AC-1] AppUser 匿名化生命周期与 Dao 往返")
    void ac1_appUserAnonymizeRoundTrip() {
        jdbcTemplate.update(
                "INSERT INTO users (username, password_hash, role, enabled, password_changed,"
                        + " timezone, daily_goal, rating_standard, desired_retention, theme,"
                        + " notifications_enabled, totp_secret_cipher, totp_enabled,"
                        + " totp_enrolled_at, deleted_at, created_at, updated_at)"
                        + " VALUES ('it-v17-learner', ?, 'USER', b'1', b'1', 'UTC', 5, 'IELTS',"
                        + " 0.850, 'night', b'1', 'cipher-v17', b'1', UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                "$2a$10$storedhashplaceholderstoredhashplaceholder12");
        Long userId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM users WHERE username = 'it-v17-learner'", Long.class);
        try {
            AppUser user = appUserDao.findById(userId).orElseThrow();
            assertThat(user.getAnonymizedAt()).isNull();

            Instant anonymizedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
            user.anonymize(userId, anonymizedAt);
            AppUser saved = appUserDao.save(user);

            assertThat(saved.getUsername()).isEqualTo("deleted_user_" + userId);
            assertThat(saved.getEmail()).isNull();
            assertThat(saved.getPasswordHash()).isEqualTo(AppUser.ANONYMIZED_PASSWORD_HASH);
            assertThat(saved.getTimezone()).isEqualTo("Asia/Shanghai");
            assertThat(saved.getDailyGoal()).isEqualTo((short) 3);
            assertThat(saved.getRatingStandard()).isEqualTo("TEM8");
            assertThat(saved.getTheme()).isEqualTo("paper");
            assertThat(saved.isNotificationsEnabled()).isFalse();
            assertThat(saved.isTotpEnabled()).isFalse();
            assertThat(saved.getTotpSecretCipher()).isNull();
            assertThat(saved.getTotpEnrolledAt()).isNull();
            assertThat(saved.getAnonymizedAt()).isEqualTo(anonymizedAt);
            // 原始 BCrypt 哈希格式不匹配 → 不可登录（matches false）。
            org.springframework.security.crypto.password.PasswordEncoder encoder =
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
            assertThat(encoder.matches("anything", saved.getPasswordHash())).isFalse();

            // Dao 重读：落库值一致（DATETIME(6) 截断微秒）。
            AppUser reloaded = appUserDao.findById(userId).orElseThrow();
            assertThat(reloaded.getAnonymizedAt()).isEqualTo(anonymizedAt);
            assertThat(reloaded.getUsername()).isEqualTo("deleted_user_" + userId);
        } finally {
            jdbcTemplate.update("DELETE FROM user_2fa_recovery_codes WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }
}
