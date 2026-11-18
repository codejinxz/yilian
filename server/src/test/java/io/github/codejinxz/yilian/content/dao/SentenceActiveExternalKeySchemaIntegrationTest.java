package io.github.codejinxz.yilian.content.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.practice.dao.LlmUsageDao;
import io.github.codejinxz.yilian.practice.entity.LlmUsage;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * V11 迁移落库形状与「仅活跃行唯一」语义集成测试（真实 MySQL，Flyway V1→V11 自动跑）。
 *
 * <p>核心语义：{@code uk_sentence_active_external (bank_id, active_external_key)} 只约束未删行——
 * 回收行（deleted_at 非空）的 active_external_key 恒为 NULL，唯一索引对 NULL 不去重， 因此批次 回滚后同文件重导放行 （V11
 * 迁移的设计目标）；活跃行互斥不变，作为并发导入的数据库兜底。 同时验证 llm_usage 表形状与 Dao 落库链路（实体映射由 Hibernate ddl-auto=validate
 * 在上下文 启动时校验，此处补真实读写）。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false",
            "yilian.import.reconcile-enabled=false"
        })
@ActiveProfiles("test")
class SentenceActiveExternalKeySchemaIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 5, 12, 0, 0));

    private static final long BANK_ID = 9101L;

    /** CHAR(26) ULID 形状的夹具 id（pad26 补齐，长度断言见构造器语义）。 */
    private static final String SENTENCE_ID_A = pad26("01JA");

    private static final String SENTENCE_ID_B = pad26("01JB");

    /** 指纹夹具：两行互异，避免撞 uk_sentence_bank_active_fingerprint（本类只考察 external_key 维度）。 */
    private static final String FINGERPRINT_A = "a".repeat(64);

    private static final String FINGERPRINT_B = "b".repeat(64);

    /**
     * 把前缀补齐为 26 位（ULID 形状），满足 sentences.id CHAR(26) 与 llm_usage.sentence_id 外形。
     *
     * @param prefix 短前缀
     * @return 26 位 id
     */
    private static String pad26(String prefix) {
        return (prefix + "0".repeat(26)).substring(0, 26);
    }

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private LlmUsageDao llmUsageDao;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 播种测试题库并清掉本类用到的句子行。 */
    @BeforeEach
    void seed() {
        clean();
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (?, NULL, ?, NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL, NULL, ?, ?, NULL, NULL)",
                BANK_ID,
                "V11 schema 集成测试题库",
                T0,
                T0,
                T0);
    }

    /** 清理句子行与用量行；题库行随用例保留（幂等播种）。 */
    @AfterEach
    void clean() {
        jdbcTemplate.update(
                "UPDATE sentences SET current_version_id = NULL WHERE bank_id = ?", BANK_ID);
        jdbcTemplate.update("DELETE FROM sentences WHERE bank_id = ?", BANK_ID);
        jdbcTemplate.update("DELETE FROM llm_usage WHERE scene = 'schema-it'");
        jdbcTemplate.update("DELETE FROM sentence_banks WHERE id = ?", BANK_ID);
    }

    /** [AC-V11-1] 迁移形状：生成列在位（STORED）、新唯一键两列齐、旧全行唯一键已删。 */
    @Test
    @DisplayName("[AC-V11-1] V11 形状：active_external_key 生成列 + 仅活跃行唯一键替换旧键")
    void migrationShape_v11() {
        String columnExtra =
                jdbcTemplate.queryForObject(
                        "SELECT EXTRA FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'"
                                + " AND COLUMN_NAME = 'active_external_key'",
                        String.class);
        assertThat(columnExtra)
                .as("生成列声明（MySQL 8 EXTRA 形如 STORED GENERATED）")
                .contains("GENERATED")
                .contains("STORED");

        Integer newKeyColumns =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.STATISTICS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'"
                                + " AND INDEX_NAME = 'uk_sentence_active_external'"
                                + " AND COLUMN_NAME IN ('bank_id', 'active_external_key')",
                        Integer.class);
        assertThat(newKeyColumns).as("新唯一键覆盖 (bank_id, active_external_key)").isEqualTo(2);

        Integer nonUnique =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.STATISTICS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'"
                                + " AND INDEX_NAME = 'uk_sentence_active_external'"
                                + " AND NON_UNIQUE = 1",
                        Integer.class);
        assertThat(nonUnique).as("新键必须是唯一索引").isZero();

        Integer oldKeyRemaining =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.STATISTICS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'"
                                + " AND INDEX_NAME = 'uk_sentence_bank_external'",
                        Integer.class);
        assertThat(oldKeyRemaining).as("旧全行唯一键已删除").isZero();
    }

    /** [AC-V11-2] 回滚后重导的核心语义：回收行同键占用释放，活跃行同键插入成功。 */
    @Test
    @DisplayName("[AC-V11-2] 回收行不挡同键新活跃行（回滚后重导放行）")
    void recycledRowDoesNotBlockSameKeyActiveRow_v11() {
        insertSentence(SENTENCE_ID_A, "k-001");
        // 模拟批次回滚：软删后 external_key 不释放，但 active_external_key 归 NULL。
        jdbcTemplate.update(
                "UPDATE sentences SET deleted_at = ?, deleted_by = 9, status = 'RECYCLED'"
                        + " WHERE id = ?",
                T0,
                SENTENCE_ID_A);

        assertThatCode(() -> insertSentence(SENTENCE_ID_B, "k-001"))
                .as("同键新活跃行必须放行（重导同文件成功）")
                .doesNotThrowAnyException();

        Long activeWithKey =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE bank_id = ?"
                                + " AND active_external_key = 'k-001'",
                        Long.class,
                        BANK_ID);
        assertThat(activeWithKey).isEqualTo(1);
    }

    /** [AC-V11-2] 多个回收行同键共存：唯一索引对 NULL 不去重，回滚可重复发生。 */
    @Test
    @DisplayName("[AC-V11-2] 两个回收行同键共存")
    void multipleRecycledRowsMayShareKey_v11() {
        // 两个活跃行同键不可共存（本就是唯一键要拦的），逐行「入库→回收」模拟两次导入两次回滚。
        insertSentence(SENTENCE_ID_A, "k-001");
        jdbcTemplate.update(
                "UPDATE sentences SET deleted_at = ?, deleted_by = 9, status = 'RECYCLED'"
                        + " WHERE id = ?",
                T0,
                SENTENCE_ID_A);
        insertSentence(SENTENCE_ID_B, "k-001");
        jdbcTemplate.update(
                "UPDATE sentences SET deleted_at = ?, deleted_by = 9, status = 'RECYCLED'"
                        + " WHERE id = ?",
                T0,
                SENTENCE_ID_B);

        Long recycled =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE bank_id = ? AND status = 'RECYCLED'",
                        Long.class,
                        BANK_ID);
        assertThat(recycled).as("两行都进回收站，无唯一键阻拦").isEqualTo(2);
    }

    /** [AC-V11-3] 活跃行互斥回归：同 (bank_id, active_external_key) 第二条活跃行被唯一键拒绝。 */
    @Test
    @DisplayName("[AC-V11-3] 活跃行同键第二行仍被唯一键拒绝（并发导入兜底不回归）")
    void activeDuplicateKeyStillRejected_v11() {
        insertSentence(SENTENCE_ID_A, "k-001");

        assertThatThrownBy(() -> insertSentence(SENTENCE_ID_B, "k-001"))
                .as("并发兜底：活跃行重键必须撞唯一键")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** [AC-V11-4] llm_usage 形状与落库链路：Dao 写入真实表，读回字段齐全。 */
    @Test
    @DisplayName("[AC-V11-4] llm_usage 落库形状与读回")
    void llmUsageInsertAndReadBack_v11() {
        LlmUsage saved =
                llmUsageDao.save(
                        LlmUsage.of(
                                9L,
                                SENTENCE_ID_A,
                                "schema-it",
                                "openai-chat",
                                "glm-4.7",
                                12,
                                34,
                                120L,
                                null,
                                true,
                                Instant.now()));

        Integer rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM llm_usage WHERE id = ? AND user_id = 9"
                                + " AND sentence_id = ? AND scene = 'schema-it'"
                                + " AND protocol = 'openai-chat' AND model = 'glm-4.7'"
                                + " AND prompt_tokens = 12 AND completion_tokens = 34"
                                + " AND duration_ms = 120 AND ok = 1",
                        Integer.class,
                        saved.getId(),
                        SENTENCE_ID_A);
        assertThat(rows).as("全字段如实落库（含 BOOLEAN 到 bit 的映射）").isEqualTo(1);
    }

    /**
     * 插入一条活跃题目行（只填唯一键相关与必填列，其余走默认值；指纹按 id 取互异夹具）。
     *
     * @param sentenceId 题目 ULID
     * @param externalKey 导入源 key
     */
    private void insertSentence(String sentenceId, String externalKey) {
        String fingerprint = SENTENCE_ID_A.equals(sentenceId) ? FINGERPRINT_A : FINGERPRINT_B;
        jdbcTemplate.update(
                "INSERT INTO sentences (id, bank_id, external_key, status, fingerprint,"
                        + " created_by, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'PUBLISHED', ?, 9, ?, ?)",
                sentenceId,
                BANK_ID,
                externalKey,
                fingerprint,
                T0,
                T0);
    }
}
