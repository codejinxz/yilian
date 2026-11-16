package io.github.codejinxz.yilian.practice.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.practice.entity.LlmProviderKey;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * V14 迁移落库形状与 {@link LlmProviderKeyDao} 读写链路集成测试（真实 MySQL，Flyway V1→V14 自动跑）[AC-1]。
 *
 * <p>锁定：llm_provider_keys 表形状（列/主键/可空性）、密钥行 upsert（同 provider_id 覆盖）、 删除幂等、updated_at 留痕；密文列上无明文约定由
 * ProviderKeyCrypto 单测与解析链集成测试承担。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class V14LlmProviderKeysSchemaIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private LlmProviderKeyDao providerKeyDao;

    static {
        TestDatabases.ensureDatabase();
    }

    /** [AC-1] 表形状：四列齐全，provider_id 为主键，key_last4 可空（env 态无行，录入态有值）。 */
    @Test
    @DisplayName("[AC-1] V14 表形状锁定（列/主键/可空性）")
    void ac1_tableShapeLocked() {
        List<Map<String, Object>> columns =
                jdbcTemplate.queryForList(
                        "SELECT COLUMN_NAME, IS_NULLABLE, DATA_TYPE, COLUMN_KEY FROM"
                                + " information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                                + " AND TABLE_NAME = 'llm_provider_keys' ORDER BY ORDINAL_POSITION");
        assertThat(columns)
                .extracting(row -> row.get("COLUMN_NAME"))
                .containsExactly("provider_id", "secret_cipher", "key_last4", "updated_at");
        Map<String, Object> providerId = columns.get(0);
        assertThat(providerId.get("COLUMN_KEY")).isEqualTo("PRI");
        assertThat(providerId.get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(columns.get(1).get("DATA_TYPE")).isEqualTo("text");
        assertThat(columns.get(1).get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(columns.get(2).get("IS_NULLABLE")).isEqualTo("YES");
        assertThat(columns.get(3).get("IS_NULLABLE")).isEqualTo("NO");
    }

    /** [AC-1] 主键唯一：同 provider_id 重复插入被数据库拦截（一目录条目至多一把入库密钥）。 */
    @Test
    @DisplayName("[AC-1] provider_id 主键拦截重复行")
    void ac1_primaryKeyRejectsDuplicateProviderId() {
        // 先清残留（此前失败运行可能遗留），再落首行，对同主键重复插入触发冲突。
        jdbcTemplate.update("DELETE FROM llm_provider_keys WHERE provider_id = 'it-v14-shape'");
        jdbcTemplate.update(
                "INSERT INTO llm_provider_keys (provider_id, secret_cipher, key_last4,"
                        + " updated_at) VALUES ('it-v14-shape', 'x', '0000', UTC_TIMESTAMP(6))");
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO llm_provider_keys (provider_id,"
                                                + " secret_cipher, key_last4, updated_at) VALUES"
                                                + " ('it-v14-shape', 'y', '1111',"
                                                + " UTC_TIMESTAMP(6))"))
                .isInstanceOfSatisfying(
                        DataIntegrityViolationException.class,
                        exception -> assertThat(exception).hasStackTraceContaining("PRIMARY"));
        // 清理本用例写入的行（幂等：id 为测试专用）。
        jdbcTemplate.update("DELETE FROM llm_provider_keys WHERE provider_id = 'it-v14-shape'");
    }

    /** [AC-1] Dao upsert/查/删往返：密文与辨识号落库一致，updated_at 留痕，删除幂等。 */
    @Test
    @DisplayName("[AC-1] Dao upsert 读写删往返")
    void ac1_daoUpsertFindDeleteRoundTrip() {
        // 先清残留（此前失败运行可能遗留），保证本轮从空行开始。
        jdbcTemplate.update("DELETE FROM llm_provider_keys WHERE provider_id = 'it-v14-dao'");
        // DATETIME(6) 只存微秒：期望值先截断到微秒，避免纳秒精度在落库时被四舍五入的假失败。
        Instant firstWrite = Instant.now().truncatedTo(ChronoUnit.MICROS);
        providerKeyDao.upsert(new LlmProviderKey("it-v14-dao", "cipher-v1", "ab12", firstWrite));
        LlmProviderKey loaded = providerKeyDao.findByProviderId("it-v14-dao").orElseThrow();
        assertThat(loaded.getSecretCipher()).isEqualTo("cipher-v1");
        assertThat(loaded.getKeyLast4()).isEqualTo("ab12");
        assertThat(loaded.getUpdatedAt()).isEqualTo(firstWrite);

        // upsert 覆盖语义：同 provider_id 第二次写入替换密文/辨识号/时间，仍只有一行。
        Instant secondWrite = firstWrite.plusSeconds(60);
        providerKeyDao.upsert(new LlmProviderKey("it-v14-dao", "cipher-v2", "cd34", secondWrite));
        assertThat(providerKeyDao.findByProviderId("it-v14-dao").orElseThrow())
                .satisfies(
                        key -> {
                            assertThat(key.getSecretCipher()).isEqualTo("cipher-v2");
                            assertThat(key.getKeyLast4()).isEqualTo("cd34");
                            assertThat(key.getUpdatedAt()).isEqualTo(secondWrite);
                        });
        assertThat(providerKeyDao.findAllKeys())
                .extracting(LlmProviderKey::getProviderId)
                .containsOnlyOnce("it-v14-dao");

        // 删除：首删 true，再删幂等 false。
        assertThat(providerKeyDao.deleteByProviderId("it-v14-dao")).isTrue();
        assertThat(providerKeyDao.findByProviderId("it-v14-dao")).isEmpty();
        assertThat(providerKeyDao.deleteByProviderId("it-v14-dao")).isFalse();
    }

    /** [AC-1] 未录入条目查询为空（负路径），不抛异常。 */
    @Test
    @DisplayName("[AC-1] 未录入条目查询为空")
    void ac1_missingRowReturnsEmpty() {
        assertThat(providerKeyDao.findByProviderId("it-v14-nothing")).isEmpty();
        assertThat(providerKeyDao.deleteByProviderId("it-v14-nothing")).isFalse();
    }
}
