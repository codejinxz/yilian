package io.github.codejinxz.yilian.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.admin.service.AdminGenerationService;
import io.github.codejinxz.yilian.admin.service.dto.AdminGenerationImportItem;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.support.TestDatabases;
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

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * external_key 唯一键双入口回归：真实 MySQL yilian_test。
 *
 * <p>回归背景：若 external_key 固定 {@code gen_%04d} 行号，行号每请求重排，同库第二次导入 必撞 {@code
 * uk_sentence_bank_external}：内容不同 → DataIntegrityViolation 冒 500；内容相同 → 本应按指纹
 * 40904。回滚批次后文件重导同文件也会撞被回收行仍占用的 key。现 AI 导入 的 key 带批次前缀（按请求隔离）；文件路径在唯一键兜底冲突时翻译为 40904。
 * 已知边界：唯一键仍覆盖已回收行，彻底放行需「仅活跃行」唯一索引迁移（active_external_key），尚未实施。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false",
            "yilian.import.reconcile-enabled=false"
        })
@ActiveProfiles("test")
class ImportExternalKeyReuseIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 12, 0, 0));

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(9L, "it-importer", "ADMIN", false);

    private static final String ZH_A = "他越来越重视这件事情。";

    private static final String REF_A = "He is paying more and more attention to this matter.";

    private static final String ZH_B = "会议如期顺利举行了。";

    private static final String REF_B = "The meeting was held as scheduled.";

    /** 与内容 A 配套的解析文案；文件路径与 AI 路径共用，保证两条路径内容完全一致。 */
    private static final String NOTE_A = "这是内容 A 的解析说明，长度满足最小要求。";

    @Autowired private AdminGenerationService adminGenerationService;

    @Autowired private ImportBatchService importBatchService;

    @Autowired private JdbcTemplate jdbcTemplate;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 清空内容与批次表并幂等播种操作者，保证重复运行与跨套件执行顺序无关。 */
    @BeforeEach
    void clean() {
        cleanTables();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, username, password_hash, role, enabled)"
                        + " VALUES (9, 'it-importer@example.com', 'it-importer', 'seeded', 'ADMIN', TRUE)"
                        + " ON DUPLICATE KEY UPDATE enabled = TRUE");
    }

    /** 用例后重建 append-only 触发器并清表，避免残留行污染其他集成套件。 */
    @AfterEach
    void cleanAfter() {
        cleanTables();
    }

    /** [AC-3] 同库两次 AI 导入同内容：第二次按指纹 40904（而不是唯一键 500）。 */
    @Test
    @DisplayName("[AC-3] AI 导入同内容两次 → 指纹 40904")
    void ac3_aiImportSameContentTwiceRejectedByFingerprint() {
        long bankId = seedBank();
        adminGenerationService.importGenerated(9L, String.valueOf(bankId), List.of(itemA()));

        assertThatThrownBy(
                        () ->
                                adminGenerationService.importGenerated(
                                        9L, String.valueOf(bankId), List.of(itemA())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode())
                                    .isEqualTo(ApiCode.IMPORT_NOT_COMMITTABLE);
                            assertThat(exception.getMessage()).contains("相同题目");
                        });
        assertThat(activeSentenceCount(bankId)).isEqualTo(1);
    }

    /** [AC-3] 同库两次 AI 导入不同内容：external_key 按批次隔离，两次都成功（修复前第二次 500）。 */
    @Test
    @DisplayName("[AC-3] AI 导入不同内容两次 → 均成功且 key 唯一")
    void ac3_aiImportDifferentContentTwiceSucceedsWithUniqueKeys() {
        long bankId = seedBank();
        adminGenerationService.importGenerated(9L, String.valueOf(bankId), List.of(itemA()));
        adminGenerationService.importGenerated(9L, String.valueOf(bankId), List.of(itemB()));

        List<String> externalKeys =
                jdbcTemplate.queryForList(
                        "SELECT external_key FROM sentences WHERE bank_id = ?"
                                + " AND deleted_at IS NULL",
                        String.class,
                        bankId);
        assertThat(externalKeys).hasSize(2).allMatch(key -> key.matches("gen_[0-9a-f]{8}_0001"));
        assertThat(externalKeys.stream().distinct().count()).as("两次导入的 key 必须互异").isEqualTo(2);
    }

    /** [AC-3] 回滚后重导（AI 路径）：文件批次回滚释放指纹后，同内容 AI 导入成功。 */
    @Test
    @DisplayName("[AC-3] 文件批次回滚 → AI 重导同内容成功")
    void ac3_reimportAfterRollbackSucceedsForGeneratedKeys() {
        long bankId = seedBank();
        ImportPreviewResponse preview =
                importBatchService.preview(
                        multipart("batch.json", fileJson(ZH_A, REF_A)), bankId, "JSON", ADMIN);
        importBatchService.commit(preview.batchId(), ADMIN);
        ImportBatchViewResponse rolled =
                importBatchService.rollback(preview.batchId(), "误导入", ADMIN);
        assertThat(rolled.status()).isEqualTo("ROLLED_BACK");

        ImportBatchViewResponse reimported =
                adminGenerationService.importGenerated(
                        9L, String.valueOf(bankId), List.of(itemA()));

        assertThat(reimported.status()).isEqualTo("COMMITTED");
        assertThat(reimported.importedRows()).isEqualTo(1);
        // 回收行保留（软删不删题），新题以新 key 活跃入库。
        assertThat(activeSentenceCount(bankId)).isEqualTo(1);
        Long recycled =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE bank_id = ? AND status = 'RECYCLED'",
                        Long.class,
                        bankId);
        assertThat(recycled).isEqualTo(1);
    }

    /**
     * [AC-5] 文件路径回滚后重导同文件：唯一键兜底冲突翻译为 40904（修复前 500）。
     *
     * <p>指纹预检对已回收行放行（active_fingerprint 为 NULL），提交时 INSERT 撞被回收行仍占用的 {@code (bank_id,
     * external_key)}；当前迁移口径下该冲突收敛为干净的 40904 + 批次 FAILED。 「重导同文件成功」需要 V11
     * 把唯一键改为仅活跃行语义（待批准项，暂以本用例锁定错误语义）。
     */
    @Test
    @DisplayName("[AC-5] 文件回滚后重导同文件 → 干净 40904")
    void ac5_fileReimportAfterRollbackFailsClean40904() {
        long bankId = seedBank();
        ImportPreviewResponse first =
                importBatchService.preview(
                        multipart("batch.json", fileJson(ZH_A, REF_A)), bankId, "JSON", ADMIN);
        importBatchService.commit(first.batchId(), ADMIN);
        importBatchService.rollback(first.batchId(), "误导入", ADMIN);

        ImportPreviewResponse second =
                importBatchService.preview(
                        multipart("batch.json", fileJson(ZH_A, REF_A)), bankId, "JSON", ADMIN);
        assertThat(second.validRows()).as("已回收行不再参与指纹去重").isEqualTo(1);

        assertThatThrownBy(() -> importBatchService.commit(second.batchId(), ADMIN))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.IMPORT_NOT_COMMITTABLE));
        ImportBatchViewResponse failed =
                importBatchService.detail(second.batchId(), 1, 20, null).batch();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(activeSentenceCount(bankId)).as("冲突整批回滚，不留半提交").isEqualTo(0);
    }

    // ---------- 内部 ----------

    /** 清空内容三表、批次两表与练习残留；先摘 sentence_versions 触发器再删行。 */
    private void cleanTables() {
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

    /**
     * 播种公开已发布题库。
     *
     * @return 题库 id
     */
    private long seedBank() {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9001, NULL, ?, NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL, NULL, ?, ?, NULL, NULL)",
                "external_key 集成测试题库",
                T0,
                T0,
                T0);
        return 9001L;
    }

    /**
     * 统计题库内活跃（未软删）题目数。
     *
     * @param bankId 题库 id
     * @return 活跃题目数
     */
    private Long activeSentenceCount(long bankId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sentences WHERE bank_id = ? AND deleted_at IS NULL",
                Long.class,
                bankId);
    }

    /**
     * 构造内容 A 的 AI 导入行（与文件路径 fileJson(ZH_A, REF_A) 同内容）。
     *
     * @return 导入行
     */
    private static AdminGenerationImportItem itemA() {
        return item(ZH_A, REF_A, NOTE_A);
    }

    /**
     * 构造内容 B 的 AI 导入行（与 A 指纹不同）。
     *
     * @return 导入行
     */
    private static AdminGenerationImportItem itemB() {
        return item(ZH_B, REF_B, "这是内容 B 的解析说明，同样满足最小长度要求。");
    }

    /**
     * 构造通过行校验的 AI 导入行（chunks[].answers[0] 拼接与 ref 一致）。
     *
     * @param zh 中文原句
     * @param ref 参考译文
     * @param note 解析
     * @return 导入行
     */
    private static AdminGenerationImportItem item(String zh, String ref, String note) {
        return new AdminGenerationImportItem(
                zh,
                ref,
                note,
                null,
                List.of("代词"),
                List.of(
                        new AdminGenerationImportItem.Chunk(
                                0, zh.substring(0, 1), List.of(ref), "代词", null)),
                List.of(0));
    }

    /**
     * 构造单行导入 JSON（与 AI 行同 zh/ref/chunks 内容）。
     *
     * @param zh 中文原句
     * @param ref 参考译文
     * @return 文件文本
     */
    private static String fileJson(String zh, String ref) {
        String row =
                "{\"externalKey\":\"s_1\",\"zh\":\""
                        + zh
                        + "\",\"ref\":\""
                        + ref
                        + "\",\"note\":\""
                        + NOTE_A
                        + "\",\"chunks\":[{\"index\":0,\"zh\":\""
                        + zh.substring(0, 1)
                        + "\",\"answers\":[\""
                        + ref
                        + "\"],\"tag\":\"代词\"}],\"blank\":[0],\"tags\":[\"代词\"]}";
        return "[" + row + "]";
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
                "file", filename, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }
}
