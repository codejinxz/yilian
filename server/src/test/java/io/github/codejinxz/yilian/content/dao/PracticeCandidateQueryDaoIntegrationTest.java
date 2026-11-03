package io.github.codejinxz.yilian.content.dao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link PracticeCandidateQueryDao} 的 MyBatis 链路集成测试：真实 MySQL + XML 动态 SQL。
 *
 * <p>同时证明三件事：MapperScan 注册生效（AC-2）、{@code map-underscore-to-camel-case} 与 record 自动构造映射生效、SQL
 * 语义与「题库可见性 + 未入队下推」逐条对拍一致（016：私有库只有本人可见、非 NEW 卡不占召回窗口）。测试库为 {@code yilian_test} （与
 * AuthFlowIntegrationTest 同源的前置条件：本机 MySQL）， 每个用例前清空内容三表与复习卡重新播种。
 */
@SpringBootTest(
        properties = {"yilian.ratelimit.enabled=false", "management.health.redis.enabled=false"})
@ActiveProfiles("test")
class PracticeCandidateQueryDaoIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 10, 0, 0));
    private static final Timestamp T1 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 10, 0, 1));
    private static final Timestamp T2 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 10, 0, 2));

    private static final long OWNER_ID = 9L;

    private static final long OTHER_ID = 8L;

    private static final String CHUNKS = "[{\"index\":0,\"zh\":\"他\",\"answers\":[\"He\"]}]";
    private static final String BLANK = "[0]";
    private static final String TAGS = "[\"代词\"]";

    @Autowired private PracticeCandidateQueryDao practiceCandidateQueryDao;

    @Autowired private JdbcTemplate jdbcTemplate;

    static {
        TestDatabases.ensureDatabase();
    }

    /**
     * 清空内容三表与复习卡，保证用例间幂等。
     *
     * <p>{@code sentence_versions} 带 append-only 触发器（V7），DELETE 被 SIGNAL 拒绝且与 {@code sentences}
     * 互为外键 RESTRICT，无法常规清理；测试库（yilian_test）可重置， 这里临时摘除两个触发器、清表后原样重建，保证测试结束后库内约束与生产一致。 reviews
     * 一并清空：候选 SQL 的 NOT EXISTS 下推后，其他套件残留的复习卡会污染可见性断言。
     */
    @BeforeEach
    void clean() {
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_delete");
        // sentences 与 sentence_versions 互为 RESTRICT 外键：先断 current_version_id 指针再按子→父删除。
        jdbcTemplate.update("UPDATE sentences SET current_version_id = NULL");
        jdbcTemplate.update("DELETE FROM review_logs");
        jdbcTemplate.update("DELETE FROM reviews");
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

    /** [AC-2] 题库可见性下推（016 P0-1a）：匿名只看公开库，私有库仅本人可见；软删/草稿题库与快照缺失行丢弃。 */
    @Test
    @DisplayName("[AC-2] 题库可见性下推：匿名/非本人/本人三态")
    void ac2_bankVisibilityPushdown() {
        insertBank(101, "PUBLISHED", "PUBLIC", null, null);
        insertBank(102, "PUBLISHED", "PUBLIC", T2, null);
        insertBank(103, "DRAFT", "PUBLIC", null, null);
        insertBank(104, "PUBLISHED", "PRIVATE", null, OWNER_ID);

        String s1 = insertPublishedSentence(101, 1, T0, null);
        String s2 = insertPublishedSentence(101, 2, T1, null);
        insertPublishedSentence(102, 3, T0, null);
        insertPublishedSentence(103, 4, T1, null);
        String s5 = insertPublishedSentence(104, 5, T2, null);
        insertSentenceWithoutSnapshot(101, 6, T2);

        // 匿名：软删题库（102）、草稿题库（103）与私有题库（104）的句子都不出现。
        assertThat(
                        practiceCandidateQueryDao
                                .findCandidates(null, null, java.util.List.of(), null, 50)
                                .stream()
                                .map(PracticeCandidateRow::sentenceId)
                                .toList())
                .containsExactly(s1, s2);
        // 登录非本人：私有库仍不可见。
        assertThat(
                        practiceCandidateQueryDao
                                .findCandidates(OTHER_ID, null, java.util.List.of(), null, 50)
                                .stream()
                                .map(PracticeCandidateRow::sentenceId)
                                .toList())
                .containsExactly(s1, s2);
        // 本人：私有库参与候选，排序口径不变。
        List<PracticeCandidateRow> rows =
                practiceCandidateQueryDao.findCandidates(
                        OWNER_ID, null, java.util.List.of(), null, 50);
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

    /** [AC-3] 未入队下推（016 P1）：非 NEW 卡（LEARNING/MASTERED）不占召回窗口，NEW/无卡照常返回。 */
    @Test
    @DisplayName("[AC-3] 未入队下推：非 NEW 卡被排除")
    void ac2_enqueuedExclusionPushdown() {
        insertBank(101, "PUBLISHED", "PUBLIC", null, null);
        String s1 = insertPublishedSentence(101, 1, T0, null);
        String s2 = insertPublishedSentence(101, 2, T1, null);

        // 用户 8 对 s1 有 MASTERED 卡、对 s2 无卡：s1 被排除、s2 返回。
        insertReview(OTHER_ID, s1, "MASTERED");
        assertThat(
                        practiceCandidateQueryDao
                                .findCandidates(OTHER_ID, null, java.util.List.of(), null, 50)
                                .stream()
                                .map(PracticeCandidateRow::sentenceId)
                                .toList())
                .containsExactly(s2);

        // 重置后的 NEW 卡不算已入队：s1 重新参与召回（一句一卡，改状态而非再插一行）。
        jdbcTemplate.update(
                "UPDATE reviews SET state = 'NEW' WHERE user_id = ? AND sentence_id = ?",
                OTHER_ID,
                s1);
        assertThat(
                        practiceCandidateQueryDao
                                .findCandidates(OTHER_ID, null, java.util.List.of(), null, 50)
                                .stream()
                                .map(PracticeCandidateRow::sentenceId)
                                .toList())
                .containsExactly(s1, s2);

        // 复习卡按用户隔离：用户 9 视角下 s1 未入队。
        assertThat(
                        practiceCandidateQueryDao
                                .findCandidates(OWNER_ID, null, java.util.List.of(), null, 50)
                                .stream()
                                .map(PracticeCandidateRow::sentenceId)
                                .toList())
                .containsExactly(s1, s2);
    }

    /** [AC-1] bankId 过滤与 excludeSentenceId 排除、limit 截断。 */
    @Test
    @DisplayName("[AC-1] 动态过滤参数与 limit 生效")
    void ac1_dynamicFiltersAndLimit() {
        insertBank(101, "PUBLISHED", "PUBLIC", null, null);
        insertBank(201, "PUBLISHED", "PUBLIC", null, null);
        String s1 = insertPublishedSentence(101, 1, T0, null);
        String s2 = insertPublishedSentence(101, 2, T1, null);
        String s3 = insertPublishedSentence(201, 3, T2, null);

        assertThat(
                        practiceCandidateQueryDao
                                .findCandidates(null, 201L, java.util.List.of(), null, 50)
                                .stream()
                                .map(PracticeCandidateRow::sentenceId)
                                .toList())
                .hasSize(1);
        assertThat(
                        practiceCandidateQueryDao
                                .findCandidates(null, null, java.util.List.of(s1), null, 50)
                                .stream()
                                .map(PracticeCandidateRow::sentenceId)
                                .toList())
                .containsExactly(s2, s3);
        assertThat(
                        practiceCandidateQueryDao
                                .findCandidates(null, null, java.util.List.of(), null, 1)
                                .stream()
                                .map(PracticeCandidateRow::sentenceId)
                                .toList())
                .containsExactly(s1);
    }

    /** [AC-1] 同一 created_at 内按 id 升序稳定排序（ULID 录入序兜底）。 */
    @Test
    @DisplayName("[AC-1] 同时间戳按 id 升序稳定排序")
    void ac1_stableOrderingWithinSameTimestamp() {
        insertBank(101, "PUBLISHED", "PUBLIC", null, null);
        String firstId = insertPublishedSentence(101, 1, T0, null);
        String secondId = insertPublishedSentence(101, 2, T0, null);

        List<PracticeCandidateRow> rows =
                practiceCandidateQueryDao.findCandidates(null, null, java.util.List.of(), null, 50);

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
     * @param ownerId 所有者用户 id，null 表示官方题库
     */
    private void insertBank(
            int id, String status, String visibility, Timestamp deletedAt, Long ownerId) {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (?, ?, ?, NULL, ?, ?, 1, ?, NULL, NULL, ?, ?, ?, NULL)",
                id,
                ownerId,
                "题库 " + id,
                visibility,
                status,
                Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 9, 0, 0)),
                T0,
                T0,
                deletedAt);
    }

    /**
     * 播种一张复习卡（候选 NOT EXISTS 下推的排他面）。
     *
     * @param userId 用户 id
     * @param sentenceId 题目 id
     * @param state 卡状态字面量（NEW/LEARNING/MASTERED）
     */
    private void insertReview(long userId, String sentenceId, String state) {
        jdbcTemplate.update(
                "INSERT INTO reviews (user_id, sentence_id, mode, state, fsrs_state, due_at,"
                        + " stability, difficulty, reps, lapses, scheduled_days, created_at,"
                        + " updated_at)"
                        + " VALUES (?, ?, 'CLOZE', ?, 'LEARNING', ?, 2.4, 5.3, 1, 1, 1, ?, ?)",
                userId,
                sentenceId,
                state,
                T0,
                T0,
                T0);
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
