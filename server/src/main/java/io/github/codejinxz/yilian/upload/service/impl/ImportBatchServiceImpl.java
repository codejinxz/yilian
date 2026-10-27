package io.github.codejinxz.yilian.upload.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.storage.ObjectStorage;
import io.github.codejinxz.yilian.common.util.IdUtils;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.upload.config.ImportAsyncConfig;
import io.github.codejinxz.yilian.upload.dao.ImportBatchDao;
import io.github.codejinxz.yilian.upload.dto.ImportBatchAccepted;
import io.github.codejinxz.yilian.upload.dto.ImportBatchErrorsResponse;
import io.github.codejinxz.yilian.upload.dto.ImportBatchStatusResponse;
import io.github.codejinxz.yilian.upload.dto.ImportRowError;
import io.github.codejinxz.yilian.upload.dto.ImportUploadCommand;
import io.github.codejinxz.yilian.upload.entity.ImportBatch;
import io.github.codejinxz.yilian.upload.entity.ImportBatchStatus;
import io.github.codejinxz.yilian.upload.service.ImportBatchService;
import io.github.codejinxz.yilian.upload.support.ImportFileDescriptor;
import io.github.codejinxz.yilian.upload.support.ImportFilePolicy;
import io.github.codejinxz.yilian.upload.support.StorageLineReader;
import io.github.codejinxz.yilian.upload.validation.ImportFileFormat;
import io.github.codejinxz.yilian.upload.validation.ImportRow;
import io.github.codejinxz.yilian.upload.validation.ImportRowValidator;
import io.github.codejinxz.yilian.upload.validation.ImportValidationResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 导入批次服务实现：流式落对象存储、异步逐行校验、按 100 行持久化进度。
 *
 * <p><strong>事务边界</strong>：本类刻意不对整个处理过程加 {@code @Transactional}。一次导入可能 持续数秒到数分钟，长事务会长期占用数据库连接；进度通过
 * DAO 的独立事务每 100 行提交一次， 失败时只丢失最近一个检查点之后的内存计数。当前不写任何业务表，后续写 {@code question_versions/chunks}
 * 时必须另起一个短事务，并在整批校验通过后执行。
 *
 * <p><strong>幂等与并发</strong>：同一批次用进程内 {@code running} 集合保证只有一个工作线程； {@code COMPLETED} 在 Service
 * 层提前返回，实体层再次拒绝重跑。单实例部署下足够；将来多副本时 需要把该集合换成数据库锁或状态 CAS，不能只依赖进程内集合。
 *
 * <p><strong>失败语义</strong>：行级校验失败只累加 {@code failedRows}，批次仍可正常 COMPLETED；
 * 只有存储读取失败、进度持久化失败、未预期运行时异常才把整批标记 FAILED，并保留最近检查点供 resume。错误摘要最多保留 20 条，避免把 5MB 文件内容留在内存或日志里。
 */
@Service
public class ImportBatchServiceImpl implements ImportBatchService {

    private static final Logger log = LoggerFactory.getLogger(ImportBatchServiceImpl.class);

    /** 启动恢复时写入的暂停原因，管理员可据此判断批次可以继续。 */
    private static final String RECOVERY_PAUSE_MESSAGE = "服务重启，导入任务已暂停，可调用 resume 接口续跑";

    /** 每处理多少行提交一次进度，与任务要求的「按 100 行持久化进度」一致。 */
    private static final int CHECKPOINT_INTERVAL = 100;

    /** errors 接口最多返回的行级样例数，防止大文件失败时响应膨胀。 */
    private static final int MAX_ERROR_SAMPLES = 20;

    /** 单条错误原因最大字符数，只保留可读摘要。 */
    private static final int MAX_ERROR_REASON_LENGTH = 200;

    /** 对象键按 UTC 年月分目录，便于对象存储生命周期策略按目录清理。 */
    private static final DateTimeFormatter STORAGE_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

    private final ObjectStorage storage;

    private final ImportBatchDao importBatchDao;

    private final List<ImportRowValidator> validators;

    private final ExecutorService executor;

    /** 当前进程内正在处理的批次；避免重复 resume 或上传后的调度重入。 */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /** 每个批次最近的行级错误样例；不持久化，应用重启后只保留数据库计数。 */
    private final Map<String, List<ImportRowError>> errorSamples = new ConcurrentHashMap<>();

