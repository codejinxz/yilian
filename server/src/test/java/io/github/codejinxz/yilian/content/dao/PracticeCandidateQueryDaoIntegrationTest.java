package io.github.codejinxz.yilian.content.dao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link PracticeCandidateQueryDao} 的 MyBatis 链路集成测试：真实 MySQL + XML 动态 SQL。
 *
 * <p>同时证明三件事：MapperScan 注册生效（AC-2）、{@code map-underscore-to-camel-case} 与 record 自动构造映射生效、SQL
 * 语义与原「两条 JPA 查询 + 内存匹配」逐条对拍一致（AC-1）。测试库为 {@code yilian_test}（与 AuthFlowIntegrationTest 同源的前置条件：本机
 * MySQL root/123456）， 每个用例前清空内容三表重新播种。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "management.health.redis.enabled=false",
            "yilian.security.jwt.secret=test-only-yilian-jwt-secret-0123456789abcdef"
        })
@ActiveProfiles("local")
class PracticeCandidateQueryDaoIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 10, 0, 0));
    private static final Timestamp T1 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 10, 0, 1));
    private static final Timestamp T2 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 10, 0, 2));

    private static final String CHUNKS = "[{\"index\":0,\"zh\":\"他\",\"answers\":[\"He\"]}]";
    private static final String BLANK = "[0]";
    private static final String TAGS = "[\"代词\"]";

    @Autowired private PracticeCandidateQueryDao practiceCandidateQueryDao;

    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 测试库指向本机 yilian_test，不读写开发库。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:mysql://localhost:3306/yilian_test?useUnicode=true&characterEncoding=utf8"
                                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                                + "&allowPublicKeyRetrieval=true&useSSL=false");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "123456");
    }

    /**
     * 清空内容三表，保证用例间幂等。
     *
     * <p>{@code sentence_versions} 带 append-only 触发器（V7），DELETE 被 SIGNAL 拒绝且与 {@code sentences}
     * 互为外键 RESTRICT，无法常规清理；测试库（yilian_test）可重置， 这里临时摘除两个触发器、清表后原样重建，保证测试结束后库内约束与生产一致。
     */
    @BeforeEach
    void clean() {
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_delete");
        // sentences 与 sentence_versions 互为 RESTRICT 外键：先断 current_version_id 指针再按子→父删除。
        jdbcTemplate.update("UPDATE sentences SET current_version_id = NULL");
        jdbcTemplate.update("DELETE FROM import_batch_rows");
        jdbcTemplate.update("DELETE FROM sentence_versions");
        jdbcTemplate.update("DELETE FROM sentences");
        jdbcTemplate.update("DELETE FROM import_batches");
        jdbcTemplate.update("DELETE FROM sentence_banks");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_sentence_versions_no_update BEFORE UPDATE ON sentence_versions"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'sentence_versions is immutable'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_sentence_versions_no_delete BEFORE DELETE ON sentence_versions"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'sentence_versions is immutable'");
    }

    /** [AC-1] 可见性与排序对拍：只出「发布题库×发布未删句子」，草稿/软删题库与快照缺失行丢弃，私有发布题库按原语义参与。 */
    @Test
    @DisplayName("[AC-1] 可见性过滤与排序与原实现一致")
    void ac1_visibilityAndOrderingParity() {
        insertBank(101, "PUBLISHED", "PUBLIC", null);
        insertBank(102, "PUBLISHED", "PUBLIC", T2);
        insertBank(103, "DRAFT", "PUBLIC", null);
        insertBank(104, "PUBLISHED", "PRIVATE", null);

        String s1 = insertPublishedSentence(101, 1, T0, null);
        String s2 = insertPublishedSentence(101, 2, T1, null);
        insertPublishedSentence(102, 3, T0, null);
        insertPublishedSentence(103, 4, T1, null);
        String s5 = insertPublishedSentence(104, 5, T2, null);
        insertSentenceWithoutSnapshot(101, 6, T2);

        List<PracticeCandidateRow> rows =
                practiceCandidateQueryDao.findCandidates(null, java.util.List.of(), null, 50);

        // 对拍原实现语义：软删题库（102）与草稿题库（103）的句子不出现；
        // 私有发布题库（104）的句子按原 JPA 查询语义参与候选（可见性由调用方约束，此处保持一致）。
        assertThat(rows).extracting(PracticeCandidateRow::sentenceId).containsExactly(s1, s2, s5);
        PracticeCandidateRow first = rows.getFirst();
        assertThat(first.bankId()).isEqualTo(101L);
        assertThat(first.versionNo()).isEqualTo(3);
        assertThat(first.zh()).isEqualTo("中文原句 1");
        // MySQL JSON 列会规范化序列化（冒号/逗号后加空格），比对解析后的结构而不是原始文本。
        assertThat(new ObjectMapper().readTree(first.chunks()))
                .isEqualTo(new ObjectMapper().readTree(CHUNKS));
        assertThat(first.level()).isEqualTo("TEM8");
    }

    /** [AC-1] bankId 过滤与 excludeSentenceId 排除、limit 截断。 */
    @Test
    @DisplayName("[AC-1] 动态过滤参数与 limit 生效")
    void ac1_dynamicFiltersAndLimit() {
        insertBank(101, "PUBLISHED", "PUBLIC", null);
        insertBank(201, "PUBLISHED", "PUBLIC", null);
        String s1 = insertPublishedSentence(101, 1, T0, null);
        String s2 = insertPublishedSentence(101, 2, T1, null);
        String s3 = insertPublishedSentence(201, 3, T2, null);

        assertThat(
                        practiceCandidateQueryDao
                                .findCandidates(201L, java.util.List.of(), null, 50)
                                .stream()
                                .map(PracticeCandidateRow::sentenceId)
                                .toList())
                .hasSize(1);
        assertThat(
                        practiceCandidateQueryDao
                                .findCandidates(null, java.util.List.of(s1), null, 50)
                                .stream()
                                .map(PracticeCandidateRow::sentenceId)
                                .toList())
                .containsExactly(s2, s3);
        assertThat(
                        practiceCandidateQueryDao
                                .findCandidates(null, java.util.List.of(), null, 1)
                                .stream()
                                .map(PracticeCandidateRow::sentenceId)
                                .toList())
                .containsExactly(s1);
    }

    /** [AC-1] 同一 created_at 内按 id 升序稳定排序（ULID 录入序兜底）。 */
    @Test
    @DisplayName("[AC-1] 同时间戳按 id 升序稳定排序")
    void ac1_stableOrderingWithinSameTimestamp() {
        insertBank(101, "PUBLISHED", "PUBLIC", null);
        String firstId = insertPublishedSentence(101, 1, T0, null);
        String secondId = insertPublishedSentence(101, 2, T0, null);

        List<PracticeCandidateRow> rows =
                practiceCandidateQueryDao.findCandidates(null, java.util.List.of(), null, 50);

        assertThat(rows)
                .extracting(PracticeCandidateRow::sentenceId)
                .containsExactly(firstId, secondId);
    }

    // ---------- 播种辅助 ----------

    /**
     * 播种题库。
     *
     * @param id 题库 id
     * @param status 状态字面量
     * @param visibility 可见性字面量（PUBLIC/PRIVATE）
     * @param deletedAt 软删时间，null 表示未删
     */
    private void insertBank(int id, String status, String visibility, Timestamp deletedAt) {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (?, NULL, ?, NULL, ?, ?, 1, ?, NULL, NULL, ?, ?, ?, NULL)",
                id,
                "题库 " + id,
                visibility,
                status,
                Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 9, 0, 0)),
                T0,
                T0,
                deletedAt);
    }

    /**
     * 播种一条「发布且未删」的题目并挂上快照。
     *
     * @param bankId 题库 id
     * @param seq 序号，用于生成 id 与指纹
     * @param createdAt 创建时间
     * @param deletedAt 软删时间，null 表示未删
     * @return 题目 id
     */
    private String insertPublishedSentence(
            int bankId, int seq, Timestamp createdAt, Timestamp deletedAt) {
        String sentenceId = String.format("01J%023d", seq);
        jdbcTemplate.update(
                "INSERT INTO sentences (id, bank_id, external_key, status, current_version_id,"
                        + " version, fingerprint, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (?, ?, NULL, 'PUBLISHED', NULL, ?, ?, NULL, NULL, ?, ?, ?, NULL)",
                sentenceId,
                bankId,
                seq,
                "fp-" + String.format("%059d", seq),
                createdAt,
                createdAt,
                deletedAt);
        long versionId = insertVersion(sentenceId, bankId, seq);
        jdbcTemplate.update(
                "UPDATE sentences SET current_version_id = ? WHERE id = ?", versionId, sentenceId);
        return sentenceId;
    }

    /**
     * 播种一条「发布且未删」但快照缺失的题目（current_version_id 为 NULL）。
     *
     * @param bankId 题库 id
     * @param seq 序号
     * @param createdAt 创建时间
     */
    private void insertSentenceWithoutSnapshot(int bankId, int seq, Timestamp createdAt) {
        jdbcTemplate.update(
                "INSERT INTO sentences (id, bank_id, external_key, status, current_version_id,"
                        + " version, fingerprint, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (?, ?, NULL, 'PUBLISHED', NULL, ?, ?, NULL, NULL, ?, ?, NULL, NULL)",
                String.format("01J%023d", seq),
                bankId,
                seq,
                "fp-" + String.format("%059d", seq),
                createdAt,
                createdAt);
    }

    /**
     * 播种内容快照并返回其自增 id。
     *
     * @param sentenceId 题目 id
     * @param bankId 题库 id
     * @param seq 序号
     * @return 快照行 id
     */
    private long insertVersion(String sentenceId, int bankId, int seq) {
        jdbcTemplate.update(
                "INSERT INTO sentence_versions (sentence_id, bank_id, version_no, zh, ref, note,"
                        + " chunks, blank, tags, level, source, license, content_hash,"
                        + " schema_version, import_batch_id, created_by, created_at)"
                        + " VALUES (?, ?, 3, ?, ?, ?, ?, ?, ?, 'TEM8', NULL, NULL, ?, 1, NULL, NULL, ?)",
                sentenceId,
                bankId,
                "中文原句 " + seq,
                "Reference " + seq,
                "解析 " + seq,
                CHUNKS,
                BLANK,
                TAGS,
                "hash-" + String.format("%058d", seq),
                T0);
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM sentence_versions WHERE sentence_id = ?",
                        Long.class,
                        sentenceId);
        assertThat(id).isNotNull();
        return id;
    }
}
