package io.github.codejinxz.yilian.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.content.service.vo.SentencePage;
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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 导入批次五端点服务层集成测试 [AC-1/2/3/5]：真实 MySQL + 真实本地对象存储。
 *
 * <p>覆盖 ACCEPTANCE #10（50 行 3 错 → 3 行标红 47 入库）、重复 commit 幂等、 回滚后增量流 tombstone + 银行修订号递增、指纹重复
 * DUPLICATE_SENTENCE 与 commit 阶段唯一索引冲突（批次 FAILED + 40904）。测试库 yilian_test。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false",
            "yilian.import.reconcile-enabled=false"
        })
@ActiveProfiles("test")
class ImportBatchServiceIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 30, 12, 0, 0));

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(9L, "it-importer", "ADMIN", false);

    @Autowired private ImportBatchService importBatchService;

    @Autowired private ContentImportService contentImportService;

    @Autowired private io.github.codejinxz.yilian.content.service.ContentQueryService queryService;

    @Autowired private JdbcTemplate jdbcTemplate;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private io.github.codejinxz.yilian.upload.dao.ImportBatchRowDao importBatchRowDao;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 用例后清掉批次与内容表，避免残留行污染其他集成套件（AuthFlow 清 users）。 */
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

    /** 清空内容与批次表（V10 后为逻辑外键，按序删除仅为可读性约定），并幂等播种操作者。 */
    @BeforeEach
    void clean() {
        // 清掉可能被前序用例 stub 的 spy 行为，避免跨用例污染。
        org.mockito.Mockito.reset(importBatchRowDao);
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
                importBatchService.preview(multipart("batch.json", json), bankId, "JSON", ADMIN);

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
        assertThat(page.items()).filteredOn(item -> item.deleted()).hasSize(5);
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
                                                                                .equals(
                                                                                        "DUPLICATE_SENTENCE")))
                                        .isTrue());

        // commit 阶段冲突：先预览一批干净行，再直接落一条相同指纹的题目，commit 撞唯一索引。
        String batchId = previewBatch(bankId, fileJson(2, 0));
        seedConflictingSentence(bankId, 0);
        assertThatThrownBy(() -> importBatchService.commit(batchId, ADMIN))
                .isInstanceOf(BusinessException.class);
        ImportBatchViewResponse failed = importBatchService.detail(batchId, 1, 20, null).batch();
        assertThat(failed.status()).isEqualTo("FAILED");
    }

    /** [AC-5] 提交原子性（015 修复轮 major #3）：行回写失败 → 整体回滚、批次 FAILED、零题目入库；随后同库可重新导入。 */
    @Test
    @DisplayName("[AC-5] commit 原子性：回写失败整体回滚可恢复")
    void ac5_commitWritebackFailureRollsBackAtomically() {
        long bankId = seedBank();
        String batchId = previewBatch(bankId, fileJson(3, 0));
        try {
            // 模拟「题目已入库、行回写失败」的进程内故障：行 Dao 抛异常，整个原子事务必须回滚。
            org.mockito.Mockito.doThrow(new IllegalStateException("回写失败"))
                    .when(importBatchRowDao)
                    .save(org.mockito.ArgumentMatchers.any());
            assertThatThrownBy(() -> importBatchService.commit(batchId, ADMIN))
                    // 事务出口会把行 Dao 的 RuntimeException 包装为 DataAccessException 体系，
                    // 断言堆栈携带根因即可：失败必须原样上抛（转 FAILED 的语义在 catch 里完成）。
                    .hasStackTraceContaining("回写失败");
        } finally {
            org.mockito.Mockito.reset(importBatchRowDao);
        }
        // 批次收敛为 FAILED（终态），不再卡 COMMITTING；重试 commit 也是 40904。
        assertThat(importBatchService.detail(batchId, 1, 20, null).batch().status())
                .isEqualTo("FAILED");
        assertThatThrownBy(() -> importBatchService.commit(batchId, ADMIN))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.IMPORT_NOT_COMMITTABLE));
        // 零题目入库、零行回写、银行修订号不动：原子回滚证据。
        Long sentenceCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE bank_id = ?", Long.class, bankId);
        assertThat(sentenceCount).isZero();
        Long importedRowCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM import_batch_rows WHERE status = 'IMPORTED'",
                        Long.class);
        assertThat(importedRowCount).isZero();
        Long bankVersion =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM sentence_banks WHERE id = ?", Long.class, bankId);
        assertThat(bankVersion).isZero();
        // 可恢复：同库新批次正常预览+提交。
        ImportBatchViewResponse recovered =
                importBatchService.commit(previewBatch(bankId, fileJson(2, 0)), ADMIN);
        assertThat(recovered.status()).isEqualTo("COMMITTED");
    }

    /** [AC-5] COMMITTING 残留对账（015 修复轮 major #3）：行未关联 → FAILED；行已关联 → 补 COMMITTED。 */
    @Test
    @DisplayName("[AC-5] COMMITTING 残留对账收敛")
    void ac5_reconcileCommittingBatchesConverges() {
        long bankId = seedBank();
        // 场景 1：提交事务未完成（行未关联题目）→ 收敛为 FAILED，不再死锁状态。
        String batchA = previewBatch(bankId, fileJson(2, 0));
        jdbcTemplate.update("UPDATE import_batches SET status = 'COMMITTING' WHERE id = ?", batchA);
        assertThat(importBatchService.reconcileCommittingBatches()).isEqualTo(1);
        assertThat(importBatchService.detail(batchA, 1, 20, null).batch().status())
                .isEqualTo("FAILED");

        // 场景 2：旧版部分提交遗产——题目已入库、行已 IMPORTED、状态停在 COMMITTING → 补 COMMITTED。
        String batchB = previewAndCommit(bankId, fileJson(2, 0));
        jdbcTemplate.update(
                "UPDATE import_batches SET status = 'COMMITTING', committed_at = NULL WHERE id = ?",
                batchB);
        assertThat(importBatchService.reconcileCommittingBatches()).isEqualTo(1);
        ImportBatchViewResponse recovered = importBatchService.detail(batchB, 1, 20, null).batch();
        assertThat(recovered.status()).isEqualTo("COMMITTED");
        assertThat(recovered.importedRows()).isEqualTo(2);
        // 对账只补状态不重复入库。
        Long sentenceCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE bank_id = ?", Long.class, bankId);
        assertThat(sentenceCount).isEqualTo(2L);
        // 收敛后的批次与正常提交同语义：可回滚。
        ImportBatchViewResponse rolled = importBatchService.rollback(batchB, "对账后回滚", ADMIN);
        assertThat(rolled.status()).isEqualTo("ROLLED_BACK");
    }

    /** [AC-5] 并发双击回滚（016 P1·05#02）：批次行锁串行化，恰一次回收，后到者幂等返回。 */
    @Test
    @DisplayName("[AC-5] 并发回滚串行化：恰一次回收不二次 bump")
    void ac5_concurrentRollbackRecyclesExactlyOnce() throws Exception {
        long bankId = seedBank();
        String batchId = previewAndCommit(bankId, fileJson(3, 0));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ImportBatchViewResponse> first =
                    () -> {
                        start.await();
                        return importBatchService.rollback(batchId, "并发回滚-1", ADMIN);
                    };
            Callable<ImportBatchViewResponse> second =
                    () -> {
                        start.await();
                        return importBatchService.rollback(batchId, "并发回滚-2", ADMIN);
                    };
            Future<ImportBatchViewResponse> firstFuture = pool.submit(first);
            Future<ImportBatchViewResponse> secondFuture = pool.submit(second);
            start.countDown();

            assertThat(firstFuture.get(45, TimeUnit.SECONDS).status()).isEqualTo("ROLLED_BACK");
            assertThat(secondFuture.get(45, TimeUnit.SECONDS).status()).isEqualTo("ROLLED_BACK");
        } finally {
            pool.shutdownNow();
        }
        // 恰一次回收：3 题全部 RECYCLED、银行修订号 = commit 3 + 回滚 3 = 6；二次回收会是 9。
        Long recycledCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE bank_id = ? AND status = 'RECYCLED'",
                        Long.class,
                        bankId);
        assertThat(recycledCount).isEqualTo(3L);
        assertThat(bankVersion(bankId)).isEqualTo(6L);
    }

    /** [AC-5] 并发 rollback+commit（016 P1·05#02）：行锁串行化后状态机不撕裂。 */
    @Test
    @DisplayName("[AC-5] 并发 rollback+commit 状态机收敛")
    void ac5_concurrentRollbackAndCommitConverge() throws Exception {
        long bankId = seedBank();
        String batchId = previewBatch(bankId, fileJson(2, 0));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        boolean rollbackSucceeded = false;
        try {
            Callable<Object> commit =
                    () -> {
                        start.await();
                        return importBatchService.commit(batchId, ADMIN);
                    };
            Callable<Object> rollback =
                    () -> {
                        start.await();
                        return importBatchService.rollback(batchId, "并发竞态", ADMIN);
                    };
            Future<Object> commitFuture = pool.submit(commit);
            Future<Object> rollbackFuture = pool.submit(rollback);
            start.countDown();

            // commit 对合法 PREVIEWED 批次必然成功；rollback 先于 commit 拿到行锁则 40904。
            assertThat(commitFuture.get(45, TimeUnit.SECONDS))
                    .isInstanceOf(ImportBatchViewResponse.class);
            try {
                rollbackFuture.get(45, TimeUnit.SECONDS);
                rollbackSucceeded = true;
            } catch (ExecutionException execution) {
                assertThat(execution.getCause())
                        .isInstanceOfSatisfying(
                                BusinessException.class,
                                exception -> {
                                    assertThat(exception.apiCode())
                                            .isEqualTo(ApiCode.IMPORT_NOT_COMMITTABLE);
                                    assertThat(exception.getMessage()).contains("只有已提交的批次可以回滚");
                                });
            }
        } finally {
            pool.shutdownNow();
        }

        // 终态二选一且自洽：ROLLED_BACK 意味着回收+二次 bump 都已发生；COMMITTED 意味着零回收。
        ImportBatchViewResponse finalBatch =
                importBatchService.detail(batchId, 1, 20, null).batch();
        Long recycledCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sentences WHERE bank_id = ? AND status = 'RECYCLED'",
                        Long.class,
                        bankId);
        if (rollbackSucceeded) {
            assertThat(finalBatch.status()).isEqualTo("ROLLED_BACK");
            assertThat(recycledCount).isEqualTo(2L);
            assertThat(bankVersion(bankId)).isEqualTo(4L);
        } else {
            assertThat(finalBatch.status()).isEqualTo("COMMITTED");
            assertThat(recycledCount).isZero();
            assertThat(bankVersion(bankId)).isEqualTo(2L);
        }
    }

    /**
     * 读取银行级修订号。
     *
     * @param bankId 题库 id
     * @return 当前版本号
     */
    private Long bankVersion(long bankId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM sentence_banks WHERE id = ?", Long.class, bankId);
    }

    /** [AC-3] CSV 导入含逗号 chunks（016 P1·05#04）：RFC4180 解析后整批可预览可提交，不再 CHUNKS_EMPTY。 */
    @Test
    @DisplayName("[AC-3] CSV 含逗号 chunks 可预览可提交")
    void ac3_csvImportWithCommaChunksCommits() {
        long bankId = seedBank();
        // zh/ref/chunks 三个单元格自身含逗号，全部按 RFC4180 引号包裹；chunks 内部引号用 "" 转义。
        String csv =
                "externalKey,zh,ref,note,chunks,blank,tags\n"
                        + "c_1,\"这家餐厅的菜品,很出名。\",\"Dishes with, commas work.\","
                        + "这是 CSV 含逗号语块的解析说明满足最小长度。,"
                        + "\"[{\"\"index\"\":0,\"\"zh\"\":\"\"这\"\",\"\"answers\"\":[\"\"Dishes with, commas work.\"\"],"
                        + "\"\"tag\"\":\"\"逗号\"\"}]\","
                        + "[0],[\"逗号\"]\n";

        ImportPreviewResponse preview =
                importBatchService.preview(
                        new MockMultipartFile(
                                "file",
                                "batch.csv",
                                "text/csv",
                                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        bankId,
                        "CSV",
                        ADMIN);

        assertThat(preview.totalRows()).isEqualTo(1);
        assertThat(preview.validRows()).as("含逗号 chunks 不再断列降级").isEqualTo(1);
        assertThat(preview.invalidRows()).isZero();

        ImportBatchViewResponse committed = importBatchService.commit(preview.batchId(), ADMIN);
        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(committed.importedRows()).isEqualTo(1);
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
                "file",
                filename,
                "application/json",
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            rows.add(
                    rowJson(
                            "s_" + index,
                            "第 " + index + " 句越来越常见。",
                            "Sentence " + index + " is common."));
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
