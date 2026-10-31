package io.github.codejinxz.yilian.content.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.content.dao.AdminSentenceQueryDao;
import io.github.codejinxz.yilian.content.dao.AdminSentenceRow;
import io.github.codejinxz.yilian.content.dao.BankDao;
import io.github.codejinxz.yilian.content.dao.SentenceDao;
import io.github.codejinxz.yilian.content.entity.BankVisibility;
import io.github.codejinxz.yilian.content.entity.ContentStatus;
import io.github.codejinxz.yilian.content.entity.Sentence;
import io.github.codejinxz.yilian.content.entity.SentenceBank;
import io.github.codejinxz.yilian.content.service.ContentAdminService;
import io.github.codejinxz.yilian.content.service.vo.BankView;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 内容管理用例实现：题库 CRUD 与生命周期、跨题库题目检索、批量状态/移动。
 *
 * <p>写路径全部复用既有 JPA 实体（sentence_banks/sentences），银行级修订号按「每题递增」推进 （切片设计 §4.5 P1-1：增量游标 {@code version
 * > sinceVersion} 永不重版本分组）；批量操作单事务、 悲观锁按题库 id 升序获取，避免两个并发批量的 ABBA 死锁。
 */
@Service
public class ContentAdminServiceImpl implements ContentAdminService {

    /** 单页题库/题目条数上限；与管理端其余分页口径一致。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 批量操作单次题目数上限；防御误选全库，前端多选最多一页（≤100），余量充足。 */
    private static final int MAX_BULK_IDS = 500;

    /** 名称与说明长度上限，与 sentence_banks 列定义及前端 zod 校验对齐。 */
    private static final int MAX_NAME_LENGTH = 128;

    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private final BankDao bankDao;

    private final SentenceDao sentenceDao;

    private final AdminSentenceQueryDao adminSentenceQueryDao;

    private final ObjectMapper objectMapper;

