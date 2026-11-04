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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link PickerSentenceQueryDao} 的 MyBatis 链路集成测试：真实 MySQL + XML 动态 SQL。
 *
 * <p>对拍四件事：题库可见性下推（016 口径复用）、练过态按用户 LEFT JOIN（匿名/无卡为 NULL、不串用户）、 动态筛选（keyword/level/status
 * 缺省隐藏回收站）、分页与排序（题库录入序）。 测试库由 {@code YILIAN_TEST_DB_NAME} 指定（如 {@code yilian_test_020}），
 * 每个用例前清空内容三表与复习卡重新播种。
 */
@SpringBootTest(
        properties = {"yilian.ratelimit.enabled=false", "management.health.redis.enabled=false"})
@ActiveProfiles("test")
class PickerSentenceQueryDaoIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 4, 10, 0, 0));
    private static final Timestamp T1 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 4, 10, 0, 1));
    private static final Timestamp T2 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 4, 10, 0, 2));

    private static final long OWNER_ID = 9L;

    private static final long OTHER_ID = 8L;

    private static final String CHUNKS = "[{\"index\":0,\"zh\":\"他\",\"answers\":[\"He\"]}]";
    private static final String BLANK = "[0]";
    private static final String TAGS = "[\"代词\"]";

    @Autowired private PickerSentenceQueryDao pickerSentenceQueryDao;

    @Autowired private JdbcTemplate jdbcTemplate;

    static {
        TestDatabases.ensureDatabase();
    }

    /**
     * 清空内容三表与复习卡，保证用例间幂等。
     *
     * <p>{@code sentence_versions} 带 append-only 触发器（V7），DELETE 被 SIGNAL 拒绝且与 {@code sentences}
     * 互为外键 RESTRICT，无法常规清理；测试库可重置， 这里临时摘除两个触发器、清表后原样重建。
     */
    @BeforeEach
    void clean() {
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_delete");
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

    /** [AC-1] 题库可见性下推：匿名只看公开库；私有库仅本人；未发布/软删库的句子不出现。 */
    @Test
    @DisplayName("[AC-1] 题库可见性下推：匿名/非本人/本人三态")
    void ac1_bankVisibilityPushdown() {
        insertBank(101, "PUBLISHED", "PUBLIC", null, null);
        insertBank(102, "PUBLISHED", "PUBLIC", T2, null);
        insertBank(103, "DRAFT", "PUBLIC", null, null);
        insertBank(104, "PUBLISHED", "PRIVATE", null, OWNER_ID);

        String s1 = insertSentence(101, 1, T0, null, "PUBLISHED");
        String s2 = insertSentence(101, 2, T1, null, "PUBLISHED");
        insertSentence(102, 3, T0, null, "PUBLISHED");
        insertSentence(103, 4, T1, null, "PUBLISHED");
        String s5 = insertSentence(104, 5, T2, null, "PUBLISHED");
        insertSentenceWithoutSnapshot(101, 6, T2);

        // 匿名：软删题库（102）、草稿题库（103）与私有题库（104）的句子都不出现。
        assertThat(
                        pickerSentenceQueryDao
                                .search(101, null, null, null, null, true, 0, 50)
                                .stream()
                                .map(PickerSentenceRow::sentenceId)
                                .toList())
                .containsExactly(s1, s2);
        // 登录非本人：私有库仍不可见。
        assertThat(
                        pickerSentenceQueryDao
                                .search(104, OTHER_ID, null, null, null, true, 0, 50)
                                .stream()
                                .map(PickerSentenceRow::sentenceId)
                                .toList())
                .isEmpty();
        // 本人：私有库可见。
        assertThat(
                        pickerSentenceQueryDao
                                .search(104, OWNER_ID, null, null, null, true, 0, 50)
                                .stream()
                                .map(PickerSentenceRow::sentenceId)
                                .toList())
                .containsExactly(s5);
    }

    /** [AC-1] 练过态按用户 LEFT JOIN：本人卡带 state/dueAt，他人卡不串，无卡/匿名两列为 null。 */
    @Test
    @DisplayName("[AC-1] 练过态：本人卡投影、他人不串、匿名与无卡为 null")
    void ac1_practiceStatePerUser() {
        insertBank(101, "PUBLISHED", "PUBLIC", null, null);
        String s1 = insertSentence(101, 1, T0, null, "PUBLISHED");
        String s2 = insertSentence(101, 2, T1, null, "PUBLISHED");
        insertReview(OTHER_ID, s1, "LEARNING");

        // 本人视角：无卡行两列为 null。
        List<PickerSentenceRow> ownerRows =
                pickerSentenceQueryDao.search(101, OWNER_ID, null, null, null, true, 0, 50);
        assertThat(ownerRows).extracting(PickerSentenceRow::sentenceId).containsExactly(s1, s2);
        assertThat(
                        ownerRows.stream()
                                .allMatch(
                                        row ->
                                                row.reviewState() == null
                                                        && row.reviewDueAt() == null))
                .as("本人无卡行练过态为 null")
                .isTrue();

        // 他人视角：s1 带 LEARNING 卡与到期时间，s2 无卡为 null。
        List<PickerSentenceRow> otherRows =
                pickerSentenceQueryDao.search(101, OTHER_ID, null, null, null, true, 0, 50);
        PickerSentenceRow learned = otherRows.getFirst();
        assertThat(learned.sentenceId()).isEqualTo(s1);
        assertThat(learned.reviewState()).isEqualTo("LEARNING");
        assertThat(learned.reviewDueAt()).isEqualTo(T0.toInstant());
        assertThat(otherRows.get(1).reviewState()).isNull();

        // 匿名：练过态恒为 null（「游客=未学」）。
        assertThat(
                        pickerSentenceQueryDao
                                .search(101, null, null, null, null, true, 0, 50)
                                .stream()
                                .allMatch(
                                        row ->
                                                row.reviewState() == null
                                                        && row.reviewDueAt() == null))
                .as("匿名行练过态为 null")
                .isTrue();
    }

    /** [AC-1] 动态筛选：keyword 命中中文/参考译文、level 精确、缺省隐藏回收站、显式 status 精确过滤。 */
    @Test
    @DisplayName("[AC-1] 动态筛选：keyword/level/status 与缺省隐藏回收站")
    void ac1_dynamicFiltersAndDefaultHideRecycled() {
        insertBank(101, "PUBLISHED", "PUBLIC", null, null);
        String s1 = insertSentence(101, 1, T0, null, "PUBLISHED");
        String s2 = insertSentence(101, 2, T1, null, "PUBLISHED");
        String s3 = insertSentence(101, 3, T2, null, "DISABLED");
        String s4 = insertSentence(101, 4, T2, null, "RECYCLED");

        // 缺省（status=null, hideRecycled=true）：回收站隐藏，DISABLED 保留（面板打徽标）。
        assertThat(
                        pickerSentenceQueryDao
                                .search(101, null, null, null, null, true, 0, 50)
                                .stream()
                                .map(PickerSentenceRow::sentenceId)
                                .toList())
                .containsExactly(s1, s2, s3);
        // 显式 status=RECYCLED：按精确状态过滤。
        assertThat(
                        pickerSentenceQueryDao
                                .search(101, null, null, null, "RECYCLED", false, 0, 50)
                                .stream()
                                .map(PickerSentenceRow::sentenceId)
                                .toList())
                .containsExactly(s4);

        // keyword 命中文档原句（ref LIKE）。
        assertThat(
                        pickerSentenceQueryDao
                                .search(101, null, "Reference 2", null, null, true, 0, 50)
                                .stream()
                                .map(PickerSentenceRow::sentenceId)
                                .toList())
                .containsExactly(s2);

        // level 精确过滤：s2 播种为 CET4，其余 TEM8。
        assertThat(
                        pickerSentenceQueryDao
                                .search(101, null, null, "CET4", null, true, 0, 50)
                                .stream()
                                .map(PickerSentenceRow::sentenceId)
                                .toList())
                .containsExactly(s2);
    }

    /** [AC-1] 分页 offset/limit 与排序：created_at 升序（录入序），同刻按 id 升序稳定。 */
    @Test
    @DisplayName("[AC-1] 分页与录入序排序")
    void ac1_paginationAndStableOrdering() {
        insertBank(101, "PUBLISHED", "PUBLIC", null, null);
        String firstId = insertSentence(101, 1, T0, null, "PUBLISHED");
        String secondId = insertSentence(101, 2, T0, null, "PUBLISHED");
        insertSentence(101, 3, T1, null, "PUBLISHED");

        List<PickerSentenceRow> page1 =
                pickerSentenceQueryDao.search(101, null, null, null, null, true, 0, 2);
        assertThat(page1)
                .extracting(PickerSentenceRow::sentenceId)
                .containsExactly(firstId, secondId);
        assertThat(page1.getFirst().status()).isEqualTo("PUBLISHED");
        assertThat(page1.getFirst().zh()).isEqualTo("中文原句 1");

        List<PickerSentenceRow> page2 =
                pickerSentenceQueryDao.search(101, null, null, null, null, true, 2, 2);
        assertThat(page2).hasSize(1);
        assertThat(pickerSentenceQueryDao.count(101, null, null, null, null, true)).isEqualTo(3);
        // count 与 search 同条件：keyword 过滤后计数同步收敛。
        assertThat(pickerSentenceQueryDao.count(101, null, "Reference 2", null, null, true))
                .isEqualTo(1);
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
                Timestamp.valueOf(LocalDateTime.of(2026, 11, 4, 9, 0, 0)),
                T0,
                T0,
                deletedAt);
    }

    /**
     * 播种一张复习卡（练过态 LEFT JOIN 的投影源）。
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
     * 播种一条题目并挂上快照（状态与软删时间可调，覆盖生命周期徽标场景）。
     *
     * @param bankId 题库 id
     * @param seq 序号，用于生成 id 与指纹
     * @param createdAt 创建时间
     * @param deletedAt 软删时间，null 表示未删
     * @param status 状态字面量
     * @return 题目 id
     */
    private String insertSentence(
            int bankId, int seq, Timestamp createdAt, Timestamp deletedAt, String status) {
        String sentenceId = String.format("01J%023d", seq);
        jdbcTemplate.update(
                "INSERT INTO sentences (id, bank_id, external_key, status, current_version_id,"
                        + " version, fingerprint, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (?, ?, NULL, ?, NULL, ?, ?, NULL, NULL, ?, ?, ?, NULL)",
                sentenceId,
                bankId,
                status,
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
     * 播种内容快照并返回其自增 id；序号 2 的快照播为 CET4，供 level 精确过滤断言。
     *
     * @param sentenceId 题目 id
     * @param bankId 题库 id
     * @param seq 序号
     * @return 快照行 id
     */
    private long insertVersion(String sentenceId, int bankId, int seq) {
        String level = seq == 2 ? "CET4" : "TEM8";
        jdbcTemplate.update(
                "INSERT INTO sentence_versions (sentence_id, bank_id, version_no, zh, ref, note,"
                        + " chunks, blank, tags, level, source, license, content_hash,"
                        + " schema_version, import_batch_id, created_by, created_at)"
                        + " VALUES (?, ?, 3, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, 1, NULL, NULL, ?)",
                sentenceId,
                bankId,
                "中文原句 " + seq,
                "Reference " + seq,
                "解析 " + seq,
                CHUNKS,
                BLANK,
                TAGS,
                level,
                "fp-" + String.format("%059d", seq),
                T0);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sentence_versions WHERE sentence_id = ? AND version_no = 3",
                Long.class,
                sentenceId);
    }
}
