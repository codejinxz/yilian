package io.github.codejinxz.yilian.content.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import io.github.codejinxz.yilian.content.service.dto.BankView;
import io.github.codejinxz.yilian.content.service.dto.SentenceDetail;
import io.github.codejinxz.yilian.content.service.dto.SentencePage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link ContentQueryServiceImpl} 的纯单元测试：Mock Dao，不启动 Spring 容器。
 *
 * <p>重点覆盖可见性规则（匿名只看公开、登录附私有）、增量拉取的 tombstone 与 DRAFT 过滤、 以及分页/游标参数校验，对应 API §5.1–§5.3 与 §1.6。
 */
class ContentQueryServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    private static final String CHUNKS_JSON =
            """
            [{"index":0,"zh":"他","answers":["He"],"tag":"代词","note":null},\
            {"index":1,"zh":"越来越","answers":["more and more","increasingly"],"tag":"副词结构","note":null}]\
            """;

    private static final String BLANK_JSON = "[1,2]";

    private static final String TAGS_JSON = "[\"副词结构\",\"搭配\"]";

    private BankDao bankDao;

    private SentenceDao sentenceDao;

    private ContentQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        bankDao = mock(BankDao.class);
        sentenceDao = mock(SentenceDao.class);
        service = new ContentQueryServiceImpl(bankDao, sentenceDao, new ObjectMapper());
    }

    // ---------- 题库列表 ----------

    /** 匿名列表：以 null 用户查询，公开题库映射出 id/句数等视图字段。 */
    @Test
    void listBanksAnonymousQueriesWithNullUserAndMapsView() {
        SentenceBank bank = publishedPublicBank(1L, 12);
        when(bankDao.searchVisible(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bank), PageRequest.of(0, 20), 1));
        when(bankDao.countPublishedSentences(1L)).thenReturn(48L);

        PageResponse<BankView> response = service.listBanks(null, null, null, null, 1, 20);

        assertThat(response.items()).hasSize(1);
        BankView view = response.items().getFirst();
        assertThat(view.id()).isEqualTo("1");
        assertThat(view.ownerId()).isNull();
        assertThat(view.name()).isEqualTo("官方题库");
        assertThat(view.visibility()).isEqualTo("PUBLIC");
        assertThat(view.status()).isEqualTo("PUBLISHED");
        assertThat(view.version()).isEqualTo(12);
        assertThat(view.sentenceCount()).isEqualTo(48);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.total()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();
    }

    /** 登录用户列表：当前用户 id 必须传给 Dao，私有题库才可能落入结果集。 */
    @Test
    void listBanksLoggedInForwardsUserId() {
        when(bankDao.searchVisible(any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listBanks(7L, null, null, null, 1, 20);

        verify(bankDao).searchVisible(eq(7L), isNull(), isNull(), any(Pageable.class));
    }

    /** 关键字去除首尾空白后传给 Dao；空白串视为未过滤。 */
    @Test
    void listBanksTrimsKeyword() {
        when(bankDao.searchVisible(any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listBanks(null, "  专八  ", null, null, 1, 20);

        verify(bankDao).searchVisible(isNull(), eq("专八"), isNull(), any(Pageable.class));
    }

    /** page/size 越界统一 40004，不触达 Dao。 */
    @Test
    void listBanksRejectsInvalidPagination() {
        assertThatThrownBy(() -> service.listBanks(null, null, null, null, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.INVALID_PAGINATION);
        assertThatThrownBy(() -> service.listBanks(null, null, null, null, 1, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.INVALID_PAGINATION);
        assertThatThrownBy(() -> service.listBanks(null, null, null, null, 1, 101))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.INVALID_PAGINATION);
        verify(bankDao, never()).searchVisible(any(), any(), any(), any(Pageable.class));
    }

    /** 公开可见集合恒为已发布；请求其他状态返回空页，不触达 Dao。 */
    @Test
    void listBanksNonPublishedStatusReturnsEmptyPage() {
        PageResponse<BankView> response =
                service.listBanks(null, null, null, ContentStatus.DISABLED, 1, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isZero();
        verify(bankDao, never()).searchVisible(any(), any(), any(), any(Pageable.class));
    }

    /** 分页请求按 id 升序稳定排序，保证翻页不抖动。 */
    @Test
    void listBanksUsesStableIdSort() {
        when(bankDao.searchVisible(any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listBanks(null, null, null, null, 2, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(bankDao).searchVisible(isNull(), isNull(), isNull(), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
    }

    // ---------- 题库详情 ----------

    /** 匿名访问公开已发布题库返回视图。 */
    @Test
    void getBankPublicAnonymousReturnsView() {
        SentenceBank bank = publishedPublicBank(1L, 12);
        when(bankDao.findById(1L)).thenReturn(Optional.of(bank));
        when(bankDao.countPublishedSentences(1L)).thenReturn(48L);

        BankView view = service.getBank(1L, null, false);

        assertThat(view.id()).isEqualTo("1");
        assertThat(view.sentenceCount()).isEqualTo(48);
    }

    /** 题库不存在返回 40401。 */
    @Test
    void getBankMissingReturns40401() {
        when(bankDao.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBank(1L, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.BANK_NOT_FOUND);
    }

    /** 匿名访问私有题库按「不存在」处理，避免向探测者泄露存在性。 */
    @Test
    void getBankPrivateAnonymousIs404() {
        when(bankDao.findById(1L)).thenReturn(Optional.of(publishedPrivateBank(1L, 9L)));

        assertThatThrownBy(() -> service.getBank(1L, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.BANK_NOT_FOUND);
    }

    /** 登录用户访问他人私有题库返回 40300。 */
    @Test
    void getBankPrivateNonOwnerIs40300() {
        when(bankDao.findById(1L)).thenReturn(Optional.of(publishedPrivateBank(1L, 9L)));

        assertThatThrownBy(() -> service.getBank(1L, 7L, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.ACCESS_DENIED);
    }

    /** 所有者可以查看自己的私有题库。 */
    @Test
    void getBankPrivateOwnerAllowed() {
        when(bankDao.findById(1L)).thenReturn(Optional.of(publishedPrivateBank(1L, 9L)));
        when(bankDao.countPublishedSentences(1L)).thenReturn(5L);

        BankView view = service.getBank(1L, 9L, false);

        assertThat(view.id()).isEqualTo("1");
        assertThat(view.ownerId()).isEqualTo("9");
    }

    /** 未发布（DRAFT）与已软删（RECYCLED）题库对非管理端一律不可见。 */
    @Test
    void getBankUnpublishedOrRecycledIs404() {
        SentenceBank draft = SentenceBank.create(9L, "草稿库", null, BankVisibility.PRIVATE, 1L, NOW);
        ReflectionTestUtils.setField(draft, "id", 2L);
        SentenceBank recycled = publishedPublicBank(3L, 1);
        recycled.recycle(1L, NOW);

        when(bankDao.findById(2L)).thenReturn(Optional.of(draft));
        when(bankDao.findById(3L)).thenReturn(Optional.of(recycled));

        assertThatThrownBy(() -> service.getBank(2L, 9L, false))
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.BANK_NOT_FOUND);
        assertThatThrownBy(() -> service.getBank(3L, null, false))
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.BANK_NOT_FOUND);
    }

    // ---------- 增量拉取 ----------

    /** 增量拉取映射：DRAFT 跳过、停用回 tombstone、已发布回完整内容，游标取本页最大 version。 */
    @Test
    void listSentencesSkipsDraftAndMapsTombstones() {
        SentenceBank bank = publishedPublicBank(1L, 12);
        when(bankDao.findById(1L)).thenReturn(Optional.of(bank));
        Sentence draft = demotedDraftSentence("01JDRAFT000000000000000000A", 10);
        Sentence disabled = disabledSentence("01JDISABLE00000000000000000B", 11);
        Sentence published = publishedSentence("01JPUBLIS000000000000000000C", 12);
        when(sentenceDao.findChangedSince(1L, 0, 100))
                .thenReturn(List.of(draft, disabled, published));
        when(sentenceDao.findVersionsByIds(List.of(1001L))).thenReturn(List.of(snapshot(1001L, 3)));

        SentencePage page = service.listSentences(1L, 0, 100, null, false);

        assertThat(page.version()).isEqualTo(12);
        assertThat(page.items()).hasSize(2);
        SentenceDetail tombstone = page.items().getFirst();
        assertThat(tombstone.id()).isEqualTo(disabled.getId());
        assertThat(tombstone.deleted()).isTrue();
        assertThat(tombstone.status()).isEqualTo("DISABLED");
        assertThat(tombstone.zh()).isNull();
        SentenceDetail detail = page.items().getLast();
        assertThat(detail.id()).isEqualTo(published.getId());
        assertThat(detail.deleted()).isFalse();
        assertThat(detail.sentenceVersionId()).isEqualTo("1001");
        assertThat(detail.questionVersion()).isEqualTo(3);
        assertThat(detail.zh()).isEqualTo("他越来越重视这件事。");
        assertThat(detail.ref()).isEqualTo("He is paying more and more attention to this.");
        assertThat(detail.chunks()).hasSize(2);
        assertThat(detail.chunks().getFirst().answers()).containsExactly("He");
        assertThat(detail.blank()).containsExactly(1, 2);
        assertThat(detail.tags()).containsExactly("副词结构", "搭配");
        assertThat(detail.level()).isEqualTo("TEM8");
        assertThat(page.nextSinceVersion()).isEqualTo(12);
        assertThat(page.hasMore()).isFalse();
    }

    /** 取满 limit 时 hasMore=true，客户端继续翻页。 */
    @Test
    void listSentencesHasMoreWhenLimitReached() {
        when(bankDao.findById(1L)).thenReturn(Optional.of(publishedPublicBank(1L, 12)));
        when(sentenceDao.findChangedSince(1L, 0, 2))
                .thenReturn(
                        List.of(
                                publishedSentence("01JPUBLIS000000000000000000C", 11),
                                publishedSentence("01JPUBLIS000000000000000000D", 12)));
        when(sentenceDao.findVersionsByIds(List.of(1001L))).thenReturn(List.of(snapshot(1001L, 3)));

        SentencePage page = service.listSentences(1L, 0, 2, null, false);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextSinceVersion()).isEqualTo(12);
    }

    /** 无数据时游标保持不变且 hasMore=false，循环在此终止。 */
    @Test
    void listSentencesEmptyPageKeepsCursor() {
        when(bankDao.findById(1L)).thenReturn(Optional.of(publishedPublicBank(1L, 12)));
        when(sentenceDao.findChangedSince(1L, 12, 100)).thenReturn(List.of());

        SentencePage page = service.listSentences(1L, 12, 100, null, false);

        assertThat(page.version()).isEqualTo(12);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextSinceVersion()).isEqualTo(12);
        assertThat(page.hasMore()).isFalse();
    }

    /** sinceVersion 大于题库当前版本返回 40005。 */
    @Test
    void listSentencesSinceVersionBeyondBankIs40005() {
        when(bankDao.findById(1L)).thenReturn(Optional.of(publishedPublicBank(1L, 12)));

        assertThatThrownBy(() -> service.listSentences(1L, 13, 100, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.INVALID_SINCE_VERSION);
    }

    /** sinceVersion 为负返回 40005；limit 越界返回 40004。 */
    @Test
    void listSentencesRejectsInvalidCursorAndLimit() {
        when(bankDao.findById(1L)).thenReturn(Optional.of(publishedPublicBank(1L, 12)));

        assertThatThrownBy(() -> service.listSentences(1L, -1, 100, null, false))
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.INVALID_SINCE_VERSION);
        assertThatThrownBy(() -> service.listSentences(1L, 0, 0, null, false))
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.INVALID_PAGINATION);
        assertThatThrownBy(() -> service.listSentences(1L, 0, 201, null, false))
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.INVALID_PAGINATION);
    }

    /** 匿名拉私有题库返回 40100，提示登录后可能可见；题库缺失仍为 40401。 */
    @Test
    void listSentencesAnonymousPrivateIs40100AndMissingIs40401() {
        when(bankDao.findById(1L)).thenReturn(Optional.of(publishedPrivateBank(1L, 9L)));
        when(bankDao.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listSentences(1L, 0, 100, null, false))
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.AUTH_REQUIRED);
        assertThatThrownBy(() -> service.listSentences(2L, 0, 100, null, false))
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.BANK_NOT_FOUND);
    }

    /** 登录用户拉他人私有题库返回 40300；所有者可以拉取。 */
    @Test
    void listSentencesPrivateOwnerAllowedNonOwnerDenied() {
        when(bankDao.findById(1L)).thenReturn(Optional.of(publishedPrivateBank(1L, 9L)));
        when(sentenceDao.findChangedSince(1L, 0, 100)).thenReturn(List.of());

        assertThatThrownBy(() -> service.listSentences(1L, 0, 100, 7L, false))
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.ACCESS_DENIED);
        assertThat(service.listSentences(1L, 0, 100, 9L, false).items()).isEmpty();
    }

    // ---------- 按 id 取题 ----------

    /** 已删题目返回 tombstone；不存在返回空。 */
    @Test
    void findSentenceDeletedIsTombstoneAndMissingIsEmpty() {
        Sentence recycled = publishedSentence("01JRECYCL00000000000000000E", 13);
        recycled.changeStatus(ContentStatus.RECYCLED, 13L, 1L, NOW);
        when(sentenceDao.findById(recycled.getId())).thenReturn(Optional.of(recycled));
        when(sentenceDao.findById("missing")).thenReturn(Optional.empty());

        Optional<SentenceDetail> tombstone = service.findSentence(recycled.getId());

        assertThat(tombstone).isPresent();
        assertThat(tombstone.get().deleted()).isTrue();
        assertThat(tombstone.get().status()).isEqualTo("RECYCLED");
        assertThat(tombstone.get().zh()).isNull();
        assertThat(service.findSentence("missing")).isEmpty();
    }

    /** 已发布题目按当前快照返回完整内容。 */
    @Test
    void findSentencePublishedReturnsDetail() {
        Sentence published = publishedSentence("01JPUBLIS000000000000000000C", 12);
        when(sentenceDao.findById(published.getId())).thenReturn(Optional.of(published));
        when(sentenceDao.findVersionsByIds(List.of(1001L))).thenReturn(List.of(snapshot(1001L, 3)));

        SentenceDetail detail = service.findSentence(published.getId()).orElseThrow();

        assertThat(detail.practiceable()).isTrue();
        assertThat(detail.chunks()).hasSize(2);
    }

    // ---------- 练习候选 ----------

    /** 候选只包含能取到快照的已发布题目；快照缺失的候选被丢弃。 */
    @Test
    void findPracticeCandidatesSkipsMissingSnapshot() {
        Sentence good = publishedSentence("01JPUBLIS000000000000000000C", 12);
        Sentence broken = publishedSentence("01JBROKEN000000000000000000F", 12);
        ReflectionTestUtils.setField(broken, "currentVersionId", 4040L);
        when(sentenceDao.findPracticeCandidates(isNull(), isNull(), eq(20)))
                .thenReturn(List.of(good, broken));
        when(sentenceDao.findVersionsByIds(List.of(1001L, 4040L)))
                .thenReturn(List.of(snapshot(1001L, 3)));

        List<SentenceDetail> candidates = service.findPracticeCandidates(null, null, 20);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().id()).isEqualTo(good.getId());
    }

    // ---------- 实体构造辅助 ----------

    /**
     * 构造已发布公开题库。
     *
     * @param id 题库 id
     * @param version 银行级版本
     * @return 已发布题库
     */
    private static SentenceBank publishedPublicBank(long id, long version) {
        SentenceBank bank =
                SentenceBank.create(null, "官方题库", "专八翻译语块", BankVisibility.PUBLIC, 1L, NOW);
        bank.advanceVersion(version, 1L, NOW);
        ReflectionTestUtils.setField(bank, "id", id);
        return bank;
    }

    /**
     * 构造已发布私有题库。
     *
     * @param id 题库 id
     * @param ownerId 所有者
     * @return 已发布私有题库
     */
    private static SentenceBank publishedPrivateBank(long id, long ownerId) {
        SentenceBank bank =
                SentenceBank.create(ownerId, "我的题库", null, BankVisibility.PRIVATE, ownerId, NOW);
        bank.advanceVersion(1, ownerId, NOW);
        ReflectionTestUtils.setField(bank, "id", id);
        return bank;
    }

    /**
     * 构造被降级回草稿的题目（历史上曾发布，占用银行级版本）。
     *
     * @param id ULID
     * @param version 银行级版本
     * @return DRAFT 题目
     */
    private static Sentence demotedDraftSentence(String id, long version) {
        Sentence sentence = publishedSentence(id, version);
        sentence.changeStatus(ContentStatus.DRAFT, version, 1L, NOW);
        return sentence;
    }

    /**
     * 构造已停用题目（占用银行级版本，无内容返回）。
     *
     * @param id ULID
     * @param version 银行级版本
     * @return DISABLED 题目
     */
    private static Sentence disabledSentence(String id, long version) {
        Sentence sentence = Sentence.create(id, 1L, null, "fp-" + id, 1L, NOW);
        sentence.pointToVersion(1002L, 2, version - 1, "fp-" + id, 1L);
        sentence.changeStatus(ContentStatus.DISABLED, version, 1L, NOW);
        return sentence;
    }

    /**
     * 构造已发布题目。
     *
     * @param id ULID
     * @param version 银行级版本
     * @return PUBLISHED 题目，指向快照 1001
     */
    private static Sentence publishedSentence(String id, long version) {
        Sentence sentence = Sentence.create(id, 1L, null, "fp-" + id, 1L, NOW);
        sentence.pointToVersion(1001L, 3, version, "fp-" + id, 1L);
        return sentence;
    }

    /**
     * 构造内容快照。
     *
     * @param id 快照 id
     * @param versionNo 单题版本号
     * @return 快照实体
     */
    private static SentenceVersion snapshot(long id, int versionNo) {
        SentenceVersion snapshot =
                SentenceVersion.create(
                        "01JPUBLIS000000000000000000C",
                        1L,
                        versionNo,
                        "他越来越重视这件事。",
                        "He is paying more and more attention to this.",
                        "「越来越」用 more and more 或 increasingly。",
                        CHUNKS_JSON,
                        BLANK_JSON,
                        TAGS_JSON,
                        "TEM8",
                        null,
                        null,
                        "hash",
                        null,
                        1L,
                        NOW);
        ReflectionTestUtils.setField(snapshot, "id", id);
        return snapshot;
    }
}