    /**
     * 注入题库/题目 Dao、管理端题目查询 Dao 与序列化器。
     *
     * @param bankDao 题库 Dao
     * @param sentenceDao 题目与快照 Dao
     * @param adminSentenceQueryDao 管理端题目列表 JOIN 查询 Dao（MyBatis 实现）
     * @param objectMapper Jackson 映射器（chunks/blank/tags 解析）
     */
    public ContentAdminServiceImpl(
            BankDao bankDao,
            SentenceDao sentenceDao,
            AdminSentenceQueryDao adminSentenceQueryDao,
            ObjectMapper objectMapper) {
        this.bankDao = bankDao;
        this.sentenceDao = sentenceDao;
        this.adminSentenceQueryDao = adminSentenceQueryDao;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<BankView> adminListBanks(
            String keyword, String visibility, String status, int page, int size) {
        requireValidPage(page, size);
        BankVisibility visibilityFilter = parseVisibility(visibility);
        ContentStatus statusFilter = parseContentStatus(status, "status 不受支持");
        String normalizedKeyword = normalizeKeyword(keyword);
        Page<SentenceBank> result =
                bankDao.searchAdmin(
                        normalizedKeyword,
                        visibilityFilter,
                        statusFilter,
                        PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));
        List<BankView> items = result.getContent().stream().map(this::toBankView).toList();
        return PageResponse.of(items, page, size, result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public BankView adminGetBank(long bankId) {
        SentenceBank bank = requireBank(bankId);
        return toBankView(bank);
    }

    @Override
    @Transactional
    public BankView adminCreateBank(
            Long actorId, String name, String description, String visibility) {
        BankVisibility visibilityValue = requireVisibility(visibility);
        String normalizedName = requireName(name);
        String normalizedDescription = normalizeDescription(description);
        SentenceBank bank =
                SentenceBank.create(
                        null,
                        normalizedName,
                        normalizedDescription,
                        visibilityValue,
                        actorId,
                        Instant.now());
        return toBankView(bankDao.save(bank));
    }

    @Override
    @Transactional
    public BankView adminUpdateBank(
            Long actorId,
            long bankId,
            String name,
            String description,
            String visibility,
            String status) {
        SentenceBank bank = requireBank(bankId);
        String normalizedName = name == null ? null : requireName(name);
        String normalizedDescription =
                description == null ? null : normalizeDescription(description);
        BankVisibility visibilityValue = parseVisibility(visibility);
        ContentStatus statusValue = parseContentStatus(status, "status 不受支持");
        bank.update(normalizedName, normalizedDescription, visibilityValue, actorId);
        if (statusValue != null) {
            bank.changeStatus(statusValue, actorId, Instant.now());
        }
        return toBankView(bankDao.save(bank));
    }

    @Override
    @Transactional
    public void adminDeleteBank(long actorId, long bankId, String reason) {
        if (StringUtils.isBlank(reason)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "删除题库必须填写原因");
        }
        if (reason.trim().length() > 255) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "原因最多 255 字符");
        }
        SentenceBank bank = requireBank(bankId);
        bank.recycle(actorId, Instant.now());
        bankDao.save(bank);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SentenceDetail> adminListBankSentences(
            long bankId, String keyword, String status, int page, int size) {
        requireValidPage(page, size);
        requireBank(bankId);
        ContentStatus statusFilter = parseContentStatus(status, "status 不受支持");
        return searchSentencePage(
                bankId, normalizeKeyword(keyword), null, statusFilter, false, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SentenceDetail> adminListSentences(
            String keyword, String bankId, String level, String status, int page, int size) {
        requireValidPage(page, size);
        Long bankIdFilter = parseBankId(bankId);
        ContentStatus statusFilter = parseContentStatus(status, "status 不受支持");
        // 缺省隐藏回收站（前端跨题库列表契约）；显式传 status（含 RECYCLED）时按精确状态过滤。
        boolean hideRecycled = statusFilter == null;
        return searchSentencePage(
                bankIdFilter,
                normalizeKeyword(keyword),
                normalizeKeyword(level),
                statusFilter,
                hideRecycled,
                page,
                size);
    }

    @Override
    @Transactional
    public int adminBulkChangeStatus(long actorId, List<String> ids, String status, String reason) {
        requireReason(reason, "批量操作必须填写原因");
        List<String> uniqueIds = normalizeBulkIds(ids);
        ContentStatus target = requireContentStatus(status, "status 不受支持");
        Map<String, Sentence> foundById =
                sentenceDao.findByIds(uniqueIds).stream()
                        .collect(Collectors.toMap(Sentence::getId, s -> s));
        // 已处于目标状态的题目跳过且不推进修订号，避免空操作制造无意义的增量流量。
        // 按题库 id 升序处理：同一事务内悲观锁按固定顺序获取，两个并发批量不会 ABBA 死锁。
        List<Sentence> matched =
                uniqueIds.stream()
                        .map(foundById::get)
                        .filter(Objects::nonNull)
                        .filter(sentence -> sentence.getStatus() != target)
                        .sorted(
                                Comparator.comparingLong(Sentence::getBankId)
                                        .thenComparing(Sentence::getId))
                        .toList();
        if (matched.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        Map<Long, SentenceBank> lockedBanks = new LinkedHashMap<>();
        for (Sentence sentence : matched) {
            SentenceBank bank =
                    lockedBanks.computeIfAbsent(
                            sentence.getBankId(),
                            id ->
                                    bankDao.lockById(id)
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "题目所属题库缺失: " + id)));
            long nextVersion = bank.getVersion() + 1;
            sentence.changeStatus(target, nextVersion, actorId, now);
            bank.advanceVersion(nextVersion, actorId, now);
            sentenceDao.save(sentence);
        }
        lockedBanks.values().forEach(bankDao::save);
        return matched.size();
    }

    @Override
    @Transactional
    public int adminBulkMove(long actorId, List<String> ids, String bankId, String reason) {
        requireReason(reason, "批量操作必须填写原因");
        List<String> uniqueIds = normalizeBulkIds(ids);
        long targetBankId = requireBankId(bankId);
        requireUsableBank(targetBankId);
        Map<String, Sentence> foundById =
                sentenceDao.findByIds(uniqueIds).stream()
                        .collect(Collectors.toMap(Sentence::getId, s -> s));
        // 不存在的 id 与已在目标题库的 id 跳过；实际移动数随响应暴露，不做静默吞没。
        List<Sentence> matched =
                uniqueIds.stream()
                        .map(foundById::get)
                        .filter(Objects::nonNull)
                        .filter(sentence -> sentence.getBankId() != targetBankId)
                        .sorted(
                                Comparator.comparingLong(Sentence::getBankId)
                                        .thenComparing(Sentence::getId))
                        .toList();
        if (matched.isEmpty()) {
            return 0;
        }
        // 与导入同源的指纹唯一约束（uk_sentence_bank_active_fingerprint）：预检给出可读的 400，
        // 而不是让并发导入后的唯一键冲突变成 500 整批回滚。
        List<String> fingerprints =
                matched.stream()
                        .filter(sentence -> sentence.getDeletedAt() == null)
                        .map(Sentence::getFingerprint)
                        .toList();
        if (!fingerprints.isEmpty()
                && !sentenceDao.findActiveFingerprints(targetBankId, fingerprints).isEmpty()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "目标题库已存在相同题目");
        }
        Instant now = Instant.now();
        // 源题库 id 必须在移动前捕获：moveToBank 会原地改写 bankId。
        TreeSet<Long> sourceBankIds = new TreeSet<>();
        matched.forEach(sentence -> sourceBankIds.add(sentence.getBankId()));
        TreeSet<Long> lockOrder = new TreeSet<>(sourceBankIds);
        lockOrder.add(targetBankId);
        Map<Long, SentenceBank> lockedBanks = new LinkedHashMap<>();
        for (Long id : lockOrder) {
            lockedBanks.put(
                    id,
                    bankDao.lockById(id)
                            .orElseThrow(() -> new IllegalStateException("题目所属题库缺失: " + id)));
        }
        for (Sentence sentence : matched) {
            SentenceBank targetBank = lockedBanks.get(targetBankId);
            long nextVersion = targetBank.getVersion() + 1;
            targetBank.advanceVersion(nextVersion, actorId, now);
            sentence.moveToBank(targetBankId, nextVersion, actorId);
            sentenceDao.save(sentence);
        }
        // 源题库各推进一次修订号：客户端按 bank.version 变化感知内容迁出。
        // 已知限制：移动不在源题库产生 tombstone，增量客户端按版本落差全量重拉（与 mock 行为一致）。
        for (Long sourceId : sourceBankIds) {
            SentenceBank source = lockedBanks.get(sourceId);
            source.advanceVersion(source.getVersion() + 1, actorId, now);
        }
        lockedBanks.values().forEach(bankDao::save);
        return matched.size();
    }

