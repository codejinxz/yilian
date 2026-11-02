package io.github.codejinxz.yilian.content.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.common.util.TextNormalizer;
import io.github.codejinxz.yilian.content.dao.AdminSentenceQueryDao;
import io.github.codejinxz.yilian.content.dao.AdminSentenceRow;
import io.github.codejinxz.yilian.content.dao.BankDao;
import io.github.codejinxz.yilian.content.dao.SentenceDao;
import io.github.codejinxz.yilian.content.entity.BankVisibility;
import io.github.codejinxz.yilian.content.entity.ContentStatus;
import io.github.codejinxz.yilian.content.entity.Sentence;
import io.github.codejinxz.yilian.content.entity.SentenceBank;
import io.github.codejinxz.yilian.content.entity.SentenceVersion;
import io.github.codejinxz.yilian.content.service.ContentAdminService;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
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
 * 内容管理用例实现：题库 CRUD 与生命周期、跨题库题目检索、批量状态/移动、单题状态流转与行内编辑。
 *
 * <p>写路径全部复用既有 JPA 实体（sentence_banks/sentences/sentence_versions），银行级修订号按「每题递增」推进 （切片设计 §4.5
 * P1-1：增量游标 {@code version > sinceVersion} 永不重版本分组）；批量操作单事务、 悲观锁按题库 id 升序获取，避免两个并发批量的 ABBA 死锁。
 * 行内编辑的 zh/level/tags/note 落在不可变内容快照上——新增版本行并回填指纹，而不是原地改历史行。
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

    /** 编辑字段长度上限，与 sentence_versions 列定义及前端 mock 校验对齐。 */
    private static final int MAX_ZH_LENGTH = 255;

    private static final int MIN_ZH_LENGTH = 4;

    private static final int MAX_LEVEL_LENGTH = 32;

    private static final int MAX_NOTE_LENGTH = 1000;

    private final BankDao bankDao;

    private final SentenceDao sentenceDao;

    private final AdminSentenceQueryDao adminSentenceQueryDao;

    private final ContentImportService contentImportService;

    private final BankMetadataCache bankMetadataCache;

    private final ObjectMapper objectMapper;

    /**
     * 注入题库/题目 Dao、管理端题目查询 Dao、导入服务（复用指纹算法）、缓存失效装配与序列化器。
     *
     * @param bankDao 题库 Dao
     * @param sentenceDao 题目与快照 Dao
     * @param adminSentenceQueryDao 管理端题目列表 JOIN 查询 Dao（MyBatis 实现）
     * @param contentImportService 导入落库服务（fingerprintsOf 与导入共用 canonical 指纹算法）
     * @param bankMetadataCache 匿名公开读缓存（写路径在事务提交后失效）
     * @param objectMapper Jackson 映射器（chunks/blank/tags 解析）
     */
    public ContentAdminServiceImpl(
            BankDao bankDao,
            SentenceDao sentenceDao,
            AdminSentenceQueryDao adminSentenceQueryDao,
            ContentImportService contentImportService,
            BankMetadataCache bankMetadataCache,
            ObjectMapper objectMapper) {
        this.bankDao = bankDao;
        this.sentenceDao = sentenceDao;
        this.adminSentenceQueryDao = adminSentenceQueryDao;
        this.contentImportService = contentImportService;
        this.bankMetadataCache = bankMetadataCache;
        this.objectMapper = objectMapper;
    }

    /** 管理端题库列表：低频且要求实时，不走缓存（013 AC-2，理由见 {@link BankMetadataCache} 类注释）。 */
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
        SentenceBank saved = bankDao.save(bank);
        // 新库出现在匿名列表里且总数变化：提交后清列表缓存（详情键此时本就不存在，顺带失效无妨）。
        bankMetadataCache.evictWrittenBanks(List.of(saved.getId()));
        return toBankView(saved);
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
        // 契约：null=不改、空串=清空（§8.8）。normalizeDescription 会把空白归一成 null（与「不改」
        // 同形），清空要在归一前截获，以空串穿透到实体层显式置 null。
        String normalizedDescription =
                description == null
                        ? null
                        : (description.isBlank() ? "" : normalizeDescription(description));
        BankVisibility visibilityValue = parseVisibility(visibility);
        ContentStatus statusValue = parseContentStatus(status, "status 不受支持");
        bank.update(normalizedName, normalizedDescription, visibilityValue, actorId);
        if (statusValue != null) {
            bank.changeStatus(statusValue, actorId, Instant.now());
        }
        SentenceBank saved = bankDao.save(bank);
        // 元数据（名称/说明/可见性/状态）变更直接影响匿名视图：提交后失效该库详情与列表缓存。
        bankMetadataCache.evictWrittenBanks(List.of(bankId));
        return toBankView(saved);
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
        // 回收后对匿名立即不可见：提交后失效详情与列表缓存。
        bankMetadataCache.evictWrittenBanks(List.of(bankId));
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
        // 批量状态变更改变「已发布且未删」计数：提交后失效受影响题库的详情与列表缓存。
        bankMetadataCache.evictWrittenBanks(affectedBankIds(matched));
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
        // 两库句数此消彼长：提交后失效全部源库与目标库的缓存。
        TreeSet<Long> affected = new TreeSet<>(sourceBankIds);
        affected.add(targetBankId);
        bankMetadataCache.evictWrittenBanks(affected);
        return matched.size();
    }

    @Override
    @Transactional
    public SentenceDetail adminChangeSentenceStatus(
            long actorId, String sentenceId, String status, String reason) {
        requireReason(reason, "变更状态必须填写原因");
        ContentStatus target = requireContentStatus(status, "status 不受支持");
        Sentence sentence = requireSentence(sentenceId);
        // 幂等：已处于目标状态不推进修订号（与批量同语义），空操作不制造增量流量。
        if (sentence.getStatus() == target) {
            return detailOf(sentence);
        }
        Instant now = Instant.now();
        SentenceBank bank = lockedBankOf(sentence.getBankId());
        long nextVersion = bank.getVersion() + 1;
        sentence.changeStatus(target, nextVersion, actorId, now);
        bank.advanceVersion(nextVersion, actorId, now);
        sentenceDao.save(sentence);
        bankDao.save(bank);
        // 状态流转改变「已发布且未删」计数：提交后失效所属题库的缓存。
        bankMetadataCache.evictWrittenBanks(List.of(sentence.getBankId()));
        return detailOf(sentence);
    }

    @Override
    @Transactional
    public SentenceDetail adminUpdateSentenceDetail(
            long actorId,
            String sentenceId,
            String zh,
            String level,
            List<String> tags,
            String note,
            String bankId,
            String status,
            String reason) {
        // 编辑字段分两类（语义见 API §8.8）：
        // - 版本字段 zh/level/tags/note：sentence_versions 不可变（DB 触发器禁止 UPDATE），改内容 = 新增
        //   version_no=最新+1 的版本行并回填指纹，所属题库按「每题递增」推进修订号；
        // - 元字段 bankId/status：只改 sentences 行，移动/状态各自推进修订号。
        String zhEdit = normalizeEditZh(zh);
        // level 用「原始参数是否为 null」判断是否修改：显式传空白串表示清空难度。
        boolean levelProvided = level != null;
        String levelEdit = level == null ? null : normalizeLevel(level);
        List<String> tagsEdit = tags == null ? null : normalizeTags(tags);
        String noteEdit = note == null ? null : normalizeNote(note);
        ContentStatus statusTarget = parseContentStatus(status, "status 不受支持");
        Long targetBankId = parseBankId(bankId);
        Sentence sentence = requireSentence(sentenceId);
        // 源题库 id 必须在移动前捕获：moveToBank 会原地改写 bankId。
        long originalBankId = sentence.getBankId();
        Instant now = Instant.now();

        boolean contentChange =
                zhEdit != null || levelProvided || tagsEdit != null || noteEdit != null;
        if (contentChange) {
            applyContentEdit(
                    actorId, sentence, zhEdit, levelProvided, levelEdit, tagsEdit, noteEdit, now);
        }
        // 与当前状态相同则不推进修订号（前端编辑弹窗恒带当前 status，属常见空操作）。
        if (statusTarget != null && sentence.getStatus() != statusTarget) {
            SentenceBank bank = lockedBankOf(sentence.getBankId());
            long nextVersion = bank.getVersion() + 1;
            sentence.changeStatus(statusTarget, nextVersion, actorId, now);
            bank.advanceVersion(nextVersion, actorId, now);
            sentenceDao.save(sentence);
            bankDao.save(bank);
        }
        boolean moved = false;
        if (targetBankId != null && targetBankId != sentence.getBankId()) {
            moveSentence(actorId, sentence, targetBankId, now);
            moved = true;
        }
        // 内容/状态编辑影响原库视图，移动还改变目标库句数：提交后一并失效。
        if (moved) {
            bankMetadataCache.evictWrittenBanks(List.of(originalBankId, targetBankId));
        } else {
            bankMetadataCache.evictWrittenBanks(List.of(originalBankId));
        }
        return detailOf(sentence);
    }

    /**
     * 落实版本字段编辑：新增不可变版本行、回填指纹与 content_hash、推进题库修订号并指向新快照。
     *
     * @param actorId 操作管理员 id
     * @param sentence 目标题目
     * @param zhEdit 新中文，null 表示不改
     * @param levelProvided 是否显式提供了 level（区分「不改」与「清空」）
     * @param levelEdit 新难度（已归一化，可为 null 表示清空）
     * @param tagsEdit 新标签，null 表示不改
     * @param noteEdit 新解析，null 表示不改
     * @param now 统一时钟
     */
    private void applyContentEdit(
            long actorId,
            Sentence sentence,
            String zhEdit,
            boolean levelProvided,
            String levelEdit,
            List<String> tagsEdit,
            String noteEdit,
            Instant now) {
        SentenceBank bank = lockedBankOf(sentence.getBankId());
        SentenceVersion current = currentVersionOf(sentence);
        int nextVersionNo =
                sentenceDao
                        .findLatestVersion(sentence.getId())
                        .map(latest -> latest.getVersionNo() + 1)
                        .orElse(1);
        String newZh = zhEdit != null ? zhEdit : current.getZh();
        String newNote = noteEdit != null ? noteEdit : current.getNote();
        String newLevel = levelProvided ? levelEdit : current.getLevel();
        String newTagsJson =
                tagsEdit != null ? objectMapper.writeValueAsString(tagsEdit) : current.getTags();
        // content_hash 与导入同式：原始 zh|ref|note|chunks|blank|tags 的 SHA-256（ref/chunks/blank 本端点不可改）。
        String contentHash =
                TextNormalizer.sha256(
                        newZh
                                + "|"
                                + current.getRef()
                                + "|"
                                + newNote
                                + "|"
                                + current.getChunks()
                                + "|"
                                + current.getBlank()
                                + "|"
                                + newTagsJson);
        // 指纹与导入共用 fingerprintsOf 的 canonical 算法：改题后再次导入相同内容仍会被去重拦截。
        String fingerprint =
                contentImportService
                        .fingerprintsOf(
                                List.of(
                                        new SentenceContent(
                                                null,
                                                newZh,
                                                current.getRef(),
                                                newNote,
                                                parseArray(
                                                        current.getChunks(), SentenceChunk[].class),
                                                parseArray(current.getBlank(), Integer[].class),
                                                tagsEdit != null
                                                        ? tagsEdit
                                                        : parseArray(
                                                                current.getTags(), String[].class),
                                                newLevel,
                                                null,
                                                null)))
                        .get(0);
        SentenceVersion nextVersion =
                SentenceVersion.create(
                        sentence.getId(),
                        sentence.getBankId(),
                        nextVersionNo,
                        newZh,
                        current.getRef(),
                        newNote,
                        current.getChunks(),
                        current.getBlank(),
                        newTagsJson,
                        newLevel,
                        current.getSource(),
                        current.getLicense(),
                        contentHash,
                        current.getImportBatchId(),
                        actorId,
                        now);
        SentenceVersion saved = sentenceDao.saveVersion(nextVersion);
        long bankVersion = bank.getVersion() + 1;
        bank.advanceVersion(bankVersion, actorId, now);
        sentence.pointToVersion(saved.getId(), nextVersionNo, bankVersion, fingerprint, actorId);
        sentenceDao.save(sentence);
        bankDao.save(bank);
    }

    /**
     * 单题移动：目标库须可用且无相同内容，目标库每题递增修订号、源库推进一次（语义与批量移动一致）。
     *
     * @param actorId 操作管理员 id
     * @param sentence 待移动题目
     * @param targetBankId 目标题库 id
     * @param now 统一时钟
     */
    private void moveSentence(long actorId, Sentence sentence, long targetBankId, Instant now) {
        requireUsableBank(targetBankId);
        // 源题库 id 必须在移动前捕获：moveToBank 会原地改写 bankId。
        long sourceBankId = sentence.getBankId();
        if (!sentenceDao
                .findActiveFingerprints(targetBankId, List.of(sentence.getFingerprint()))
                .isEmpty()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "目标题库已存在相同题目");
        }
        TreeSet<Long> lockOrder = new TreeSet<>(List.of(sourceBankId, targetBankId));
        Map<Long, SentenceBank> lockedBanks = new LinkedHashMap<>();
        for (Long id : lockOrder) {
            lockedBanks.put(id, lockedBankOf(id));
        }
        SentenceBank targetBank = lockedBanks.get(targetBankId);
        long nextVersion = targetBank.getVersion() + 1;
        targetBank.advanceVersion(nextVersion, actorId, now);
        sentence.moveToBank(targetBankId, nextVersion, actorId);
        sentenceDao.save(sentence);
        // 源题库推进一次修订号；移动不在源库产生 tombstone（已知限制，与批量移动一致）。
        SentenceBank source = lockedBanks.get(sourceBankId);
        source.advanceVersion(source.getVersion() + 1, actorId, now);
        lockedBanks.values().forEach(bankDao::save);
    }

    /**
     * 取题目，不存在回 40402（题目不存在，与 mock/错误码语义一致）。
     *
     * @param sentenceId 题目 ULID
     * @return 题目实体
     */
    private Sentence requireSentence(String sentenceId) {
        return sentenceDao
                .findById(sentenceId)
                .orElseThrow(() -> new BusinessException(ApiCode.SENTENCE_NOT_FOUND));
    }

    /**
     * 收集批量操作实际涉及的题库 id（去重保序），供缓存失效使用。
     *
     * @param sentences 实际发生变更的题目
     * @return 涉及的题库 id
     */
    private static List<Long> affectedBankIds(List<Sentence> sentences) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Sentence sentence : sentences) {
            ids.add(sentence.getBankId());
        }
        return List.copyOf(ids);
    }

    /**
     * 取当前内容快照；缺失视为数据损坏，按未知异常上抛转 500 并落 error_log（与只读查询同口径）。
     *
     * @param sentence 题目实体
     * @return 当前快照
     */
    private SentenceVersion currentVersionOf(Sentence sentence) {
        Long currentVersionId = sentence.getCurrentVersionId();
        if (currentVersionId == null) {
            throw new IllegalStateException("题目缺少内容快照: " + sentence.getId());
        }
        return sentenceDao.findVersionsByIds(List.of(currentVersionId)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("题目快照缺失: " + sentence.getId()));
    }

    /**
     * 构造题目当前视图：按 sentences 行 + 当前快照组装（管理端不做 tombstone 裁剪）。
     *
     * @param sentence 题目实体
     * @return 题目视图
     */
    private SentenceDetail detailOf(Sentence sentence) {
        return toDetail(sentence, currentVersionOf(sentence));
    }

    /**
     * 由题目身份与内容快照组装视图。
     *
     * @param sentence 题目实体
     * @param snapshot 内容快照
     * @return 题目视图
     */
    private SentenceDetail toDetail(Sentence sentence, SentenceVersion snapshot) {
        return new SentenceDetail(
                sentence.getId(),
                Long.toString(sentence.getBankId()),
                sentence.getVersion(),
                snapshot.getId() == null ? null : Long.toString(snapshot.getId()),
                snapshot.getVersionNo(),
                sentence.getStatus().name(),
                sentence.getDeletedAt() != null,
                snapshot.getZh(),
                snapshot.getRef(),
                snapshot.getNote(),
                parseArray(snapshot.getChunks(), SentenceChunk[].class),
                parseArray(snapshot.getBlank(), Integer[].class),
                parseArray(snapshot.getTags(), String[].class),
                snapshot.getLevel());
    }

    /**
     * 加悲观写锁取题库；题目行有外键保证题库存在，缺失按数据损坏上抛。
     *
     * @param bankId 题库 id
     * @return 已锁定的题库
     */
    private SentenceBank lockedBankOf(long bankId) {
        return bankDao.lockById(bankId)
                .orElseThrow(() -> new IllegalStateException("题目所属题库缺失: " + bankId));
    }

    /**
     * 归一化编辑的中文：必填（null 表示不改）、去首尾空白、4–255 字符。
     *
     * @param zh 原始中文
     * @return 归一化中文；未提供时为 null
     */
    private static String normalizeEditZh(String zh) {
        if (zh == null) {
            return null;
        }
        String trimmed = zh.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "请填写中文");
        }
        if (trimmed.length() < MIN_ZH_LENGTH) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "中文太短");
        }
        if (trimmed.length() > MAX_ZH_LENGTH) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "中文最多 255 个字符");
        }
        return trimmed;
    }

    /**
     * 归一化编辑的难度：空白视为清空，超长按 40000 收敛。
     *
     * @param level 原始难度（调用方保证非 null）
     * @return 归一化难度；清空时为 null
     */
    private static String normalizeLevel(String level) {
        String trimmed = level.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LEVEL_LENGTH) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "难度最多 32 个字符");
        }
        return trimmed;
    }

    /**
     * 归一化编辑的标签：逐项去空白并剔除空白项（允许清空为空列表）。
     *
     * @param tags 原始标签（调用方保证非 null）
     * @return 归一化标签
     */
    private static List<String> normalizeTags(List<String> tags) {
        return tags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }

    /**
     * 归一化编辑的解析：去首尾空白（允许清空为空串），超长按 40000 收敛。
     *
     * @param note 原始解析（调用方保证非 null）
     * @return 归一化解析
     */
    private static String normalizeNote(String note) {
        String trimmed = note.trim();
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "解析最多 1000 个字符");
        }
        return trimmed;
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
