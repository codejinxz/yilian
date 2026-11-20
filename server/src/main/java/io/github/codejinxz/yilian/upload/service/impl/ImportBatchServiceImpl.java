package io.github.codejinxz.yilian.upload.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.common.storage.ObjectStorage;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.common.util.TextNormalizer;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.upload.dao.ImportBatchDao;
import io.github.codejinxz.yilian.upload.dao.ImportBatchQueryDao;
import io.github.codejinxz.yilian.upload.dao.ImportBatchRowDao;
import io.github.codejinxz.yilian.upload.dto.ImportBatchDetailResponse;
import io.github.codejinxz.yilian.upload.dto.ImportBatchListItem;
import io.github.codejinxz.yilian.upload.dto.ImportBatchRowItem;
import io.github.codejinxz.yilian.upload.dto.ImportBatchViewResponse;
import io.github.codejinxz.yilian.upload.dto.ImportErrorSummary;
import io.github.codejinxz.yilian.upload.dto.ImportPreviewResponse;
import io.github.codejinxz.yilian.upload.dto.ImportRowError;
import io.github.codejinxz.yilian.upload.dto.ImportRowView;
import io.github.codejinxz.yilian.upload.entity.ImportBatch;
import io.github.codejinxz.yilian.upload.entity.ImportBatchRow;
import io.github.codejinxz.yilian.upload.entity.ImportBatchRowStatus;
import io.github.codejinxz.yilian.upload.service.ImportBatchService;
import io.github.codejinxz.yilian.upload.support.ImportFileDescriptor;
import io.github.codejinxz.yilian.upload.support.ImportFilePolicy;
import io.github.codejinxz.yilian.upload.validation.ImportBusinessValidator;
import io.github.codejinxz.yilian.upload.validation.ImportRowParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 导入批次服务实现（preview→commit→rollback 编排）。
 *
 * <p>事务边界：preview 的「批次 + 行」单事务落库（对象存储写入在事务窗口内但非事务资源，落库失败清孤儿文件补偿）； commit 是**一个**原子事务—— 「锁批次行 → 复检状态
 * → 内容快照/题目落库（{@code ContentImportService} 同事务加入）→ 行回写 → COMMITTED」， 任一步失败整体回滚并补记 FAILED，
 * 不再出现「题目已入库而批次停在 COMMITTING」的死锁状态； rollback 同理为单事务（锁批次行 + 锁内复检 + recycle 同事务）。残留的 COMMITTING 批次由
 * {@link #reconcileCommittingBatches()} 按行数据对账收敛。
 */
@Service
public class ImportBatchServiceImpl implements ImportBatchService {

    private static final Logger log = LoggerFactory.getLogger(ImportBatchServiceImpl.class);

    /** 预览响应里行明细的最大条数（全量明细走详情分页）。 */
    private static final int PREVIEW_ROW_LIMIT = 100;

    /** 错误摘要抽样行数。 */
    private static final int SUMMARY_SAMPLE_ROWS = 3;

    private final ObjectStorage objectStorage;

    private final ContentImportService contentImportService;

    private final ImportBatchDao importBatchDao;

    private final ImportBatchRowDao importBatchRowDao;

    private final ImportBatchQueryDao importBatchQueryDao;

    private final ImportRowParser rowParser;

    private final ImportBusinessValidator businessValidator;

    private final ObjectMapper objectMapper;

    private final TransactionTemplate transactionTemplate;

    /**
     * 注入存储、content 落库接缝、批次/行 Dao、动态查询 Dao、解析校验器与事务模板。
     *
     * @param objectStorage 对象存储
     * @param contentImportService content 落库接缝
     * @param importBatchDao 批次 Dao
     * @param importBatchRowDao 行 Dao
     * @param importBatchQueryDao 批次/行动态查询 Dao（MyBatis）
     * @param rowParser 行解析器
     * @param businessValidator 业务校验器
     * @param objectMapper Jackson 映射器
     * @param transactionManager 事务管理器（commit 原子事务与对账恢复的编程式事务）
     */
    public ImportBatchServiceImpl(
            ObjectStorage objectStorage,
            ContentImportService contentImportService,
            ImportBatchDao importBatchDao,
            ImportBatchRowDao importBatchRowDao,
            ImportBatchQueryDao importBatchQueryDao,
            ImportRowParser rowParser,
            ImportBusinessValidator businessValidator,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.objectStorage = objectStorage;
        this.contentImportService = contentImportService;
        this.importBatchDao = importBatchDao;
        this.importBatchRowDao = importBatchRowDao;
        this.importBatchQueryDao = importBatchQueryDao;
        this.rowParser = rowParser;
        this.businessValidator = businessValidator;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional
    public ImportPreviewResponse preview(
            MultipartFile file, Long bankId, String format, YilianUserPrincipal actor) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "导入文件不能为空");
        }
        requireFormat(format);
        String derivedFormat = derivedFormat(file.getOriginalFilename(), file.getContentType());
        if (!derivedFormat.equals(format)) {
            throw new BusinessException(
                    ApiCode.VALIDATION_FAILED, "文件扩展名与声明的 format 不一致（应为 " + derivedFormat + "）");
        }
        if (!contentImportService.isImportableBank(bankId)) {
            throw new BusinessException(ApiCode.BANK_NOT_FOUND);
        }

        ImportFileDescriptor descriptor =
                ImportFilePolicy.validate(
                        file.getOriginalFilename(), file.getSize(), file.getContentType());
        byte[] bytes = readBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        String contentHash = TextNormalizer.sha256(text);
        String storageKey = storageKey(descriptor.safeFilename());

        try {
            objectStorage.put(
                    storageKey,
                    new ByteArrayInputStream(bytes),
                    bytes.length,
                    descriptor.contentType());
        } catch (IOException exception) {
            throw new BusinessException(ApiCode.MALFORMED_REQUEST, "导入文件保存失败");
        }
        try {
            List<ImportRowParser.ParsedRow> parsed = rowParser.parse(derivedFormat, text);

            Instant now = Instant.now();
            String batchId = UUID.randomUUID().toString();
            List<ImportBatchRow> rowEntities = new ArrayList<>(parsed.size());
            List<RowOutcome> outcomes = new ArrayList<>(parsed.size());
            for (ImportRowParser.ParsedRow row : parsed) {
                List<ImportRowError> errors =
                        businessValidator.validate(objectMapper.readTree(row.rawJson()));
                ImportBatchRowStatus status =
                        errors.isEmpty()
                                ? ImportBatchRowStatus.VALID
                                : ImportBatchRowStatus.INVALID;
                rowEntities.add(
                        ImportBatchRow.previewed(
                                batchId,
                                row.rowNo(),
                                row.externalKey(),
                                row.rawJson(),
                                status,
                                errors.isEmpty() ? null : writeJson(errors),
                                now));
                outcomes.add(
                        new RowOutcome(
                                row.rowNo(), row.externalKey(), row.rawJson(), status, errors));
            }
            markDuplicates(bankId, outcomes, rowEntities, now);

            long validRows =
                    outcomes.stream().filter(o -> o.status() == ImportBatchRowStatus.VALID).count();
            long invalidRows = outcomes.size() - validRows;
            ImportErrorSummary summary =
                    invalidRows > 0
                            ? new ImportErrorSummary(
                                    ImportErrorSummary.SCHEMA_VERSION,
                                    "ROW_VALIDATION_FAILED",
                                    invalidRows + " 行未通过校验",
                                    outcomes.stream()
                                            .filter(o -> o.status() == ImportBatchRowStatus.INVALID)
                                            .map(RowOutcome::rowNo)
                                            .limit(SUMMARY_SAMPLE_ROWS)
                                            .toList())
                            : null;

            ImportBatch batch =
                    ImportBatch.previewing(
                            batchId,
                            bankId,
                            derivedFormat,
                            descriptor.safeFilename(),
                            storageKey,
                            contentHash,
                            bytes.length,
                            actor.id(),
                            actor.getUsername(),
                            now);
            batch.markPreviewed(
                    outcomes.size(),
                    validRows,
                    invalidRows,
                    summary == null ? null : writeJson(summary),
                    now);
            importBatchDao.save(batch);
            importBatchRowDao.saveAll(rowEntities);

            List<ImportRowView> invalidViews =
                    outcomes.stream()
                            .filter(o -> o.status() == ImportBatchRowStatus.INVALID)
                            .map(o -> toRowView(o, null))
                            .limit(PREVIEW_ROW_LIMIT)
                            .toList();
            return new ImportPreviewResponse(
                    batchId,
                    bankId,
                    batch.getStatus().name(),
                    batch.getFormat(),
                    batch.getTotalRows(),
                    batch.getValidRows(),
                    batch.getInvalidRows(),
                    invalidViews,
                    summary);
        } catch (RuntimeException exception) {
            // 落库失败清孤儿文件；对象存储不在事务内，手动补偿。
            // 补偿失败只告警不吞异常：原始落库失败才是要回给客户端的错误，孤儿文件靠运维清理。
            try {
                objectStorage.delete(storageKey);
            } catch (RuntimeException cleanupException) {
                log.warn(
                        "import_preview_orphan_cleanup_failed storageKey={} reason={}",
                        storageKey,
                        cleanupException.getMessage());
            }
            throw exception;
        }
    }

    @Override
    public ImportBatchViewResponse commit(String batchId, YilianUserPrincipal actor) {
        ImportBatch current = requireBatch(batchId);
        if (current.isCommitted()) {
            return toView(current); // 重复 commit 幂等返回
        }
        if (current.getStatus()
                != io.github.codejinxz.yilian.upload.entity.ImportBatchStatus.PREVIEWED) {
            throw new BusinessException(ApiCode.IMPORT_NOT_COMMITTABLE, "当前批次不可提交");
        }
        try {
            // 单原子事务：内容落库、行回写、状态推进要么全部生效要么全部回滚。
            // 失败后批次仍在 PREVIEWED（COMMITTING 轨迹对外不可见），不产生永久卡死状态。
            ImportBatch committed =
                    transactionTemplate.execute(status -> commitAtomically(batchId, actor));
            return toView(committed);
        } catch (RuntimeException exception) {
            // DB 唯一键兜底冲突（并发批次提交同指纹/同 external_key，预检已放行）不是代码缺陷，
            // 翻译成 40904 业务语义，不让唯一索引冲突冒成 500。
            RuntimeException toRethrow = exception;
            if (causedByDataIntegrityViolation(exception)) {
                toRethrow =
                        new BusinessException(ApiCode.IMPORT_NOT_COMMITTABLE, "题库已存在相同题目（唯一键冲突）");
            }
            // 事务已整体回滚：题目与行回写均未生效。批次仍在 PREVIEWED 时补记 FAILED（终态，
            // 已知取舍：原文件留在对象存储，storage_key 仍挂在批次行上可审计，不构成孤儿）；
            // 状态已被他人改写（对账/并发收敛）则原样上抛，不覆盖别人的状态。
            ImportBatch failed = requireBatch(batchId);
            if (failed.getStatus()
                    == io.github.codejinxz.yilian.upload.entity.ImportBatchStatus.PREVIEWED) {
                failed.markFailed(writeJson(failureSummary(toRethrow)), Instant.now());
                importBatchDao.save(failed);
            }
            throw toRethrow;
        }
    }

    /**
     * commit 的原子事务体：锁批次行 → 锁内复检状态 → markCommitting → 落题目 → 行回写 → markCommitted。
     *
     * <p>先锁批次行再复检状态，并发重复提交在行锁上串行化：等锁后看到 COMMITTED 直接幂等返回。 {@code ContentImportService} 的 REQUIRED
     * 事务加入本事务，与行回写、状态推进同生共死。
     *
     * @param batchId 批次 id
     * @param actor 当前管理员
     * @return 提交完成（或幂等命中）的批次
     */
    private ImportBatch commitAtomically(String batchId, YilianUserPrincipal actor) {
        ImportBatch batch =
                importBatchDao
                        .lockById(batchId)
                        .orElseThrow(() -> new BusinessException(ApiCode.IMPORT_BATCH_NOT_FOUND));
        if (batch.isCommitted()) {
            return batch; // 并发重复 commit：等锁后复检，幂等返回
        }
        if (batch.getStatus()
                != io.github.codejinxz.yilian.upload.entity.ImportBatchStatus.PREVIEWED) {
            throw new BusinessException(ApiCode.IMPORT_NOT_COMMITTABLE, "当前批次不可提交");
        }
        // 状态机仍走 PREVIEWED→COMMITTING→COMMITTED，但 COMMITTING 只存在于本事务内部，
        // 不再作为「提前落库的乐观兜底」暴露给其他事务。
        batch.markCommitting(Instant.now());

        List<ImportBatchRow> validRows =
                importBatchRowDao.findByBatchIdAndStatus(batchId, ImportBatchRowStatus.VALID);
        List<SentenceContent> contents = new ArrayList<>(validRows.size());
        for (ImportBatchRow row : validRows) {
            contents.add(
                    toSentenceContent(objectMapper.readTree(row.getRaw()), row.getExternalKey()));
        }
        var results =
                contentImportService.importSentences(
                        batch.getBankId(), batchId, contents, actor.id(), Instant.now());
        for (int index = 0; index < validRows.size(); index++) {
            validRows
                    .get(index)
                    .markImported(
                            results.get(index).sentenceId(),
                            results.get(index).sentenceVersionId(),
                            Instant.now());
            importBatchRowDao.save(validRows.get(index));
        }
        batch.markCommitted(results.size(), Instant.now());
        return importBatchDao.save(batch);
    }

    @Override
    public int reconcileCommittingBatches() {
        int reconciled = 0;
        // COMMITTING 残留只可能来自旧版本「分事务提交」的历史数据：新 commit 的 COMMITTING
        // 轨迹仅存在于未提交事务内，对其他事务不可见。逐批独立事务收敛，单批失败不影响其余。
        for (ImportBatch stale :
                importBatchDao.findByStatus(
                        io.github.codejinxz.yilian.upload.entity.ImportBatchStatus.COMMITTING)) {
            ImportBatch outcome =
                    transactionTemplate.execute(status -> reconcileOne(stale.getId()));
            if (outcome != null) {
                reconciled++;
            }
        }
        return reconciled;
    }

    /**
     * 收敛单个 COMMITTING 残留批次（独立事务，锁批次行后再裁决）。
     *
     * <p>按行数据判定：行已关联题目 → 内容确已入库，补齐行状态并 markCommitted；行未关联题目 → 提交事务未完成、什么都没入库， 标记
     * FAILED（终态，管理员重新导入新批次）。
     *
     * <p>与 {@link #commitAtomically} 靠批次行锁互斥：锁内重读状态，已不是 COMMITTING （并发 commit 恰好完成）则放弃收敛，避免覆盖提交结果。
     *
     * @param batchId 批次 id
     * @return 收敛后的批次；无需收敛（状态已被改写）时为 null
     */
    private ImportBatch reconcileOne(String batchId) {
        ImportBatch batch = importBatchDao.lockById(batchId).orElse(null);
        if (batch == null
                || batch.getStatus()
                        != io.github.codejinxz.yilian.upload.entity.ImportBatchStatus.COMMITTING) {
            return null;
        }
        List<ImportBatchRow> linkedRows =
                importBatchRowDao.findByBatchId(batchId).stream()
                        .filter(row -> row.getSentenceId() != null)
                        .toList();
        if (linkedRows.isEmpty()) {
            batch.markFailed(
                    writeJson(
                            new ImportErrorSummary(
                                    ImportErrorSummary.SCHEMA_VERSION,
                                    "COMMIT_INTERRUPTED",
                                    "提交中断，对账确认未入库，批次标记失败",
                                    null)),
                    Instant.now());
            log.warn("import_batch_reconciled batchId={} outcome=FAILED", batchId);
        } else {
            for (ImportBatchRow row : linkedRows) {
                if (row.getStatus() == ImportBatchRowStatus.VALID
                        && row.getSentenceVersionId() != null) {
                    // 旧版部分回写残留：行已拿到题目 id 但状态仍 VALID，这里补齐。
                    row.markImported(
                            row.getSentenceId(), row.getSentenceVersionId(), Instant.now());
                    importBatchRowDao.save(row);
                }
            }
            batch.markCommitted(linkedRows.size(), Instant.now());
            log.warn(
                    "import_batch_reconciled batchId={} outcome=COMMITTED importedRows={}",
                    batchId,
                    linkedRows.size());
        }
        return importBatchDao.save(batch);
    }

    @Override
    public ImportBatchViewResponse rollback(
            String batchId, String reason, YilianUserPrincipal actor) {
        // 与 commit 同构的单原子事务：recycle（content 侧自带事务独立提交）
        // 若与批次状态写入拆成两个事务，中途失败会出现「题目已回收、批次仍 COMMITTED」的撕裂态，
        // 再次回滚会对同一批题二次推进银行修订号；并发双击回滚也各自 recycle 一遍。
        ImportBatch rolled =
                transactionTemplate.execute(status -> rollbackAtomically(batchId, reason, actor));
        return toView(rolled);
    }

    /**
     * rollback 的原子事务体：锁批次行 → 锁内复检状态 → 回收题目 → markRolledBack。
     *
     * <p>先锁批次行再复检状态，并发重复回滚在行锁上串行化：等锁后看到 ROLLED_BACK 直接幂等 返回，看到非 COMMITTED 拒绝。{@code
     * recycleImportedSentences} 的 REQUIRED 事务加入本 事务，题目回收与批次状态同生共死。
     *
     * @param batchId 批次 id
     * @param reason 回滚原因
     * @param actor 当前管理员
     * @return 回滚完成（或幂等命中）的批次
     */
    private ImportBatch rollbackAtomically(
            String batchId, String reason, YilianUserPrincipal actor) {
        ImportBatch batch =
                importBatchDao
                        .lockById(batchId)
                        .orElseThrow(() -> new BusinessException(ApiCode.IMPORT_BATCH_NOT_FOUND));
        if (batch.getStatus()
                == io.github.codejinxz.yilian.upload.entity.ImportBatchStatus.ROLLED_BACK) {
            return batch; // 并发重复 rollback：等锁后复检，幂等返回
        }
        if (batch.getStatus()
                != io.github.codejinxz.yilian.upload.entity.ImportBatchStatus.COMMITTED) {
            throw new BusinessException(ApiCode.IMPORT_NOT_COMMITTABLE, "只有已提交的批次可以回滚");
        }
        List<String> sentenceIds =
                importBatchRowDao
                        .findByBatchIdAndStatus(batchId, ImportBatchRowStatus.IMPORTED)
                        .stream()
                        .map(ImportBatchRow::getSentenceId)
                        .toList();
        contentImportService.recycleImportedSentences(
                batch.getBankId(), sentenceIds, actor.id(), Instant.now());
        batch.markRolledBack(actor.id(), reason, Instant.now());
        return importBatchDao.save(batch);
    }

    @Override
    public PageResponse<ImportBatchViewResponse> list(
            int page, int size, Long bankId, String status, String from, String to) {
        requireValidPage(page, size);
        requireKnownStatus(status);
        long offset = (long) (page - 1) * size;
        List<ImportBatchListItem> items =
                importBatchQueryDao.findBatches(
                        bankId, status, utcStart(from), utcEnd(to), offset, size);
        long total = importBatchQueryDao.countBatches(bankId, status, utcStart(from), utcEnd(to));
        List<ImportBatchViewResponse> views = items.stream().map(this::toView).toList();
        return PageResponse.of(views, page, size, total);
    }

    @Override
    public ImportBatchDetailResponse detail(String batchId, int page, int size, String rowStatus) {
        requireValidPage(page, size);
        ImportBatch batch = requireBatch(batchId);
        long offset = (long) (page - 1) * size;
        List<ImportBatchRowItem> items =
                importBatchQueryDao.findRows(batchId, rowStatus, offset, size);
        long total = importBatchQueryDao.countRows(batchId, rowStatus);
        List<ImportRowView> views = items.stream().map(item -> toRowView(item)).toList();
        return new ImportBatchDetailResponse(
                toView(batch), PageResponse.of(views, page, size, total));
    }

    // ---------- 内部 ----------

    private record RowOutcome(
            int rowNo,
            String externalKey,
            String rawJson,
            ImportBatchRowStatus status,
            List<ImportRowError> errors) {}

    /**
     * 标记指纹重复行：题库已存在或批内重复的 VALID 行改为 INVALID + DUPLICATE_SENTENCE。
     *
     * @param bankId 题库 id
     * @param outcomes 行结果
     * @param rowEntities 行实体（与 outcomes 同序）
     * @param now 当前时间
     */
    private void markDuplicates(
            Long bankId, List<RowOutcome> outcomes, List<ImportBatchRow> rowEntities, Instant now) {
        List<SentenceContent> validContents = new ArrayList<>();
        List<Integer> validIndexes = new ArrayList<>();
        for (int index = 0; index < outcomes.size(); index++) {
            if (outcomes.get(index).status() == ImportBatchRowStatus.VALID) {
                validContents.add(
                        toSentenceContent(
                                objectMapper.readTree(outcomes.get(index).rawJson()),
                                outcomes.get(index).externalKey()));
                validIndexes.add(index);
            }
        }
        List<String> fingerprints = contentImportService.fingerprintsOf(validContents);
        Set<String> existing =
                contentImportService.findExistingFingerprints(bankId, new HashSet<>(fingerprints));
        // §4.3：批内重复与命中题库的行「均标 INVALID」（不静默保留首行，由管理员决定去留）。
        Map<String, List<Integer>> grouped = new LinkedHashMap<>();
        for (int index = 0; index < fingerprints.size(); index++) {
            grouped.computeIfAbsent(fingerprints.get(index), key -> new ArrayList<>())
                    .add(validIndexes.get(index));
        }
        Map<Integer, ImportRowError> duplicates = new LinkedHashMap<>();
        grouped.forEach(
                (fingerprint, indexes) -> {
                    if (indexes.size() > 1 || existing.contains(fingerprint)) {
                        String message = indexes.size() > 1 ? "批次内重复题目（指纹重复）" : "题库已存在相同题目（指纹重复）";
                        indexes.forEach(
                                validIndex ->
                                        duplicates.put(
                                                validIndex,
                                                new ImportRowError(
                                                        "DUPLICATE_SENTENCE", null, message)));
                    }
                });
        for (Map.Entry<Integer, ImportRowError> entry : duplicates.entrySet()) {
            int index = entry.getKey();
            RowOutcome outcome = outcomes.get(index);
            outcomes.set(
                    index,
                    new RowOutcome(
                            outcome.rowNo(),
                            outcome.externalKey(),
                            outcome.rawJson(),
                            ImportBatchRowStatus.INVALID,
                            List.of(entry.getValue())));
            rowEntities.set(
                    index,
                    ImportBatchRow.previewed(
                            rowEntities.get(index).getBatchId(),
                            outcome.rowNo(),
                            outcome.externalKey(),
                            outcome.rawJson(),
                            ImportBatchRowStatus.INVALID,
                            writeJson(List.of(entry.getValue())),
                            now));
        }
    }

    /**
     * 取批次或抛 40405。
     *
     * @param batchId 批次 id
     * @return 批次实体
     */
    private ImportBatch requireBatch(String batchId) {
        return importBatchDao
                .findById(batchId)
                .orElseThrow(() -> new BusinessException(ApiCode.IMPORT_BATCH_NOT_FOUND));
    }

    /**
     * 校验 format 参数取值。
     *
     * @param format 声明格式
     */
    private static void requireFormat(String format) {
        if (!"JSON".equals(format) && !"CSV".equals(format)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "format 必须是 JSON 或 CSV");
        }
    }

    /**
     * 由扩展名推导格式（.csv → CSV，其余 json → JSON）。
     *
     * @param filename 文件名
     * @param contentType MIME
     * @return {@code JSON} / {@code CSV}
     */
    private static String derivedFormat(String filename, String contentType) {
        ImportFileDescriptor descriptor = ImportFilePolicy.validate(filename, 1, contentType);
        return descriptor.format()
                        == io.github.codejinxz.yilian.upload.validation.ImportFileFormat.CSV
                ? "CSV"
                : "JSON";
    }

    /**
     * 生成对象存储键：imports/{年}/{月}/{uuid}{扩展名}。
     *
     * @param filename 安全文件名
     * @return 对象键
     */
    private static String storageKey(String filename) {
        String extension = filename.substring(filename.lastIndexOf('.'));
        String[] parts = LocalDate.now(ZoneOffset.UTC).toString().split("-");
        return "imports/" + parts[0] + "/" + parts[1] + "/" + UUID.randomUUID() + extension;
    }

    /**
     * 读取上传文件字节。
     *
     * @param file 上传文件
     * @return 字节
     */
    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 校验分页参数。
     *
     * @param page 页码
     * @param size 每页条数
     */
    private static void requireValidPage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ApiCode.INVALID_PAGINATION);
        }
    }

    /**
     * 校验批次状态取值。
     *
     * @param status 状态字面量
     */
    private static void requireKnownStatus(String status) {
        if (status == null || status.isBlank()) {
            return;
        }
        try {
            io.github.codejinxz.yilian.upload.entity.ImportBatchStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "未知批次状态: " + status);
        }
    }

    /**
     * 日期下界（UTC 00:00）；null 返回 null。
     *
     * @param from ISO 日期
     * @return Instant
     */
    private static Instant utcStart(String from) {
        return from == null || from.isBlank()
                ? null
                : LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * 日期上界（含当天：次日 00:00 前闭区间）；null 返回 null。
     *
     * @param to ISO 日期
     * @return Instant
     */
    private static Instant utcEnd(String to) {
        return to == null || to.isBlank()
                ? null
                : LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * 把行 JSON 组装为落库内容。
     *
     * @param row 行节点
     * @param externalKey 源 id
     * @return 落库内容
     */
    private SentenceContent toSentenceContent(JsonNode row, String externalKey) {
        List<SentenceChunk> chunks = new ArrayList<>();
        JsonNode chunksNode = row.path("chunks");
        if (chunksNode.isArray()) {
            int index = 0;
            for (JsonNode chunk : chunksNode) {
                List<String> answers = new ArrayList<>();
                chunk.path("answers").forEach(answer -> answers.add(answer.asText()));
                chunks.add(
                        new SentenceChunk(
                                chunk.path("index").isInt() ? chunk.path("index").asInt() : index,
                                chunk.path("zh").asText(""),
                                answers,
                                chunk.path("tag").isTextual() ? chunk.path("tag").asText() : null,
                                chunk.path("note").isTextual()
                                        ? chunk.path("note").asText()
                                        : null));
                index++;
            }
        }
        List<Integer> blank = new ArrayList<>();
        row.path("blank").forEach(value -> blank.add(value.asInt()));
        List<String> tags = new ArrayList<>();
        row.path("tags").forEach(tag -> tags.add(tag.asText()));
        String level = row.path("level").isTextual() ? row.path("level").asText() : null;
        String source = row.path("source").isTextual() ? row.path("source").asText() : null;
        String license = row.path("license").isTextual() ? row.path("license").asText() : null;
        return new SentenceContent(
                externalKey,
                row.path("zh").asText(""),
                row.path("ref").asText(""),
                row.path("note").asText(""),
                chunks,
                blank,
                tags,
                level,
                source,
                license);
    }

    /**
     * 判断异常链上是否存在数据完整性冲突（唯一键/外键的 DB 兜底层）。
     *
     * <p>约束冲突可能由仓储直接抛出，也可能在事务提交阶段被包装为 {@code TransactionSystemException}，所以沿 cause
     * 链整条检查；防循环引用只在链上走一遍。
     *
     * @param exception 捕获到的异常
     * @return 链上任一层为 {@link DataIntegrityViolationException} 时为 true
     */
    private static boolean causedByDataIntegrityViolation(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /**
     * 组装失败摘要。
     *
     * @param exception 失败异常
     * @return 摘要对象
     */
    private ImportErrorSummary failureSummary(RuntimeException exception) {
        String message =
                exception instanceof BusinessException business
                        ? business.getMessage()
                        : "提交失败，批次已标记 FAILED";
        return new ImportErrorSummary(
                ImportErrorSummary.SCHEMA_VERSION, "COMMIT_FAILED", message, null);
    }

    /**
     * 批次实体转视图。
     *
     * @param batch 批次实体
     * @return 批次视图
     */
    private ImportBatchViewResponse toView(ImportBatch batch) {
        return new ImportBatchViewResponse(
                batch.getId(),
                batch.getBankId(),
                batch.getStatus().name(),
                batch.getFormat(),
                batch.getOriginalFilename(),
                batch.getTotalRows(),
                batch.getValidRows(),
                batch.getInvalidRows(),
                batch.getImportedRows(),
                batch.getCreatedAt() == null ? null : batch.getCreatedAt().toString(),
                batch.getPreviewedAt() == null ? null : batch.getPreviewedAt().toString(),
                batch.getCommittedAt() == null ? null : batch.getCommittedAt().toString(),
                parseErrorSummary(batch.getErrorSummary()));
    }

    /**
     * 列表项转视图。
     *
     * @param item 列表项
     * @return 批次视图
     */
    private ImportBatchViewResponse toView(ImportBatchListItem item) {
        return new ImportBatchViewResponse(
                item.id(),
                item.bankId(),
                item.status(),
                item.format(),
                item.originalFilename(),
                item.totalRows(),
                item.validRows(),
                item.invalidRows(),
                item.importedRows(),
                item.createdAt() == null ? null : item.createdAt().toString(),
                item.previewedAt() == null ? null : item.previewedAt().toString(),
                item.committedAt() == null ? null : item.committedAt().toString(),
                parseErrorSummary(item.errorSummary()));
    }

    /**
     * 行实体转视图。
     *
     * @param row 行实体
     * @return 行视图
     */
    private ImportRowView toRowView(ImportBatchRow row) {
        return new ImportRowView(
                row.getRowNo(),
                row.getExternalKey(),
                row.getStatus().name(),
                parseErrors(row.getErrorCodes()),
                row.getSentenceId(),
                row.getSentenceVersionId());
    }

    /**
     * 行结果转视图。
     *
     * @param outcome 行结果
     * @param sentenceId 关联题目 id
     * @return 行视图
     */
    private ImportRowView toRowView(RowOutcome outcome, String sentenceId) {
        return new ImportRowView(
                outcome.rowNo(),
                outcome.externalKey(),
                outcome.status().name(),
                outcome.errors(),
                sentenceId,
                null);
    }

    /**
     * 行列表项转视图。
     *
     * @param item 列表项
     * @return 行视图
     */
    private ImportRowView toRowView(ImportBatchRowItem item) {
        return new ImportRowView(
                item.rowNo(),
                item.externalKey(),
                item.status(),
                parseErrors(item.errorCodes()),
                item.sentenceId(),
                item.sentenceVersionId());
    }

    /**
     * 解析错误摘要 JSON。
     *
     * @param json 摘要 JSON 文本
     * @return 摘要对象；入参为空或解析失败返回 null
     */
    private ImportErrorSummary parseErrorSummary(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ImportErrorSummary.class);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 解析逐行错误 JSON 数组。
     *
     * @param json 错误 JSON 文本
     * @return 错误列表；入参为空返回空列表
     */
    private List<ImportRowError> parseErrors(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            List<ImportRowError> errors = new ArrayList<>();
            objectMapper
                    .readTree(json)
                    .forEach(
                            node ->
                                    errors.add(
                                            new ImportRowError(
                                                    node.path("code").asText(""),
                                                    node.path("field").isTextual()
                                                            ? node.path("field").asText()
                                                            : null,
                                                    node.path("message").asText(""))));
            return errors;
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /**
     * 序列化对象为 JSON 文本。
     *
     * @param value 待序列化对象
     * @return JSON 文本
     */
    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