    /**
     * 组装题目分页：查询 Dao 直出行，再解析 JSON 列构造视图。
     *
     * @param bankId 题库过滤，可为 null
     * @param keyword 关键字，可为 null
     * @param level 难度过滤，可为 null
     * @param status 状态过滤，可为 null
     * @param hideRecycled 是否隐藏回收站
     * @param page 页码
     * @param size 每页大小
     * @return 分页视图
     */
    private PageResponse<SentenceDetail> searchSentencePage(
            Long bankId,
            String keyword,
            String level,
            ContentStatus status,
            boolean hideRecycled,
            int page,
            int size) {
        String statusRaw = status == null ? null : status.name();
        List<AdminSentenceRow> rows =
                adminSentenceQueryDao.search(
                        bankId, keyword, level, statusRaw, hideRecycled, (page - 1) * size, size);
        long total = adminSentenceQueryDao.count(bankId, keyword, level, statusRaw, hideRecycled);
        List<SentenceDetail> items = rows.stream().map(this::toSentenceDetail).toList();
        return PageResponse.of(items, page, size, total);
    }

    /**
     * 由查询行构造题目视图：管理端不做 tombstone 裁剪，回收站行也带完整快照供恢复预览。
     *
     * @param row 查询行
     * @return 题目视图
     */
    private SentenceDetail toSentenceDetail(AdminSentenceRow row) {
        return new SentenceDetail(
                row.sentenceId(),
                Long.toString(row.bankId()),
                row.version(),
                row.versionId() == null ? null : Long.toString(row.versionId()),
                row.versionNo(),
                row.status(),
                row.deletedAt() != null,
                row.zh(),
                row.ref(),
                row.note(),
                parseArray(row.chunks(), SentenceChunk[].class),
                parseArray(row.blank(), Integer[].class),
                parseArray(row.tags(), String[].class),
                row.level());
    }