    /**
     * 注入存储、Dao、校验器链与专用线程池。
     *
     * @param storage 对象存储
     * @param importBatchDao 批次 Dao
     * @param validators 所有行校验器；默认实现做结构校验，后续题库校验器自动加入链尾
     * @param executor 导入专用线程池，避免占用 Web 请求线程
     */
    public ImportBatchServiceImpl(
            ObjectStorage storage,
            ImportBatchDao importBatchDao,
            List<ImportRowValidator> validators,
            @Qualifier(ImportAsyncConfig.IMPORT_EXECUTOR) ExecutorService executor) {
        this.storage = storage;
        this.importBatchDao = importBatchDao;
        this.validators = List.copyOf(validators);
        this.executor = executor;
    }

    /**
     * 校验上传元数据、流式写对象存储、创建批次并调度异步处理。
     *
     * @param command 上传输入
     * @return 受理视图；状态固定为创建瞬间的 {@code UPLOADED}
     * @throws BusinessException 元数据不合法时抛出（扩展名/MIME 为 415，空文件为 400）
     * @throws io.github.codejinxz.yilian.upload.exception.ImportFileTooLargeException 文件超过 5MB
     */
    @Override
    public ImportBatchAccepted upload(ImportUploadCommand command) {
        ImportFileDescriptor descriptor = ImportFilePolicy.validate(command);
        String storageKey = newStorageKey(descriptor.format());
        try {
            storage.put(
                    storageKey, command.content(), command.sizeBytes(), descriptor.contentType());
        } catch (IOException exception) {
            throw new UncheckedIOException("导入文件写入对象存储失败", exception);
        }

        ImportBatch batch;
        try {
            batch =
                    ImportBatch.uploaded(
                            IdUtils.newUuid(),
                            descriptor.safeFilename(),
                            storageKey,
                            command.sizeBytes(),
                            descriptor.contentType(),
                            StringUtils.defaultIfBlank(command.createdBy(), "unknown"),
                            Instant.now());
            importBatchDao.save(batch);
        } catch (RuntimeException exception) {
            // 数据库失败时删除刚写入的对象，避免留下无批次引用的孤儿文件。
            deleteQuietly(storageKey);
            throw exception;
        }

        ImportBatchAccepted accepted = ImportBatchAccepted.from(batch);
        schedule(batch.getId());
        return accepted;
    }

    /**
     * 查询批次状态。
     *
     * @param batchId 批次 id
     * @return 状态与计数
     * @throws BusinessException 批次不存在时抛出 404
     */
    @Override
    public ImportBatchStatusResponse status(String batchId) {
        return ImportBatchStatusResponse.from(require(batchId));
    }

    /**
     * 从最近检查点续跑；已完成批次幂等返回，正在处理的批次直接返回当前状态。
     *
     * @param batchId 批次 id
     * @return 续跑受理后的状态视图
     * @throws BusinessException 批次不存在时抛出 404
     */
    @Override
    public ImportBatchStatusResponse resume(String batchId) {
        ImportBatch batch = require(batchId);
        if (batch.isCompleted() || running.contains(batchId)) {
            return ImportBatchStatusResponse.from(batch);
        }
        batch.markProcessing();
        importBatchDao.save(batch);
        // 上次失败时的行级样例属于未持久化的旧进度，续跑前清掉，避免展示过期错误。
        errorSamples.remove(batchId);
        ImportBatchStatusResponse response = ImportBatchStatusResponse.from(batch);
        schedule(batchId);
        return response;
    }

    /**
     * 查询失败行摘要。
     *
     * @param batchId 批次 id
     * @return 失败计数与最近的行级样例
     * @throws BusinessException 批次不存在时抛出 404
     */
    @Override
    public ImportBatchErrorsResponse errors(String batchId) {
        ImportBatch batch = require(batchId);
        List<ImportRowError> samples = errorSamples.get(batchId);
        if (samples == null) {
            return ImportBatchErrorsResponse.of(batch, List.of());
        }
        synchronized (samples) {
            return ImportBatchErrorsResponse.of(batch, new ArrayList<>(samples));
        }
    }

