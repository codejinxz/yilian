package io.github.codejinxz.yilian.content.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.content.dao.BankDao;
import io.github.codejinxz.yilian.content.dao.SentenceDao;
import io.github.codejinxz.yilian.content.entity.BankVisibility;
import io.github.codejinxz.yilian.content.entity.ContentStatus;
import io.github.codejinxz.yilian.content.entity.Sentence;
import io.github.codejinxz.yilian.content.entity.SentenceBank;
import io.github.codejinxz.yilian.content.entity.SentenceVersion;
import io.github.codejinxz.yilian.content.service.ContentQueryService;
import io.github.codejinxz.yilian.content.service.dto.BankView;
import io.github.codejinxz.yilian.content.service.dto.SentenceChunk;
import io.github.codejinxz.yilian.content.service.dto.SentenceDetail;
import io.github.codejinxz.yilian.content.service.dto.SentencePage;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 内容只读查询实现：题库列表、详情、按银行级版本增量拉题。
 *
 * <p>可见性规则（对应 API §5.1–§5.3）：题库可读集合恒为「已发布且未软删」，匿名只看 PUBLIC， 登录用户额外可读自己的私有题库；私有题库对匿名统一按不可见处理，
 * 登录后非所有者才回 40300， 增量拉取对匿名回 40100 以提示「登录后可能可见」。未发布（DRAFT）题目对非管理端完全跳过， 停用/回收/归档题目按 tombstone
 * 返回，客户端据此清理本地缓存。
 */
@Service
public class ContentQueryServiceImpl implements ContentQueryService {

    /** 单页题库/题目条数上限；与管理端分页口径一致，防止一次拉取拖垮内存。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 增量拉取单页条数上限，对应 API §1.6 的 limit 1–200。 */
    private static final int MAX_LIMIT = 200;

    private final BankDao bankDao;

    private final SentenceDao sentenceDao;

    private final ObjectMapper objectMapper;

    /**
     * 注入题库与题目 Dao，以及解析快照 JSON 列的映射器。
     *
     * @param bankDao 题库 Dao
     * @param sentenceDao 题目与快照 Dao
     * @param objectMapper Spring Boot 提供的 Jackson 3 映射器
     */
    public ContentQueryServiceImpl(
            BankDao bankDao, SentenceDao sentenceDao, ObjectMapper objectMapper) {
        this.bankDao = bankDao;
        this.sentenceDao = sentenceDao;
        this.objectMapper = objectMapper;
    }

    /**
     * 题库列表：匿名只返回公开题库，登录后附加自己的私有题库。
     *
     * <p>可见集合由 Dao 固定为已发布且未软删；题数逐题统计，页容量有上限（≤100），代价可控。 请求其他状态时直接返回空页：可见集合里不可能有它们，静默空页比报错更贴近筛选语义。
     *
     * @param userId 当前用户 id，可为 null
     * @param keyword 关键字
     * @param visibility 可见性过滤
     * @param status 状态过滤，仅 PUBLISHED 有效；null 视为 PUBLISHED
     * @param page 页码
     * @param size 每页大小
     * @return 分页视图
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<BankView> listBanks(
            Long userId,
            String keyword,
            BankVisibility visibility,
            ContentStatus status,
            int page,
            int size) {
        requireValidPage(page, size);
        if (status != null && status != ContentStatus.PUBLISHED) {
            return PageResponse.of(List.of(), page, size, 0);
        }
        String normalizedKeyword = normalizeKeyword(keyword);
        Page<SentenceBank> result =
                bankDao.searchVisible(
                        userId,
                        normalizedKeyword,
                        visibility,
                        PageRequest.of(page - 1, size, Sort.by(Sort.Direction.ASC, "id")));
        List<BankView> items = result.getContent().stream().map(this::toBankView).toList();
        return PageResponse.of(items, page, size, result.getTotalElements());
    }

    /**
     * 题库详情。
     *
     * <p>私有题库对匿名按「不存在」返回（40401），避免向未登录探测者泄露题库存在性； 登录后非所有者回 40300，与 API §5.2 的错误码约定一致。
     *
     * @param bankId 题库 id
     * @param userId 当前用户 id，可为 null
     * @param admin 是否管理端
     * @return 题库视图
     */
    @Override
    @Transactional(readOnly = true)
    public BankView getBank(long bankId, Long userId, boolean admin) {
        SentenceBank bank = requireVisibleBank(bankId, userId, admin, false);
        return toBankView(bank);
    }