    /**
     * 解析快照中的 JSON 数组列为不可变列表。
     *
     * <p>快照列由导入校验保证非空且结构合法；解析失败按未知异常上抛，由全局出口转 500 并落 error_log。
     *
     * @param json JSON 数组文本，可为 null
     * @param elementType 数组元素类型
     * @param <T> 元素类型
     * @return 不可变列表；入参为 null 时返回 null
     */
    private <T> List<T> parseArray(String json, Class<T[]> elementType) {
        if (json == null) {
            return null;
        }
        return List.of(objectMapper.readValue(json, elementType));
    }

    /**
     * 构造题库视图，题数按「已发布且未删」统计。
     *
     * @param bank 题库实体
     * @return 视图
     */
    private BankView toBankView(SentenceBank bank) {
        long sentenceCount = bankDao.countPublishedSentences(bank.getId());
        return BankView.of(
                bank.getId(),
                bank.getOwnerId(),
                bank.getName(),
                bank.getDescription(),
                bank.getVisibility().name(),
                bank.getStatus().name(),
                bank.getVersion(),
                sentenceCount);
    }

    /**
     * 取题库，不存在回 40401。
     *
     * @param bankId 题库 id
     * @return 题库实体
     */
    private SentenceBank requireBank(long bankId) {
        return bankDao.findById(bankId)
                .orElseThrow(() -> new BusinessException(ApiCode.BANK_NOT_FOUND));
    }

    /**
     * 取可作为批量移动目标的题库：不存在或已进回收站一律 40401，避免把题目移进看不见的库。
     *
     * @param bankId 题库 id
     * @return 题库实体
     */
    private SentenceBank requireUsableBank(long bankId) {
        SentenceBank bank = requireBank(bankId);
        if (bank.getDeletedAt() != null || bank.getStatus() == ContentStatus.RECYCLED) {
            throw new BusinessException(ApiCode.BANK_NOT_FOUND);
        }
        return bank;
    }