    /**
     * 启动恢复：把上次进程遗留的 {@code PROCESSING} 批次转 {@code PAUSED}。
     *
     * <p>导入线程只存在于进程内，进程退出后批次不会自动恢复；如果永远停在 PROCESSING，管理员既看不到失败原因， 也无法判断能否续跑。这里统一转 PAUSED
     * 并写入提示，由管理员显式调用 {@link #resume(String)}，避免重启后无声地重复处理文件。
     *
     * <p>单个批次失败只记日志、不向上抛出，避免一个坏批次阻塞应用启动；查询本身失败仍会抛出， 因为此时无法确认是否存在遗留批次。 该实现依赖单实例部署（AGENTS.md
     * 明确不做多副本），启动时不会有其他实例正在写同一批次；将来多副本时应改为带实例标识的租约或数据库状态 CAS。
     */
    @Override
    public void recoverInterrupted() {
        for (ImportBatch batch : importBatchDao.findByStatus(ImportBatchStatus.PROCESSING)) {
            try {
                batch.pause(RECOVERY_PAUSE_MESSAGE);
                importBatchDao.save(batch);
                log.warn("import batch {} paused after restart", batch.getId());
            } catch (RuntimeException exception) {
                log.error("failed to pause stale import batch {}", batch.getId(), exception);
            }
        }
    }

