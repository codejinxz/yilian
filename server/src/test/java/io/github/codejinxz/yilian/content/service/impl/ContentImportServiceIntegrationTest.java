package io.github.codejinxz.yilian.content.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.ContentQueryService;
import io.github.codejinxz.yilian.content.service.dto.ImportedSentence;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.content.service.vo.SentencePage;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link ContentImportService} 落库链路集成测试 [AC-4]：真实 MySQL + 悲观锁事务。
 *
 * <p>覆盖：逐行落库（PUBLISHED + 快照回填）、银行修订号「每题递增」（P1-1）、 200 行 limit=50 拉满 4 页无丢行（分页回归）、指纹批内重复整批回滚、 DRAFT
 * tombstone（P1-2，经增量流断言）。测试库 yilian_test，每用例清空内容三表。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class ContentImportServiceIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 12, 0, 0));

    @Autowired private ContentImportService contentImportService;

    @Autowired private ContentQueryService contentQueryService;

    @Autowired private JdbcTemplate jdbcTemplate;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 用例后清掉批次与内容表，避免 RESTRICT 外键污染其他集成套件（AuthFlow 清 users）。 */
    @AfterEach
    void cleanAfter() {
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_delete");
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

    /** 清空内容三表（先断指针再删），与练习候选集成测试同口径。 */
    @BeforeEach
    void clean() {
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_delete");
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
        // 导入批次 created_by 有 users 外键；幂等播种操作者（AuthFlow 会清非 admin 用户，这里补回）。
        jdbcTemplate.update(
                "INSERT INTO users (id, email, username, password_hash, role, enabled)"
                        + " VALUES (9, 'it-importer@example.com', 'it-importer', 'seeded', 'ADMIN', TRUE)"
                        + " ON DUPLICATE KEY UPDATE enabled = TRUE");
    }

    /** [AC-4] 逐行落库：PUBLISHED + 快照回填 + 银行修订号每题递增。 */
    @Test
    @DisplayName("[AC-4] 逐行落库与银行修订号每题递增")
    void ac4_importRowsIncrementsBankVersionPerRow() {
        long bankId = insertBank("PUBLISHED");
        List<SentenceContent> rows =
                List.of(
                        row(1, "他越来越重视这件事。", "He is paying more and more attention."),
                        row(2, "会议如期举行。", "The meeting was held as scheduled."),
                        row(3, "天气越来越好。", "The weather is getting better and better."));

        seedImportBatch("batch-it-0001-0000-0000-000000000001");
        var results =
                contentImportService.importSentences(
                        bankId, "batch-it-0001-0000-0000-000000000001", rows, 9L, T0.toInstant());

        assertThat(results).hasSize(3);
        assertThat(results).extracting(r -> r.sentenceVersionId()).doesNotHaveDuplicates();
        Long bankVersion =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM sentence_banks WHERE id = ?", Long.class, bankId);
        assertThat(bankVersion).isEqualTo(3L);
        Long publishedCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE bank_id = ? AND status = 'PUBLISHED'"
                                + " AND deleted_at IS NULL",
                        Long.class,
                        bankId);
        assertThat(publishedCount).isEqualTo(3L);
        Long versionLinks =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences s JOIN sentence_versions v"
                                + " ON s.current_version_id = v.id WHERE s.bank_id = ?",
                        Long.class,
                        bankId);
        assertThat(versionLinks).isEqualTo(3L);
    }

    /** [AC-4] 指纹批内重复：整批回滚（无任何落库）且抛 IMPORT_NOT_COMMITTABLE。 */
    @Test
    @DisplayName("[AC-4] 指纹批内重复整批回滚")
    void ac4_duplicateFingerprintRollsBackWholeBatch() {
        long bankId = insertBank("PUBLISHED");
        List<SentenceContent> rows =
                List.of(
                        row(1, "他越来越重视这件事。", "He is paying more and more attention."),
                        row(2, "他越来越重视这件事。", "He is paying more and more attention."));

        seedImportBatch("batch-it-0002-0000-0000-000000000002");

        assertThatThrownBy(
                        () ->
                                contentImportService.importSentences(
                                        bankId,
                                        "batch-it-0002-0000-0000-000000000002",
                                        rows,
                                        9L,
                                        T0.toInstant()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.IMPORT_NOT_COMMITTABLE));

        Long bankCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE bank_id = ?", Long.class, bankId);
        assertThat(bankCount).isZero();
    }

    /** [AC-4] 分页回归（P1-1）：200 行导入后 limit=50 拉满 4 页无丢行；DRAFT 回 tombstone（P1-2）。 */
    @Test
    @DisplayName("[AC-4] 200 行 limit=50 拉满 4 页无丢行；DRAFT 回 tombstone")
    void ac4_paginationRegressionAndDraftTombstone() {
        long bankId = insertBank("PUBLISHED");
        List<SentenceContent> rows = new ArrayList<>();
        for (int index = 1; index <= 200; index++) {
            rows.add(
                    row(
                            index,
                            "第 " + index + " 句越来越常见。",
                            "Sentence number " + index + " is increasingly common."));
        }
        seedImportBatch("batch-it-0003-0000-0000-000000000003");
        contentImportService.importSentences(
                bankId, "batch-it-0003-0000-0000-000000000003", rows, 9L, T0.toInstant());
        // 最后一行降级为 DRAFT：增量流应回 tombstone 而不是内容或跳过（P1-2）。
        String draftSentenceId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM sentences WHERE bank_id = ? AND external_key = ?",
                        String.class,
                        bankId,
                        "s_0200");
        jdbcTemplate.update("UPDATE sentences SET status = 'DRAFT' WHERE id = ?", draftSentenceId);

        Set<String> seen = new HashSet<>();
        long sinceVersion = 0;
        int pages = 0;
        String draftTombstoneStatus = null;
        while (pages < 10) {
            SentencePage page =
                    contentQueryService.listSentences(bankId, sinceVersion, 50, null, false);
            pages++;
            for (var item : page.items()) {
                seen.add(item.id());
                if (item.id().equals(draftSentenceId)) {
                    draftTombstoneStatus = item.status();
                }
            }
            if (!page.hasMore()) {
                break;
            }
            sinceVersion = page.nextSinceVersion();
        }
        // hasMore 以「取满 limit」近似：200 行整除 50 时会多拉一次空页自然终止（API §1.6 语义）。
        assertThat(pages).isBetween(4, 5);
        assertThat(seen).hasSize(200);
        assertThat(draftTombstoneStatus).isEqualTo("DRAFT");
    }

    /** 回收归属与状态防御：不属于目标库的 id 与已回收（软删）的 id 一律跳过， 返回值只计实际回收——防止批次行错乱时用本库版本号改写他库题目并重复推进修订号。 */
    @Test
    @DisplayName("[AC-4] 回收校验归属库与已回收状态")
    void ac4_recycleSkipsForeignBankAndAlreadyRecycledSentences() {
        long bankId = insertBank("PUBLISHED");
        List<SentenceContent> rows =
                List.of(
                        row(1, "他越来越重视这件事。", "He is paying more and more attention."),
                        row(2, "会议如期顺利举行了。", "The meeting was held as scheduled."));
        seedImportBatch("batch-it-0004-0000-0000-000000000004");
        List<ImportedSentence> imported =
                contentImportService.importSentences(
                        bankId, "batch-it-0004-0000-0000-000000000004", rows, 9L, T0.toInstant());
        String ownId = imported.get(0).sentenceId();
        String secondId = imported.get(1).sentenceId();

        // 他库题目（V10 后与批次间只剩逻辑外键，跨库 id 现实可传）。
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9002, NULL, '他库', NULL, 'PUBLIC', 'PUBLISHED', 5, ?, NULL, NULL,"
                        + " ?, ?, NULL, NULL)",
                T0,
                T0,
                T0);
        jdbcTemplate.update(
                "INSERT INTO sentences (id, bank_id, external_key, status, current_version_id,"
                        + " version, fingerprint, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES ('01JFOREIG0000000000000000A', 9002, NULL, 'PUBLISHED', NULL, 5,"
                        + " 'fp-foreign', NULL, NULL, ?, ?, NULL, NULL)",
                T0,
                T0);
        // 本库已回收的题目（deleted_at 非空）。
        jdbcTemplate.update(
                "UPDATE sentences SET status = 'RECYCLED', deleted_at = ?, deleted_by = 9"
                        + " WHERE id = ?",
                T0,
                secondId);
        Long foreignVersionBefore =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM sentences WHERE id = '01JFOREIG0000000000000000A'",
                        Long.class);
        Long foreignBankVersionBefore =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM sentence_banks WHERE id = 9002", Long.class);

        int recycled =
                contentImportService.recycleImportedSentences(
                        bankId,
                        List.of(ownId, secondId, "01JFOREIG0000000000000000A"),
                        9L,
                        T0.toInstant());

        // 只回收本库未软删的 ownId：已回收与他库 id 都跳过。
        assertThat(recycled).isEqualTo(1);
        Long ownDeleted =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE id = ? AND deleted_at IS NOT NULL"
                                + " AND status = 'RECYCLED'",
                        Long.class,
                        ownId);
        assertThat(ownDeleted).isEqualTo(1L);
        // 他库题目与题库修订号原样不动。
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT version FROM sentences WHERE id ="
                                        + " '01JFOREIG0000000000000000A'",
                                Long.class))
                .isEqualTo(foreignVersionBefore);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT version FROM sentence_banks WHERE id = 9002", Long.class))
                .isEqualTo(foreignBankVersionBefore);
        // 本库修订号只因实际回收推进一版（导入 2 行到 2，再推进到 3）。
        Long bankVersion =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM sentence_banks WHERE id = ?", Long.class, bankId);
        assertThat(bankVersion).isEqualTo(3L);
    }

    /**
     * 播种真实导入批次（V10 后为逻辑外键，但业务序仍是先有批次再落题目）。
     *
     * @param batchId 批次 id
     */
    private void seedImportBatch(String batchId) {
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, 9001, 'JSON', 'it.json', 'imports/it.json', ?, 1024,"
                        + " 'COMMITTING', 9, 'admin', ?)",
                batchId,
                "a".repeat(64),
                T0);
    }

    /**
     * 播种题库。
     *
     * @param status 状态字面量
     * @return 题库 id
     */
    private long insertBank(String status) {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9001, NULL, ?, NULL, 'PUBLIC', ?, 0, ?, NULL, NULL, ?, ?, NULL, NULL)",
                "导入测试题库",
                status,
                T0,
                T0,
                T0);
        return 9001L;
    }

    /**
     * 构造一行导入内容。
     *
     * @param seq 序号
     * @param zh 中文原句
     * @param ref 参考译文
     * @return 行内容
     */
    private static SentenceContent row(int seq, String zh, String ref) {
        List<SentenceChunk> chunks =
                List.of(new SentenceChunk(0, zh.substring(0, 1), List.of(ref), "代词", null));
        return new SentenceContent(
                "s_" + String.format("%04d", seq),
                zh,
                ref,
                "这是第 " + seq + " 句的解析说明，长度满足最小要求。",
                chunks,
                List.of(0),
                List.of("代词"),
                null,
                null,
                null);
    }
}