    /**
     * 校验分页参数，越界统一抛 40004。
     *
     * @param page 页码，从 1 开始
     * @param size 每页大小，1–100
     */
    private static void requireValidPage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ApiCode.INVALID_PAGINATION);
        }
    }

    /**
     * 校验审计原因，空白或超长按参数校验失败收敛。
     *
     * @param reason 原因
     * @param message 空白时的提示文案
     */
    private static void requireReason(String reason, String message) {
        if (StringUtils.isBlank(reason)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, message);
        }
        if (reason.trim().length() > 255) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "原因最多 255 字符");
        }
    }

    /**
     * 归一化批量 id 列表：去空白、去重（保序）；空集或超上限按 40000 拒绝。
     *
     * <p>上限 500：前端多选来自单页（≤100），余量充足；防御一次事务锁全库。
     *
     * @param ids 原始 id 列表
     * @return 去重后的 id 列表
     */
    private static List<String> normalizeBulkIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "请先选择题目");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null) {
                continue;
            }
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        if (unique.isEmpty()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "请先选择题目");
        }
        if (unique.size() > MAX_BULK_IDS) {
            throw new BusinessException(
                    ApiCode.VALIDATION_FAILED, "批量操作一次最多 " + MAX_BULK_IDS + " 条");
        }
        return List.copyOf(unique);
    }

    /**
     * 解析必填题库 id 字符串，空白或非数字按参数校验失败收敛。
     *
     * @param bankId 题库 id 字符串
     * @return 题库 id
     */
    private static long requireBankId(String bankId) {
        Long parsed = parseBankId(bankId);
        if (parsed == null) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "bankId 必填");
        }
        return parsed;
    }

    /**
     * 解析可选题库 id 字符串：空白视为未过滤，非数字按 40000 收敛，避免 NumberFormatException 变 500。
     *
     * @param bankId 题库 id 字符串，可为 null
     * @return 题库 id；未提供时为 null
     */
    private static Long parseBankId(String bankId) {
        if (StringUtils.isBlank(bankId)) {
            return null;
        }
        try {
            return Long.valueOf(bankId.trim());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "bankId 不合法");
        }
    }

    /**
     * 解析可选可见性参数：空白表示不过滤，非法值按参数校验失败收敛。
     *
     * @param visibility PUBLIC/PRIVATE，可为 null
     * @return 可见性枚举；不过滤时为 null
     */
    private static BankVisibility parseVisibility(String visibility) {
        if (StringUtils.isBlank(visibility)) {
            return null;
        }
        try {
            return BankVisibility.valueOf(visibility.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "visibility 不受支持");
        }
    }

    /**
     * 解析必填可见性参数。
     *
     * @param visibility PUBLIC/PRIVATE
     * @return 可见性枚举
     */
    private static BankVisibility requireVisibility(String visibility) {
        BankVisibility parsed = parseVisibility(visibility);
        if (parsed == null) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "visibility 必填");
        }
        return parsed;
    }

    /**
     * 解析可选生命周期参数：空白表示不过滤，非法值按参数校验失败收敛。
     *
     * @param status DRAFT/PUBLISHED/DISABLED/RECYCLED/ARCHIVED，可为 null
     * @param message 非法时的提示文案
     * @return 状态枚举；不过滤时为 null
     */
    private static ContentStatus parseContentStatus(String status, String message) {
        if (StringUtils.isBlank(status)) {
            return null;
        }
        try {
            return ContentStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, message);
        }
    }

    /**
     * 解析必填生命周期参数。
     *
     * @param status 目标状态
     * @param message 非法时的提示文案
     * @return 状态枚举
     */
    private static ContentStatus requireContentStatus(String status, String message) {
        ContentStatus parsed = parseContentStatus(status, message);
        if (parsed == null) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "status 必填");
        }
        return parsed;
    }

    /**
     * 校验并归一化题库名称：必填、去首尾空白后 1–128 字符。
     *
     * @param name 原始名称
     * @return 归一化名称
     */
    private static String requireName(String name) {
        if (StringUtils.isBlank(name)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "请填写题库名称");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "题库名称最多 128 个字符");
        }
        return trimmed;
    }

    /**
     * 归一化题库说明：空白视为无说明，超长按 40000 收敛。
     *
     * @param description 原始说明，可为 null
     * @return 归一化说明；无内容时为 null
     */
    private static String normalizeDescription(String description) {
        if (StringUtils.isBlank(description)) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "说明最多 500 个字符");
        }
        return trimmed;
    }

    /**
     * 归一化关键字：空白串视为未过滤。
     *
     * @param keyword 原始关键字
     * @return 去除首尾空白后的关键字；无有效内容时为 null
     */
    private static String normalizeKeyword(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return null;
        }
        return keyword.trim();
    }
}
