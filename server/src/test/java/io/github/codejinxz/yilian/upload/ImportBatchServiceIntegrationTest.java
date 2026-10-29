package io.github.codejinxz.yilian.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentencePage;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.upload.dto.ImportBatchViewResponse;
import io.github.codejinxz.yilian.upload.dto.ImportPreviewResponse;
import io.github.codejinxz.yilian.upload.service.ImportBatchService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入批次五端点服务层集成测试 [AC-1/2/3/5]：真实 MySQL + 真实本地对象存储。
 *
 * <p>覆盖 ACCEPTANCE #10（50 行 3 错 → 3 行标红 47 入库）、重复 commit 幂等、
 * 回滚后增量流 tombstone + 银行修订号递增、指纹重复 DUPLICATE_SENTENCE 与
 * commit 阶段唯一索引冲突（批次 FAILED + 40904）。测试库 yilian_test。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false",
            "yilian.security.jwt.secret=test-only-yilian-jwt-secret-0123456789abcdef"
        })
@ActiveProfiles("local")
class ImportBatchServiceIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 30, 12, 0, 0));

    private static final YilianUserPrincipal ADMIN = YilianUserPrincipal.of(9L, "it-importer", "ADMIN", false);

    @Autowired private ImportBatchService importBatchService;

    @Autowired private ContentImportService contentImportService;

    @Autowired private io.github.codejinxz.yilian.content.service.ContentQueryService queryService;

    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 测试库指向本机 yilian_test。
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

    /** 用例后清掉批次与内容表，避免 RESTRICT 外键污染其他集成套件（AuthFlow 清 users）。 */
    @AfterEach
    void cleanAfter() {
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

    /** 清空内容与批次表（RESTRICT 外键按序删除），并幂等播种操作者。 */
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
                "INSERT INTO users (id, email, username, password_hash, role, enabled)"
                        + " VALUES (9, 'it-importer@example.com', 'it-importer', 'seeded', 'ADMIN', TRUE)"
                        + " ON DUPLICATE KEY UPDATE enabled = TRUE");
    }

    /** [AC-1] ACCEPTANCE #10：50 行 3 错 → 3 行标红 47 入库；bank.version 逐题递增。 */
    @Test
    @DisplayName("[AC-1] 50 行 3 错 → 47 入库（ACCEPTANCE #10）")
    void ac1_preview50RowsWith3InvalidThenCommit47() {
        long bankId = seedBank();
        String json = fileJson(47, 3);
        ImportPreviewResponse preview =
                importBatchService.preview(
                        multipart("batch.json", json), bankId, "JSON", ADMIN);

        assertThat(preview.totalRows()).isEqualTo(50);
        assertThat(preview.validRows()).isEqualTo(47);
        assertThat(preview.invalidRows()).isEqualTo(3);
        assertThat(preview.status()).isEqualTo("PREVIEWED");
        assertThat(preview.rows()).hasSize(3);
        assertThat(preview.rows().getFirst().errors())
                .anySatisfy(error -> assertThat(error.code()).isEqualTo("ZH_LENGTH_INVALID"));
        assertThat(preview.errorSummary()).isNotNull();
        assertThat(preview.errorSummary().sampleRows()).hasSize(3);

        ImportBatchViewResponse committed = importBatchService.commit(preview.batchId(), ADMIN);

        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(committed.importedRows()).isEqualTo(47);
        Long bankVersion =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM sentence_banks WHERE id = ?", Long.class, bankId);
        assertThat(bankVersion).isEqualTo(47L);
    }

    /** [AC-2] 重复 commit 幂等返回且不再入库；非 PREVIEWED 状态 → 40904。 */
    @Test
    @DisplayName("[AC-2] 重复 commit 幂等；状态守卫 40904")
    void ac2_commitIdempotentAndStateGuard() {
        long bankId = seedBank();
        String batchId = previewAndCommit(bankId, fileJson(5, 0));

        ImportBatchViewResponse again = importBatchService.commit(batchId, ADMIN);
        assertThat(again.status()).isEqualTo("COMMITTED");
        Long sentenceCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE bank_id = ?", Long.class, bankId);
        assertThat(sentenceCount).isEqualTo(5L);

        String anotherBatchId = previewBatch(bankId, fileJson(2, 0));
        jdbcTemplate.update(
                "UPDATE import_batches SET status = 'COMMITTING' WHERE id = ?", anotherBatchId);
        assertThatThrownBy(() -> importBatchService.commit(anotherBatchId, ADMIN))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.IMPORT_NOT_COMMITTABLE));
        jdbcTemplate.update(
                "UPDATE import_batches SET status = 'ROLLED_BACK' WHERE id = ?", anotherBatchId);
        assertThatThrownBy(() -> importBatchService.commit(anotherBatchId, ADMIN))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.IMPORT_NOT_COMMITTABLE));
    }

    /** [AC-3] 回滚：题目 RECYCLED、银行修订号再递增、增量流 tombstone；重复回滚幂等。 */
    @Test
    @DisplayName("[AC-3] 回滚 tombstone 与幂等")
    void ac3_rollbackRecyclesAndEmitsTombstones() {
        long bankId = seedBank();
        String batchId = previewAndCommit(bankId, fileJson(5, 0));

        ImportBatchViewResponse rolled = importBatchService.rollback(batchId, "误导入", ADMIN);
        assertThat(rolled.status()).isEqualTo("ROLLED_BACK");
        Long recycledCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE bank_id = ? AND status = 'RECYCLED'",
                        Long.class,
                        bankId);
        assertThat(recycledCount).isEqualTo(5L);
        Long bankVersion =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM sentence_banks WHERE id = ?", Long.class, bankId);
        assertThat(bankVersion).isEqualTo(10L);
        SentencePage page = queryService.listSentences(bankId, 0, 50, null, false);
        assertThat(page.items())
                .filteredOn(item -> item.deleted())
                .hasSize(5);
        // 重复回滚幂等返回（不再重复回收）。
        ImportBatchViewResponse again = importBatchService.rollback(batchId, "再滚一次", ADMIN);
        assertThat(again.status()).isEqualTo("ROLLED_BACK");
        Long stillRecycled =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE bank_id = ? AND status = 'RECYCLED'",
                        Long.class,
                        bankId);
        assertThat(stillRecycled).isEqualTo(5L);
    }

    /** [AC-5] 指纹重复：批内重复标 DUPLICATE_SENTENCE；commit 阶段唯一索引冲突 → FAILED + 40904。 */
    @Test
    @DisplayName("[AC-5] 指纹去重与 commit 冲突")
    void ac5_duplicateFingerprintHandling() {
        long bankId = seedBank();
        String duplicateRow =
                "  {\"externalKey\":\"d1\",\"zh\":\"同一句重复导入。\",\"ref\":\"Duplicated sentence for import.\","
                        + "\"note\":\"这句会与下一行指纹重复，用于验证去重逻辑。\",\"chunks\":[{\"index\":0,\"zh\":\"同\",\"answers\":[\"Duplicated sentence for import.\"],\"tag\":\"重复\"}],\"blank\":[0],\"tags\":[\"重复\"]}";
        String json = "[" + duplicateRow + "," + duplicateRow + "]";
        ImportPreviewResponse preview =
                importBatchService.preview(multipart("dup.json", json), bankId, "JSON", ADMIN);

        assertThat(preview.invalidRows()).isEqualTo(2);
        assertThat(preview.rows())
                .allSatisfy(
                        row ->
                                assertThat(
                                                row.errors().stream()
                                                        .anyMatch(
                                                                error ->
                                                                        error.code()
                                                                                .equals("DUPLICATE_SENTENCE")))
                                        .isTrue());

        // commit 阶段冲突：先预览一批干净行，再直接落一条相同指纹的题目，commit 撞唯一索引。
        String batchId = previewBatch(bankId, fileJson(2, 0));
        seedConflictingSentence(bankId, 0);
        assertThatThrownBy(() -> importBatchService.commit(batchId, ADMIN))
                .isInstanceOf(BusinessException.class);
        ImportBatchViewResponse failed =
                importBatchService.detail(batchId, 1, 20, null).batch();
        assertThat(failed.status()).isEqualTo("FAILED");
    }

    /**
     * 预览并提交一批。
     *
     * @param bankId 题库 id
     * @param json 文件内容
     * @return 批次 id
     */
    private String previewAndCommit(long bankId, String json) {
        ImportPreviewResponse preview =
                importBatchService.preview(multipart("batch.json", json), bankId, "JSON", ADMIN);
        importBatchService.commit(preview.batchId(), ADMIN);
        return preview.batchId();
    }

    /**
     * 只预览一批。
     *
     * @param bankId 题库 id
     * @param json 文件内容
     * @return 批次 id
     */
    private String previewBatch(long bankId, String json) {
        return importBatchService
                .preview(multipart("batch.json", json), bankId, "JSON", ADMIN)
                .batchId();
    }

    /**
     * 构造 multipart 文件。
     *
     * @param filename 文件名
     * @param json 内容
     * @return multipart 文件
     */
    private static MockMultipartFile multipart(String filename, String json) {
        return new MockMultipartFile(
                "file", filename, "application/json", json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 生成导入文件 JSON：validRows 行合法 + invalidRows 行非法（zh 长度不足）。
     *
     * @param validRows 合法行数
     * @param invalidRows 非法行数
     * @return 文件文本
     */
    private static String fileJson(int validRows, int invalidRows) {
        List<String> rows = new ArrayList<>();
        for (int index = 1; index <= validRows; index++) {
            rows.add(rowJson("s_" + index, "第 " + index + " 句越来越常见。", "Sentence " + index + " is common."));
        }
        for (int index = 1; index <= invalidRows; index++) {
            rows.add(rowJson("bad_" + index, "短", "Sentence " + index + " is common."));
        }
        return "[" + String.join(",", rows) + "]";
    }

    /**
     * 单行 JSON。
     *
     * @param externalKey 源 id
     * @param zh 中文
     * @param ref 参考译文
     * @return JSON 文本
     */
    private static String rowJson(String externalKey, String zh, String ref) {
        return "{\"externalKey\":\""
                + externalKey
                + "\",\"zh\":\""
                + zh
                + "\",\"ref\":\""
                + ref
                + "\",\"note\":\"这是解析说明，长度满足最小要求，包含标点。\",\"chunks\":[{\"index\":0,\"zh\":\""
                + zh.substring(0, 1)
                + "\",\"answers\":[\""
                + ref
                + "\"],\"tag\":\"代词\"}],\"blank\":[0],\"tags\":[\"代词\"]}";
    }

    /**
     * 播种题库。
     *
     * @return 题库 id
     */
    private long seedBank() {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9001, NULL, ?, NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL, NULL, ?, ?, NULL, NULL)",
                "导入集成测试题库",
                T0,
                T0,
                T0);
        return 9001L;
    }

    /**
     * 直接落一条与某行指纹相同的题目（绕过 preview，制造 commit 阶段唯一索引冲突）。
     *
     * @param bankId 题库 id
     * @param index 行序号
     */
    private void seedConflictingSentence(long bankId, int index) {
        List<SentenceContent> rows =
                List.of(
                        new SentenceContent(
                                "s_" + (index + 1),
                                "第 " + (index + 1) + " 句越来越常见。",
                                "Sentence " + (index + 1) + " is common.",
                                "这是第 " + (index + 1) + " 句的解析说明，长度满足最小要求。",
                                List.of(
                                        new SentenceChunk(
                                                0,
                                                "第",
                                                List.of("Sentence " + (index + 1) + " is common."),
                                                "代词",
                                                null)),
                                List.of(0),
                                List.of("代词"),
                                null,
                                null,
                                null));
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, ?, 'JSON', 'seed.json', 'imports/seed.json', ?, 10, 'COMMITTED',"
                        + " 9, 'it-importer', ?)",
                "seed-batch-" + index,
                bankId,
                "a".repeat(64),
                T0);
        contentImportService.importSentences(
                bankId, "seed-batch-" + index, rows, 9L, T0.toInstant());
    }
}
