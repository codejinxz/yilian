package io.github.codejinxz.yilian.upload.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.upload.dto.ImportBatchAccepted;
import io.github.codejinxz.yilian.upload.dto.ImportBatchErrorsResponse;
import io.github.codejinxz.yilian.upload.dto.ImportBatchStatusResponse;
import io.github.codejinxz.yilian.upload.dto.ImportUploadCommand;
import io.github.codejinxz.yilian.upload.entity.ImportBatch;
import io.github.codejinxz.yilian.upload.entity.ImportBatchStatus;
import io.github.codejinxz.yilian.upload.support.InMemoryImportBatchDao;
import io.github.codejinxz.yilian.upload.support.InMemoryObjectStorage;
import io.github.codejinxz.yilian.upload.validation.DefaultImportRowValidator;
import io.github.codejinxz.yilian.upload.validation.ImportRow;
import io.github.codejinxz.yilian.upload.validation.ImportRowValidator;
import io.github.codejinxz.yilian.upload.validation.ImportValidationResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 导入异步处理测试：不依赖 MySQL / Redis / 真实对象存储。
 *
 * <p>用内存 Dao、内存对象存储与单线程执行器覆盖：正常完成、行级失败计数、100 行检查点、 失败后从 resume_offset 续跑、完成态幂等、处理中状态可见、启动恢复与 404。
 */
class ImportBatchServiceImplTest {

    private InMemoryImportBatchDao dao;

    private InMemoryObjectStorage storage;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        dao = new InMemoryImportBatchDao();
        storage = new InMemoryObjectStorage();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 合法 CSV 应全部计为成功并进入 COMPLETED。 */
    @Test
    void processesCsvAndCountsRows() {
        ImportBatchServiceImpl service = service(new DefaultImportRowValidator(new ObjectMapper()));
        String batchId = upload(service, "sentences.csv", "text/csv", "zh,ref\n他,He\n她,She\n");

        ImportBatchStatusResponse status =
                awaitStatus(service, batchId, ImportBatchStatus.COMPLETED);

        assertThat(status.totalRows()).isEqualTo(3);
        assertThat(status.processedRows()).isEqualTo(3);
        assertThat(status.successRows()).isEqualTo(3);
        assertThat(status.failedRows()).isZero();
        assertThat(storage.getCount()).isEqualTo(1);
    }

    /** 空行、超长、非法 JSON 行只增加失败计数，批次仍可完成。 */
    @Test
    void rowFailuresAreCountedButBatchCompletes() {
        ImportBatchServiceImpl service = service(new DefaultImportRowValidator(new ObjectMapper()));
        String content =
                "{\"zh\":\"a\"}\n"
                        + "\n"
                        + "{bad json}\n"
                        + "x".repeat(DefaultImportRowValidator.MAX_LINE_LENGTH + 1)
                        + "\n";

        String batchId = upload(service, "sentences.json", "application/json", content);
        ImportBatchStatusResponse status =
                awaitStatus(service, batchId, ImportBatchStatus.COMPLETED);

        assertThat(status.totalRows()).isEqualTo(4);
        assertThat(status.successRows()).isEqualTo(1);
        assertThat(status.failedRows()).isEqualTo(3);

        ImportBatchErrorsResponse errors = service.errors(batchId);
        assertThat(errors.failedRows()).isEqualTo(3);
        assertThat(errors.errors()).hasSize(3);
        assertThat(errors.errors()).extracting("reason").contains("空行", "非法 JSON 行");
    }

