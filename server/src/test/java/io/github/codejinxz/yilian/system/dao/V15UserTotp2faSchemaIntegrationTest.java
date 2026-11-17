package io.github.codejinxz.yilian.system.dao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.support.TestDatabases;
import io.github.codejinxz.yilian.system.entity.User2faRecoveryCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * V15 迁移落库形状与 {@link User2faRecoveryCodeDao} 读写链路集成测试（真实 MySQL，Flyway V1→V15 自动跑）[AC-3]。
 *
 * <p>锁定：users 三列形状（可空性/默认值）、恢复码表形状（列/主键/索引）、恢复码「使用落 used_at 不删行」的 append-only 语义、按用户清行的删除语义。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class V15UserTotp2faSchemaIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private User2faRecoveryCodeDao recoveryCodeDao;

    @Autowired private AppUserDao appUserDao;

    static {
        TestDatabases.ensureDatabase();
    }

    /** [AC-3] users 三列形状：密文可空、开关 NOT NULL 默认 FALSE、启用时刻可空。 */
    @Test
    @DisplayName("[AC-3] users 三列形状锁定")
    void ac3_usersColumnsShapeLocked() {
        List<String> columnNames =
                jdbcTemplate.queryForList(
                        "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'"
                                + " AND COLUMN_NAME IN ('totp_secret_cipher','totp_enabled','totp_enrolled_at')"
                                + " ORDER BY ORDINAL_POSITION",
                        String.class);
        assertThat(columnNames)
                .containsExactly("totp_secret_cipher", "totp_enabled", "totp_enrolled_at");
        java.util.Map<String, Object> column =
                jdbcTemplate.queryForMap(
                        "SELECT IS_NULLABLE, DATA_TYPE, COLUMN_TYPE FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'"
                                + " AND COLUMN_NAME = 'totp_secret_cipher'");
        assertThat(column.get("IS_NULLABLE")).isEqualTo("YES");
        assertThat(column.get("DATA_TYPE")).isEqualTo("varchar");
        java.util.Map<String, Object> enabled =
                jdbcTemplate.queryForMap(
                        "SELECT IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'"
                                + " AND COLUMN_NAME = 'totp_enabled'");
        assertThat(enabled.get("IS_NULLABLE")).isEqualTo("NO");
        // MySQL information_schema 对 BOOLEAN DEFAULT FALSE 回显 "0"（tinyint(1) 的默认值形态）。
        assertThat(String.valueOf(enabled.get("COLUMN_DEFAULT"))).isEqualTo("0");
    }

    /** [AC-3] 恢复码表形状：五列齐全、主键 id、user_id 索引存在。 */
    @Test
    @DisplayName("[AC-3] user_2fa_recovery_codes 表形状锁定")
    void ac3_recoveryTableShapeLocked() {
        List<java.util.Map<String, Object>> columns =
                jdbcTemplate.queryForList(
                        "SELECT COLUMN_NAME, IS_NULLABLE, DATA_TYPE FROM"
                                + " information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                                + " AND TABLE_NAME = 'user_2fa_recovery_codes'"
                                + " ORDER BY ORDINAL_POSITION");
        assertThat(columns)
                .extracting(row -> row.get("COLUMN_NAME"))
                .containsExactly("id", "user_id", "code_hash", "used_at", "created_at");
        assertThat(columns.get(1).get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(columns.get(2).get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(columns.get(3).get("IS_NULLABLE")).isEqualTo("YES");
        assertThat(columns.get(4).get("IS_NULLABLE")).isEqualTo("NO");
        Long indexCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.STATISTICS"
                                + " WHERE TABLE_SCHEMA = DATABASE()"
                                + " AND TABLE_NAME = 'user_2fa_recovery_codes'"
                                + " AND INDEX_NAME = 'idx_2fa_recovery_user' AND COLUMN_NAME = 'user_id'",
                        Long.class);
        assertThat(indexCount).as("user_id 索引存在").isEqualTo(1L);
    }

    /** [AC-3] Dao 往返：插入未用行 → 使用落 used_at 不删行 → 按用户清行（派生删除需事务）。 */
    @Test
    @org.springframework.transaction.annotation.Transactional
    @DisplayName("[AC-3] 恢复码 Dao 使用与清行语义")
    void ac3_recoveryDaoUseAndClearSemantics() {
        jdbcTemplate.update("DELETE FROM user_2fa_recovery_codes WHERE user_id = -7");
        Instant created = Instant.now().truncatedTo(ChronoUnit.MICROS);
        User2faRecoveryCode first =
                recoveryCodeDao.save(User2faRecoveryCode.unused(-7L, "$2a$10$hash-a", created));
        recoveryCodeDao.save(User2faRecoveryCode.unused(-7L, "$2a$10$hash-b", created));

        List<User2faRecoveryCode> unused = recoveryCodeDao.findUnusedByUserId(-7L);
        assertThat(unused).hasSize(2);
        assertThat(unused.get(0).getCodeHash()).isEqualTo("$2a$10$hash-a");
        assertThat(unused.get(0).getCreatedAt()).isEqualTo(created);

        // 使用语义：markUsed 落 used_at，行不消失、不再出现在未用集合。
        first.markUsed(created.plusSeconds(60));
        recoveryCodeDao.save(first);
        assertThat(recoveryCodeDao.findUnusedByUserId(-7L)).hasSize(1);
        Long usedRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_2fa_recovery_codes WHERE user_id = -7",
                        Long.class);
        assertThat(usedRows).as("使用后行保留（append-only）").isEqualTo(2L);

        // 清行语义：disable/reset-2fa 的物理删除（凭据投影，非历史抹除）。
        assertThat(recoveryCodeDao.deleteByUserId(-7L)).isEqualTo(2);
        assertThat(recoveryCodeDao.deleteByUserId(-7L)).isZero();
        assertThat(recoveryCodeDao.findUnusedByUserId(-7L)).isEmpty();
    }

    /** [AC-3] AppUser 实体三列映射与迁移方法：stage→activate→clear 全生命周期。 */
    @Test
    @DisplayName("[AC-3] AppUser 2FA 生命周期方法")
    void ac3_appUserLifecycleMethods() {
        jdbcTemplate.update(
                "INSERT INTO users (username, password_hash, role, enabled, password_changed,"
                        + " timezone, daily_goal, rating_standard, desired_retention, theme,"
                        + " notifications_enabled, created_at, updated_at)"
                        + " VALUES ('it-v15-entity', 'x', 'USER', b'1', b'1', 'Asia/Shanghai', 3,"
                        + " 'TEM8', 0.900, 'paper', b'0', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        Long userId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM users WHERE username = 'it-v15-entity'", Long.class);
        try {
            io.github.codejinxz.yilian.system.entity.AppUser user =
                    appUserDao.findById(userId).orElseThrow();
            assertThat(user.isTotpEnabled()).isFalse();
            assertThat(user.getTotpSecretCipher()).isNull();

            user.stageTotpSecret("cipher-1");
            user = appUserDao.save(user);
            assertThat(user.isTotpEnabled()).isFalse();
            assertThat(user.getTotpSecretCipher()).isEqualTo("cipher-1");

            Instant enrolled = Instant.now().truncatedTo(ChronoUnit.MICROS);
            user.activateTotp("cipher-2", enrolled);
            user = appUserDao.save(user);
            assertThat(user.isTotpEnabled()).isTrue();
            assertThat(user.getTotpSecretCipher()).isEqualTo("cipher-2");
            assertThat(user.getTotpEnrolledAt()).isEqualTo(enrolled);

            user.clearTotp();
            user = appUserDao.save(user);
            assertThat(user.isTotpEnabled()).isFalse();
            assertThat(user.getTotpSecretCipher()).isNull();
            assertThat(user.getTotpEnrolledAt()).isNull();
        } finally {
            jdbcTemplate.update("DELETE FROM user_2fa_recovery_codes WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }
}