    /**
     * 按 sinceVersion 增量拉取题目。
     *
     * <p>游标语义遵循 API §1.6：{@code version > sinceVersion}，升序返回； {@code nextSinceVersion} 取本页最大
     * version，无数据时等于 {@code sinceVersion}； {@code hasMore} 以「取满 limit」近似，客户端拉到空页自然终止。
     *
     * @param bankId 题库 id
     * @param sinceVersion 起始版本
     * @param limit 每页条数
     * @param userId 当前用户 id，可为 null
     * @param admin 是否管理端
     * @return 增量响应
     */
    @Override
    @Transactional(readOnly = true)
    public SentencePage listSentences(
            long bankId, long sinceVersion, int limit, Long userId, boolean admin) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(ApiCode.INVALID_PAGINATION);
        }
        if (sinceVersion < 0) {
            throw new BusinessException(ApiCode.INVALID_SINCE_VERSION);
        }
        SentenceBank bank = requireVisibleBank(bankId, userId, admin, true);
        if (sinceVersion > bank.getVersion()) {
            throw new BusinessException(ApiCode.INVALID_SINCE_VERSION);
        }
        List<Sentence> rows = sentenceDao.findChangedSince(bankId, sinceVersion, limit);
        List<SentenceDetail> items = new ArrayList<>(rows.size());
        for (Sentence row : rows) {
            if (!admin && row.getStatus() == ContentStatus.DRAFT) {
                // 未发布内容对练习端不可见；跳过不影响游标：一旦发布必然 bump version。
                continue;
            }
            if (!admin && !isPublishedActive(row)) {
                items.add(
                        SentenceDetail.tombstone(
                                row.getId(), bankId, row.getVersion(), row.getStatus().name()));
                continue;
            }
            items.add(toSentenceDetail(row));
        }
        long nextSinceVersion = rows.isEmpty() ? sinceVersion : rows.getLast().getVersion();
        boolean hasMore = rows.size() == limit;
        return new SentencePage(bank.getVersion(), items, nextSinceVersion, hasMore);
    }

    /**
     * 按 id 取题目详情（可能为 tombstone）。
     *
     * <p>本方法不带用户上下文，可见性由调用方（practice 模块）负责校验；题目已删时返回 tombstone， 快照缺失视为数据损坏，直接抛错进入 error_log
     * 而不是静默降级。
     *
     * @param sentenceId 题目 id
     * @return 题目视图；题目不存在时为空
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<SentenceDetail> findSentence(String sentenceId) {
        return sentenceDao.findById(sentenceId).map(this::requireDetail);
    }

    /**
     * 按 id 批量取题目详情。
     *
     * <p>保持入参顺序；不存在的 id 直接跳过。题目量与答题记录同量级，逐条查询即可， 暂不为一次性读取引入批量 Dao。
     *
     * @param sentenceIds 题目 id 集合
     * @return 题目视图列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<SentenceDetail> findSentences(Collection<String> sentenceIds) {
        if (sentenceIds == null || sentenceIds.isEmpty()) {
            return List.of();
        }
        List<SentenceDetail> items = new ArrayList<>(sentenceIds.size());
        for (String sentenceId : sentenceIds) {
            sentenceDao.findById(sentenceId).map(this::requireDetail).ifPresent(items::add);
        }
        return List.copyOf(items);
    }

    /**
     * 练习候选：只返回已发布题库中的已发布题目。
     *
     * <p>快照缺失的候选直接丢弃：练习不能用残缺数据，宁可少一题也不能崩一次拉题。
     *
     * @param bankId 题库 id，可为 null
     * @param excludeSentenceId 排除题目
     * @param limit 最多条数
     * @return 候选题目
     */
    @Override
    @Transactional(readOnly = true)
    public List<SentenceDetail> findPracticeCandidates(
            Long bankId, String excludeSentenceId, int limit) {
        List<Sentence> rows = sentenceDao.findPracticeCandidates(bankId, excludeSentenceId, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> versionIds = rows.stream().map(Sentence::getCurrentVersionId).toList();
        List<SentenceVersion> snapshots = sentenceDao.findVersionsByIds(versionIds);
        List<SentenceDetail> items = new ArrayList<>(rows.size());
        for (Sentence row : rows) {
            snapshots.stream()
                    .filter(snapshot -> snapshot.getId().equals(row.getCurrentVersionId()))
                    .findFirst()
                    .map(snapshot -> toDetail(row, snapshot))
                    .ifPresent(items::add);
        }
        return List.copyOf(items);
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
     * 归一化关键字：空白串视为未过滤。
     *
     * @param keyword 原始关键字
     * @return 去除首尾空白后的关键字；无有效内容时为 null
     */
    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }

    /**
     * 判断题目是否处于「已发布且未软删」。
     *
     * @param sentence 题目
     * @return 可对练习端返回完整内容时为 true
     */
    private static boolean isPublishedActive(Sentence sentence) {
        return sentence.getDeletedAt() == null && sentence.getStatus() == ContentStatus.PUBLISHED;
    }

    /**
     * 取可见题库：不存在回 40401；私有题库匿名回 {@code authRequiredForPrivate} 决定的错误码， 登录后非所有者回 40300；未发布或已软删对非管理端一律
     * 40401。
     *
     * @param bankId 题库 id
     * @param userId 当前用户 id，可为 null
     * @param admin 是否管理端
     * @param authRequiredForPrivate 匿名访问私有题库时是否回 40100（增量拉取场景）
     * @return 可见题库
     */
    private SentenceBank requireVisibleBank(
            long bankId, Long userId, boolean admin, boolean authRequiredForPrivate) {
        SentenceBank bank =
                bankDao.findById(bankId)
                        .orElseThrow(() -> new BusinessException(ApiCode.BANK_NOT_FOUND));
        if (admin) {
            return bank;
        }
        if (bank.getVisibility() == BankVisibility.PRIVATE) {
            if (userId == null) {
                throw new BusinessException(
                        authRequiredForPrivate ? ApiCode.AUTH_REQUIRED : ApiCode.BANK_NOT_FOUND);
            }
            if (!userId.equals(bank.getOwnerId())) {
                throw new BusinessException(ApiCode.ACCESS_DENIED);
            }
        }
        if (bank.getStatus() != ContentStatus.PUBLISHED || bank.getDeletedAt() != null) {
            // 未发布/已软删题库对练习端等同不存在，不泄露其状态与存在性。
            throw new BusinessException(ApiCode.BANK_NOT_FOUND);
        }
        return bank;
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
     * 构造题目详情：已删回 tombstone，其余必须能取到当前快照。
     *
     * @param sentence 题目实体
     * @return 题目视图
     */
    private SentenceDetail requireDetail(Sentence sentence) {
        if (sentence.getDeletedAt() != null) {
            return SentenceDetail.tombstone(
                    sentence.getId(),
                    sentence.getBankId(),
                    sentence.getVersion(),
                    sentence.getStatus().name());
        }
        return toSentenceDetail(sentence);
    }

    /**
     * 按当前快照构造完整题目详情。
     *
     * @param sentence 题目实体
     * @return 题目视图
     */
    private SentenceDetail toSentenceDetail(Sentence sentence) {
        Long currentVersionId = sentence.getCurrentVersionId();
        if (currentVersionId == null) {
            throw new IllegalStateException("题目缺少内容快照: " + sentence.getId());
        }
        SentenceVersion snapshot =
                sentenceDao.findVersionsByIds(List.of(currentVersionId)).stream()
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("题目快照缺失: " + sentence.getId()));
        return toDetail(sentence, snapshot);
    }

    /**
     * 由题目身份与内容快照组装视图。
     *
     * @param sentence 题目实体
     * @param snapshot 当前内容快照
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
                false,
                snapshot.getZh(),
                snapshot.getRef(),
                snapshot.getNote(),
                parseArray(snapshot.getChunks(), SentenceChunk[].class),
                parseArray(snapshot.getBlank(), Integer[].class),
                parseArray(snapshot.getTags(), String[].class),
                snapshot.getLevel());
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
}