    /**
     * 异步工作线程入口：加载批次、逐行校验、写进度，最后完成或失败。
     *
     * @param batchId 批次 id
     */
    private void process(String batchId) {
        if (!running.add(batchId)) {
            return;
        }
        try {
            ImportBatch batch = importBatchDao.findById(batchId).orElse(null);
            if (batch == null) {
                log.warn("import batch {} disappeared before processing", batchId);
                return;
            }
            if (batch.isCompleted()) {
                return;
            }
            batch.markProcessing();
            importBatchDao.save(batch);

            ImportFileFormat format =
                    ImportFileFormat.fromFilename(batch.getOriginalFilename())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "导入文件扩展名不受支持: " + batch.getOriginalFilename()));
            Progress progress = processRows(batch, format);
            batch.applyProgress(
                    progress.totalRows(),
                    progress.processedRows(),
                    progress.successRows(),
                    progress.failedRows());
            batch.complete();
            importBatchDao.save(batch);
            log.info(
                    "import batch {} completed: total={}, success={}, failed={}",
                    batchId,
                    batch.getTotalRows(),
                    batch.getSuccessRows(),
                    batch.getFailedRows());
        } catch (Exception exception) {
            log.error("import batch {} failed", batchId, exception);
            markFailed(batchId, exception);
        } finally {
            running.remove(batchId);
        }
    }

    /**
     * 从 {@code resumeOffset} 之后逐行消费文件；返回调用方尚未落库的最终计数。
     *
     * <p>每 {@value #CHECKPOINT_INTERVAL} 行调用一次 {@link ImportBatch#applyProgress} 并保存，
     * 因此异常发生时实体停在上一个检查点。跳过行数在循环开始前固定，不能被检查点更新后的 {@code resumeOffset} 影响，否则同一批次的后续行会被误跳过。
     *
     * @param batch 批次
     * @param format 文件格式
     * @return 最终计数
     * @throws IOException 存储读取失败时抛出
     */
    private Progress processRows(ImportBatch batch, ImportFileFormat format) throws IOException {
        long skipLines = batch.getResumeOffset();
        long[] counters = {
            batch.getTotalRows(),
            batch.getProcessedRows(),
            batch.getSuccessRows(),
            batch.getFailedRows()
        };
        long[] lineNumber = {0};
        long[] sinceCheckpoint = {0};

        StorageLineReader.forEachLine(
                storage,
                batch.getStorageKey(),
                rawLine -> {
                    lineNumber[0]++;
                    if (lineNumber[0] <= skipLines) {
                        return;
                    }
                    counters[0]++;
                    counters[1]++;
                    ImportValidationResult result =
                            validate(new ImportRow(lineNumber[0], format, rawLine));
                    if (result.valid()) {
                        counters[2]++;
                    } else {
                        counters[3]++;
                        recordError(batch.getId(), lineNumber[0], result.reason());
                    }
                    sinceCheckpoint[0]++;
                    if (sinceCheckpoint[0] >= CHECKPOINT_INTERVAL) {
                        batch.applyProgress(counters[0], counters[1], counters[2], counters[3]);
                        importBatchDao.save(batch);
                        sinceCheckpoint[0] = 0;
                    }
                });

        return new Progress(counters[0], counters[1], counters[2], counters[3]);
    }

    /**
     * 依次运行校验器链，第一个失败即返回。
     *
     * @param row 待校验行
     * @return 通过或失败结果
     */
    private ImportValidationResult validate(ImportRow row) {
        for (ImportRowValidator validator : validators) {
            ImportValidationResult result = validator.validate(row);
            if (result == null) {
                throw new IllegalStateException(
                        "ImportRowValidator 不能返回 null: " + validator.getClass().getName());
            }
            if (!result.valid()) {
                return result;
            }
        }
        return ImportValidationResult.ok();
    }

    /**
     * 记录一条行级错误样例，超过上限后只保留计数。
     *
     * @param batchId 批次 id
     * @param lineNumber 行号
     * @param reason 失败原因
     */
    private void recordError(String batchId, long lineNumber, String reason) {
        List<ImportRowError> samples =
                errorSamples.computeIfAbsent(
                        batchId, ignored -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (samples) {
            if (samples.size() < MAX_ERROR_SAMPLES) {
                samples.add(
                        new ImportRowError(
                                lineNumber, StringUtils.truncate(reason, MAX_ERROR_REASON_LENGTH)));
            }
        }
    }

    /**
     * 把批次标记为 FAILED；失败本身不能再次抛出，否则工作线程会带着未清理的 running 标记退出。
     *
     * @param batchId 批次 id
     * @param cause 失败原因
     */
    private void markFailed(String batchId, Exception cause) {
        try {
            importBatchDao
                    .findById(batchId)
                    .ifPresent(
                            batch -> {
                                if (batch.isCompleted()) {
                                    return;
                                }
                                batch.fail(failureMessage(cause));
                                importBatchDao.save(batch);
                            });
        } catch (RuntimeException persistenceFailure) {
            log.error(
                    "failed to persist FAILED status for import batch {}",
                    batchId,
                    persistenceFailure);
        }
    }

    /**
     * 把异常转成可给管理员看的摘要，不包含堆栈；实体负责按 1000 字符截断。
     *
     * @param cause 失败原因
     * @return 中文摘要
     */
    private static String failureMessage(Exception cause) {
        String message = cause.getMessage();
        if (StringUtils.isNotBlank(message)) {
            return message;
        }
        return "导入处理失败: " + cause.getClass().getSimpleName();
    }

    /**
     * 调度异步处理；线程池拒绝时直接把批次标记失败，避免它永远停在 UPLOADED。
     *
     * @param batchId 批次 id
     */
    private void schedule(String batchId) {
        try {
            executor.execute(() -> process(batchId));
        } catch (RuntimeException exception) {
            log.error("failed to schedule import batch {}", batchId, exception);
            markFailed(batchId, exception);
        }
    }

    /**
     * 生成服务端完全可控的对象键，不使用用户文件名。
     *
     * @param format 文件格式
     * @return 形如 {@code imports/2026/10/<uuid>.csv} 的键
     */
    private static String newStorageKey(ImportFileFormat format) {
        String month = LocalDate.now(ZoneOffset.UTC).format(STORAGE_MONTH);
        return "imports/" + month + "/" + IdUtils.newUuid() + format.extension();
    }

    /**
     * 按 id 取批次，不存在时统一 404，避免泄露内部数据。
     *
     * @param batchId 批次 id
     * @return 批次
     * @throws BusinessException id 为空或批次不存在时抛出
     */
    private ImportBatch require(String batchId) {
        if (StringUtils.isBlank(batchId)) {
            throw new BusinessException(ApiCode.NOT_FOUND, "导入批次不存在");
        }
        return importBatchDao
                .findById(batchId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "导入批次不存在"));
    }

    /**
     * 删除对象，失败只记 WARN；孤儿对象比丢失原始异常更容易清理。
     *
     * @param storageKey 对象键
     */
    private void deleteQuietly(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException exception) {
            log.warn("failed to delete orphan import object {}", storageKey, exception);
        }
    }

    /**
     * 一批文件处理后的最终计数，尚未写入实体。
     *
     * @param totalRows 已读到的行数
     * @param processedRows 已处理行数
     * @param successRows 成功行数
     * @param failedRows 失败行数
     */
    private record Progress(
            long totalRows, long processedRows, long successRows, long failedRows) {}
}
