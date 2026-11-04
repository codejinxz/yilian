package io.github.codejinxz.yilian.admin.service.impl;

import io.github.codejinxz.yilian.admin.service.AdminGenerationService;
import io.github.codejinxz.yilian.admin.service.dto.AdminGenerationImportItem;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.upload.dto.ImportBatchViewResponse;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 出题导入实现：行校验（与 upload 导入校验器同口径）→ content 导入接缝入库 → 批次视图。
 *
 * <p>「批次 id」是随机 UUID，写入 {@code sentence_versions.import_batch_id} 供溯源， 同时为 {@code external_key}
 * 提供按批次隔离的命名空间前缀 （同库反复 AI 导入不再互相撞唯一键）； upload 侧不建批次记录（出题导入走 content 接缝的最小入库路径）。指纹去重语义
 * 与文件导入完全一致——批内或库内重复整批 40904 回滚，与「不自动合并近似重复」的约束相容。
 */
@Service
public class AdminGenerationServiceImpl implements AdminGenerationService {

    /** 单批行数上限；与 bulk-status/bulk-move 的 500 上限对齐，防单事务无界写。 */
    static final int MAX_ITEMS = 500;

    private final ContentImportService contentImportService;

    /**
     * @param contentImportService content 导入接缝（content 模块接口）
     */
    public AdminGenerationServiceImpl(ContentImportService contentImportService) {
        this.contentImportService = contentImportService;
    }