    /** 每 100 行应有一次 PROCESSING 快照，证明进度不是只在结束时落库。 */
    @Test
    void persistsProgressEveryHundredRows() {
        ImportBatchServiceImpl service = service(new DefaultImportRowValidator(new ObjectMapper()));
        String batchId = upload(service, "sentences.json", "application/json", jsonLines(250));

        awaitStatus(service, batchId, ImportBatchStatus.COMPLETED);

        assertThat(dao.snapshots())
                .anySatisfy(
                        snapshot -> {
                            assertThat(snapshot.status()).isEqualTo(ImportBatchStatus.PROCESSING);
                            assertThat(snapshot.processedRows()).isEqualTo(100);
                            assertThat(snapshot.resumeOffset()).isEqualTo(100);
                        })
                .anySatisfy(
                        snapshot -> {
                            assertThat(snapshot.status()).isEqualTo(ImportBatchStatus.PROCESSING);
                            assertThat(snapshot.processedRows()).isEqualTo(200);
                        });
    }

    /** 处理中抛异常应标记 FAILED 并停在最近检查点，resume 从 100 行后继续。 */
    @Test
    void resumesFromLastCheckpointAfterFailure() {
        ImportBatchServiceImpl service = service(new FailOnceValidator(151));
        String batchId = upload(service, "sentences.json", "application/json", jsonLines(250));

        ImportBatchStatusResponse failed = awaitStatus(service, batchId, ImportBatchStatus.FAILED);
        assertThat(failed.processedRows()).isEqualTo(100);
        assertThat(failed.resumeOffset()).isEqualTo(100);
        assertThat(failed.errorMessage()).contains("模拟处理失败");

        ImportBatchStatusResponse resumed = service.resume(batchId);
        assertThat(resumed.status()).isEqualTo(ImportBatchStatus.PROCESSING);

        ImportBatchStatusResponse completed =
                awaitStatus(service, batchId, ImportBatchStatus.COMPLETED);
        assertThat(completed.totalRows()).isEqualTo(250);
        assertThat(completed.processedRows()).isEqualTo(250);
        assertThat(completed.successRows()).isEqualTo(250);
        assertThat(completed.failedRows()).isZero();
        assertThat(completed.resumeOffset()).isEqualTo(250);
        assertThat(storage.getCount()).isEqualTo(2);
    }

    /** 已完成批次 resume 必须直接返回，不重新读文件、不重复计数。 */
    @Test
    void resumeCompletedBatchIsIdempotent() {
        ImportBatchServiceImpl service = service(new DefaultImportRowValidator(new ObjectMapper()));
        String batchId = upload(service, "sentences.json", "application/json", jsonLines(3));
        awaitStatus(service, batchId, ImportBatchStatus.COMPLETED);
        int readsBeforeResume = storage.getCount();

        ImportBatchStatusResponse response = service.resume(batchId);

        assertThat(response.status()).isEqualTo(ImportBatchStatus.COMPLETED);
        assertThat(response.processedRows()).isEqualTo(3);
        assertThat(storage.getCount()).isEqualTo(readsBeforeResume);
    }

    /** 工作线程阻塞在校验时，status 应能观察到 PROCESSING。 */
    @Test
    void statusShowsProcessingWhileWorkerIsRunning() throws Exception {
        BlockingValidator validator = new BlockingValidator();
        ImportBatchServiceImpl service = service(validator);
        String batchId = upload(service, "sentences.json", "application/json", jsonLines(2));

        assertThat(validator.entered.await(5, TimeUnit.SECONDS)).isTrue();
        ImportBatchStatusResponse processing = service.status(batchId);
        assertThat(processing.status()).isEqualTo(ImportBatchStatus.PROCESSING);

        validator.release.countDown();
        ImportBatchStatusResponse completed =
                awaitStatus(service, batchId, ImportBatchStatus.COMPLETED);
        assertThat(completed.successRows()).isEqualTo(2);
    }