    @Override
    public ImportBatchViewResponse importGenerated(
            Long actorId, String bankId, List<AdminGenerationImportItem> items) {
        long bank = parseBankId(bankId);
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "没有可导入的句子");
        }
        if (items.size() > MAX_ITEMS) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "单批最多 " + MAX_ITEMS + " 行");
        }
        // 批次 id 先生成再组行：external_key 需要按批次命名空间。
        String batchId = UUID.randomUUID().toString();
        List<SentenceContent> contents = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            validateRow(index + 1, items.get(index));
            contents.add(toSentenceContent(items.get(index), index + 1, batchId));
        }
        Instant now = Instant.now();
        contentImportService.importSentences(bank, batchId, contents, actorId, now);
        return new ImportBatchViewResponse(
                batchId,
                bank,
                "COMMITTED",
                "JSON",
                "AI 生成.json",
                contents.size(),
                contents.size(),
                0,
                contents.size(),
                now.toString(),
                now.toString(),
                now.toString(),
                null);
    }

    /**
     * 解析题库 id；非数字按参数错误收敛（40401 保留给「存在但不可导入」）。
     *
     * @param bankId 入参字符串
     * @return 题库 id
     * @throws BusinessException 非数字（40000）
     */
    private static long parseBankId(String bankId) {
        if (bankId == null || bankId.isBlank()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "bankId 不能为空");
        }
        try {
            return Long.parseLong(bankId.trim());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "bankId 需为数字");
        }
    }

    /**
     * 行级业务校验；口径与 upload 导入校验器一致（DATA-MODEL §6.7）： zh 4–60 字、ref 8–300、note 20–300、chunks
     * 非空且每块至少一个非空答案、 blank 下标必须命中语块、chunks[].answers[0] 按序拼接 normalize 后与 ref 一致、tags 1–5。
     *
     * @param rowNo 行号（1 基），错误提示用
     * @param item 待校验行
     * @throws BusinessException 任一规则不满足（40000，fail-fast 整批拒绝）
     */
    private static void validateRow(int rowNo, AdminGenerationImportItem item) {
        String prefix = "第 " + rowNo + " 行校验未通过：";
        int zhLength = codePoints(item == null || item.zh() == null ? "" : item.zh());
        if (zhLength < 4 || zhLength > 60) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, prefix + "中文长度必须为 4–60 字");
        }
        int refLength = codePoints(item.ref() == null ? "" : item.ref());
        if (refLength < 8 || refLength > 300) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, prefix + "参考译文长度必须为 8–300");
        }
        int noteLength = codePoints(item.note() == null ? "" : item.note());
        if (noteLength < 20 || noteLength > 300) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, prefix + "解析必须为 20–300 字");
        }
        List<AdminGenerationImportItem.Chunk> chunks =
                item.chunks() == null ? List.of() : item.chunks();
        if (chunks.isEmpty()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, prefix + "chunks 不能为空");
        }
        Set<Integer> chunkIndexes = new HashSet<>();
        List<String> canonicalParts = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            AdminGenerationImportItem.Chunk chunk = chunks.get(index);
            if (chunk == null || chunk.zh() == null || chunk.zh().isBlank()) {
                throw new BusinessException(
                        ApiCode.VALIDATION_FAILED, prefix + "chunks[" + index + "].zh 不能为空");
            }
            List<String> answers = chunk.answers() == null ? List.of() : chunk.answers();
            List<String> nonBlank =
                    answers.stream().filter(answer -> answer != null && !answer.isBlank()).toList();
            if (nonBlank.isEmpty()) {
                throw new BusinessException(
                        ApiCode.VALIDATION_FAILED,
                        prefix + "chunks[" + index + "].answers 至少一个非空答案");
            }
            chunkIndexes.add(chunk.index() == null ? index : chunk.index());
            canonicalParts.add(nonBlank.getFirst());
        }
        List<Integer> blank = item.blank() == null ? List.of() : item.blank();
        if (blank.isEmpty()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, prefix + "blank 至少一个下标");
        }
        for (Integer value : blank) {
            if (value == null || !chunkIndexes.contains(value)) {
                throw new BusinessException(
                        ApiCode.VALIDATION_FAILED, prefix + "blank 下标 " + value + " 不存在于 chunks");
            }
        }
        String joined = canonicalParts.stream().reduce((a, b) -> a + " " + b).orElse("");
        if (!normalize(joined).equals(normalize(item.ref()))) {
            throw new BusinessException(
                    ApiCode.VALIDATION_FAILED, prefix + "chunks[].answers[0] 按序拼接与 ref 不一致");
        }
        int tagCount = item.tags() == null ? 0 : item.tags().size();
        if (tagCount < 1 || tagCount > 5) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, prefix + "考点标签必须为 1–5 个");
        }
    }

    /**
     * 请求行转落库内容；index 缺省按位置补齐。
     *
     * <p>externalKey 用 {@code gen_<批次前缀>_<行号>}：若固定为 {@code gen_%04d} 行号，行号每请求都从 1 重排，同题库第二次 AI
     * 导入必复用同一批 key，撞 {@code uk_sentence_bank_external} 唯一键后整批 500 且不可重试。批次前缀让命名空间按请求
     * 隔离：内容不同则各次导入都成功；内容相同（指纹相同）由 {@code importSentences} 的指纹预检 统一收敛为 40904。批次 id 去连字符取前 8
     * 位十六进制，加前缀总长 17，远小于列宽 64。
     *
     * @param item 请求行
     * @param rowNo 行号（1 基）
     * @param batchId 本批次 UUID（含连字符）
     * @return 落库内容
     */
    private static SentenceContent toSentenceContent(
            AdminGenerationImportItem item, int rowNo, String batchId) {
        List<SentenceChunk> chunks = new ArrayList<>(item.chunks().size());
        for (int index = 0; index < item.chunks().size(); index++) {
            AdminGenerationImportItem.Chunk chunk = item.chunks().get(index);
            chunks.add(
                    new SentenceChunk(
                            chunk.index() == null ? index : chunk.index(),
                            chunk.zh().trim(),
                            chunk.answers().stream()
                                    .filter(answer -> answer != null && !answer.isBlank())
                                    .toList(),
                            chunk.tag(),
                            chunk.note()));
        }
        return new SentenceContent(
                "gen_"
                        + batchId.replace("-", "").substring(0, 8)
                        + "_"
                        + String.format("%04d", rowNo),
                item.zh().trim(),
                item.ref().trim(),
                item.note().trim(),
                chunks,
                List.copyOf(item.blank()),
                List.copyOf(item.tags()),
                item.level(),
                null,
                null);
    }

    /**
     * 文本 code point 计数（与 upload 校验器一致，避免代理对误计）。
     *
     * @param value 原文
     * @return code point 数
     */
    private static int codePoints(String value) {
        return (int) value.codePoints().count();
    }

    /**
     * 规范化文本用于拼接比对：小写、仅保留字母数字（非字母数字串折叠为单空格）。
     *
     * <p>与前端 {@code gen.ts} 的 normLoose 同口径——出题 Prompt 明确「去掉标点后必须等于 ref」， 因此比对容忍标点差异；文件导入（upload
     * 校验器）的更严口径不适用本端点。
     *
     * @param value 原文
     * @return 规范化结果
     */
    private static String normalize(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }
}