    /** 不存在的批次统一 404，不暴露内部细节。 */
    @Test
    void unknownBatchReturnsNotFound() {
        ImportBatchServiceImpl service = service(new DefaultImportRowValidator(new ObjectMapper()));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.status("missing")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.apiCode()).isEqualTo(ApiCode.NOT_FOUND));
    }

    /** 启动恢复应把上次进程遗留的 PROCESSING 批次转为 PAUSED，并写入续跑提示。 */
    @Test
    void recoverInterruptedPausesStaleProcessingBatches() {
        ImportBatchServiceImpl service = service(new DefaultImportRowValidator(new ObjectMapper()));
        ImportBatch batch =
                ImportBatch.uploaded(
                        "stale-batch",
                        "sentences.csv",
                        "imports/2026/10/stale-batch.csv",
                        16,
                        "text/csv",
                        "admin",
                        Instant.now());
        batch.markProcessing();
        dao.save(batch);

        service.recoverInterrupted();

        ImportBatchStatusResponse recovered = service.status("stale-batch");
        assertThat(recovered.status()).isEqualTo(ImportBatchStatus.PAUSED);
        assertThat(recovered.errorMessage()).contains("服务重启");
    }

    /**
     * 构造使用单线程执行器的服务。
     *
     * @param validator 行校验器
     * @return 服务实例
     */
    private ImportBatchServiceImpl service(ImportRowValidator validator) {
        return new ImportBatchServiceImpl(storage, dao, List.of(validator), executor);
    }

    /**
     * 上传内容并返回批次 id。
     *
     * @param service 服务
     * @param filename 文件名
     * @param contentType MIME
     * @param content 文本内容
     * @return 批次 id
     */
    private static String upload(
            ImportBatchServiceImpl service, String filename, String contentType, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ImportBatchAccepted accepted =
                service.upload(
                        new ImportUploadCommand(
                                filename,
                                contentType,
                                bytes.length,
                                new ByteArrayInputStream(bytes),
                                "admin"));
        return accepted.batchId();
    }

    /**
     * 轮询直到目标状态或超时。
     *
     * @param service 服务
     * @param batchId 批次 id
     * @param expected 目标状态
     * @return 目标状态下的响应
     */
    private static ImportBatchStatusResponse awaitStatus(
            ImportBatchServiceImpl service, String batchId, ImportBatchStatus expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ImportBatchStatusResponse last = null;
        while (System.nanoTime() < deadline) {
            last = service.status(batchId);
            if (last.status() == expected) {
                return last;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail("等待导入状态时被中断", exception);
            }
        }
        fail("等待状态 " + expected + " 超时，最后状态: " + (last == null ? "null" : last.status()));
        return last;
    }

    /**
     * 生成指定行数的 JSON Lines，每行一个对象。
     *
     * @param rows 行数
     * @return 文件内容
     */
    private static String jsonLines(int rows) {
        StringBuilder builder = new StringBuilder(rows * 16);
        for (int index = 1; index <= rows; index++) {
            builder.append("{\"id\":").append(index).append("}\n");
        }
        return builder.toString();
    }

    /** 在第 N 次校验时抛一次运行时异常，模拟处理中途失败。 */
    private static final class FailOnceValidator implements ImportRowValidator {

        private final int failAtCall;

        private final AtomicInteger calls = new AtomicInteger();

        /**
         * 创建一次性失败校验器。
         *
         * @param failAtCall 第几次调用时抛异常，从 1 开始
         */
        private FailOnceValidator(int failAtCall) {
            this.failAtCall = failAtCall;
        }

        /**
         * 命中指定次数时抛异常，其余行通过。
         *
         * @param row 行
         * @return 通过
         */
        @Override
        public ImportValidationResult validate(ImportRow row) {
            if (calls.incrementAndGet() == failAtCall) {
                throw new IllegalStateException("模拟处理失败");
            }
            return ImportValidationResult.ok();
        }
    }

    /** 用两个闩锁把工作线程卡在 PROCESSING，便于断言中间状态。 */
    private static final class BlockingValidator implements ImportRowValidator {

        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        /**
         * 第一次校验时通知测试并等待释放。
         *
         * @param row 行
         * @return 通过
         */
        @Override
        public ImportValidationResult validate(ImportRow row) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待测试释放超时");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("校验被中断", exception);
            }
            return ImportValidationResult.ok();
        }
    }
}
