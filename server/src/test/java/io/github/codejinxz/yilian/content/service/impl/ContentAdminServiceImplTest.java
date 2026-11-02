package io.github.codejinxz.yilian.content.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.config.CacheConfig;
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
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.BankView;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@link ContentAdminServiceImpl} 的纯单元测试：Mock Dao，不启动 Spring 容器。
 *
 * <p>重点覆盖批量操作的核心语义——不存在的 id 跳过并在响应暴露计数、已处于目标状态的不重复推进修订号、 每题递增银行修订号（P1-1）、悲观锁按题库 id 升序获取——以及参数白名单与
 * 40401 收敛。
 */
class ContentAdminServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    private static final String CHUNKS_JSON =
            """
            [{"index":0,"zh":"他","answers":["He"],"tag":"代词","note":null},\
            {"index":1,"zh":"越来越","answers":["more and more","increasingly"],"tag":"副词结构","note":null}]\
            """;

    private static final String BLANK_JSON = "[1,2]";

    private static final String TAGS_JSON = "[\"搭配\",\"副词结构\"]";

    private BankDao bankDao;

    private SentenceDao sentenceDao;

    private AdminSentenceQueryDao adminSentenceQueryDao;

    private ContentImportService contentImportService;

    private ContentAdminServiceImpl service;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        bankDao = mock(BankDao.class);
        sentenceDao = mock(SentenceDao.class);
        adminSentenceQueryDao = mock(AdminSentenceQueryDao.class);
        contentImportService = mock(ContentImportService.class);
        objectMapper = new ObjectMapper();
        // 真实 CacheManager + 真实失效装配：写路径的 evictWrittenBanks 在空缓存上是无害 no-op。
        BankMetadataCache bankMetadataCache =
                new BankMetadataCache(bankDao, new CacheConfig().cacheManager());
        service =
                new ContentAdminServiceImpl(
                        bankDao,
                        sentenceDao,
                        adminSentenceQueryDao,
                        contentImportService,
                        bankMetadataCache,
                        objectMapper);
    }

    /** 题库检索：字符串筛选解析为枚举透传，分页按 id 倒序，视图带题数统计。 */
    @Test
    void adminListBanksParsesFiltersAndCountsSentences() {
        SentenceBank bank = bank(5L, ContentStatus.DRAFT, 3);
        when(bankDao.searchAdmin(
                        eq("官方"),
                        eq(BankVisibility.PUBLIC),
                        eq(ContentStatus.DRAFT),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bank)));
        when(bankDao.countPublishedSentences(5L)).thenReturn(12L);

        PageResponse<BankView> page = service.adminListBanks("官方", "PUBLIC", "DRAFT", 2, 5);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(bankDao)
                .searchAdmin(
                        eq("官方"),
                        eq(BankVisibility.PUBLIC),
                        eq(ContentStatus.DRAFT),
                        pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "id"));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).id()).isEqualTo("5");
        assertThat(page.items().get(0).ownerId()).isNull();
        assertThat(page.items().get(0).status()).isEqualTo("DRAFT");
        assertThat(page.items().get(0).sentenceCount()).isEqualTo(12L);
    }

    /** 题库检索参数越界与非法枚举值：40004/40000 收敛，不触达 Dao。 */
    @Test
    void adminListBanksRejectsInvalidPagingAndEnums() {
        assertThatThrownBy(() -> service.adminListBanks(null, null, null, 0, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.INVALID_PAGINATION));
        assertThatThrownBy(() -> service.adminListBanks(null, null, null, 1, 101))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.INVALID_PAGINATION));
        assertThatThrownBy(() -> service.adminListBanks(null, null, "NOPE", 1, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.adminListBanks(null, "INTERNAL", null, 1, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        verifyNoInteractionsOnDaos();
    }

    /** 题库详情：不存在回 40401。 */
    @Test
    void adminGetBankRejectsMissingBankWith40401() {
        when(bankDao.findById(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.adminGetBank(5L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode()).isEqualTo(ApiCode.BANK_NOT_FOUND));
    }

    /** 新建题库：官方（owner 空）、初始 DRAFT、版本 0；名称去空白、空白说明归一为 null。 */
    @Test
    void adminCreateBankCreatesOfficialDraftAndNormalizesText() {
        when(bankDao.save(any(SentenceBank.class)))
                .thenAnswer(
                        invocation -> {
                            SentenceBank bank = invocation.getArgument(0);
                            ReflectionTestUtils.setField(bank, "id", 12L);
                            return bank;
                        });

        BankView view = service.adminCreateBank(9L, "  新题库  ", "  ", "PUBLIC");

        ArgumentCaptor<SentenceBank> captor = ArgumentCaptor.forClass(SentenceBank.class);
        verify(bankDao).save(captor.capture());
        SentenceBank saved = captor.getValue();
        assertThat(saved.getOwnerId()).isNull();
        assertThat(saved.getName()).isEqualTo("新题库");
        assertThat(saved.getDescription()).isNull();
        assertThat(saved.getVisibility()).isEqualTo(BankVisibility.PUBLIC);
        assertThat(saved.getStatus()).isEqualTo(ContentStatus.DRAFT);
        assertThat(saved.getVersion()).isZero();
        assertThat(view.id()).isEqualTo("12");
        assertThat(view.status()).isEqualTo("DRAFT");
    }

    /** 新建校验：名称空白 / 可见性非法 → 40000。 */
    @Test
    void adminCreateBankRejectsBlankNameAndBadVisibility() {
        assertThatThrownBy(() -> service.adminCreateBank(9L, "  ", null, "PUBLIC"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.adminCreateBank(9L, "题库", null, "INTERNAL"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        verify(bankDao, never()).save(any());
    }

    /** 部分更新：null 字段不改，status 流转生效（发布记 publishedAt）。 */
    @Test
    void adminUpdateBankAppliesOnlyProvidedFields() {
        SentenceBank bank = bank(5L, ContentStatus.DRAFT, 3);
        when(bankDao.findById(5L)).thenReturn(Optional.of(bank));
        when(bankDao.save(any(SentenceBank.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BankView view = service.adminUpdateBank(9L, 5L, null, "新说明", null, "PUBLISHED");

        assertThat(bank.getName()).isEqualTo("官方题库");
        assertThat(bank.getDescription()).isEqualTo("新说明");
        assertThat(bank.getStatus()).isEqualTo(ContentStatus.PUBLISHED);
        assertThat(bank.getPublishedAt()).isNotNull();
        assertThat(view.status()).isEqualTo("PUBLISHED");
    }

    /** [012 AC-5] 说明契约：null=不改、空串=清空——空串显式置 null 落库，null 保持原说明。 */
    @Test
    void adminUpdateBankClearsDescriptionWithEmptyStringOnly() {
        SentenceBank bank = bank(5L, ContentStatus.DRAFT, 3);
        when(bankDao.findById(5L)).thenReturn(Optional.of(bank));
        when(bankDao.save(any(SentenceBank.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // 空串 = 清空：实体说明置 null，视图回 null。
        service.adminUpdateBank(9L, 5L, null, "", null, null);
        assertThat(bank.getDescription()).isNull();

        // null = 不改：清空后再传 null，说明保持 null（不会复活），再设值后 null 不覆盖。
        service.adminUpdateBank(9L, 5L, null, "恢复说明", null, null);
        assertThat(bank.getDescription()).isEqualTo("恢复说明");
        service.adminUpdateBank(9L, 5L, "改名", null, null, null);
        assertThat(bank.getDescription()).isEqualTo("恢复说明");
        assertThat(bank.getName()).isEqualTo("改名");
    }

    /** 更新/删除：题库不存在回 40401。 */
    @Test
    void adminUpdateAndDeleteRejectMissingBankWith40401() {
        when(bankDao.findById(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.adminUpdateBank(9L, 5L, null, null, null, "PUBLISHED"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode()).isEqualTo(ApiCode.BANK_NOT_FOUND));
        assertThatThrownBy(() -> service.adminDeleteBank(9L, 5L, "原因"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode()).isEqualTo(ApiCode.BANK_NOT_FOUND));
    }

    /** 软删题库：状态流转 RECYCLED 并写软删标记；不物理删。 */
    @Test
    void adminDeleteBankRecyclesInsteadOfPhysicalDelete() {
        SentenceBank bank = bank(5L, ContentStatus.PUBLISHED, 3);
        when(bankDao.findById(5L)).thenReturn(Optional.of(bank));
        when(bankDao.save(any(SentenceBank.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.adminDeleteBank(9L, 5L, "重复题库");

        assertThat(bank.getStatus()).isEqualTo(ContentStatus.RECYCLED);
        assertThat(bank.getDeletedAt()).isNotNull();
        verify(bankDao).save(bank);
    }

    /** 软删校验：原因空白 → 40000，不触达 Dao。 */
    @Test
    void adminDeleteBankRejectsBlankReason() {
        assertThatThrownBy(() -> service.adminDeleteBank(9L, 5L, "  "))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        verify(bankDao, never()).findById(5L);
    }

    /** 题库内题目列表：题库不存在回 40401。 */
    @Test
    void adminListBankSentencesRejectsMissingBankWith40401() {
        when(bankDao.findById(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.adminListBankSentences(5L, null, null, 1, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode()).isEqualTo(ApiCode.BANK_NOT_FOUND));
    }

    /** 题库内题目列表：查询 Dao 参数透传（不隐藏回收站），行解析为完整视图。 */
    @Test
    void adminListBankSentencesForwardsQueryAndMapsRows() {
        when(bankDao.findById(5L)).thenReturn(Optional.of(bank(5L, ContentStatus.PUBLISHED, 7)));
        when(adminSentenceQueryDao.search(
                        eq(5L), eq("他"), isNull(), eq("DRAFT"), eq(false), eq(5), eq(5)))
                .thenReturn(List.of(row(null), row(NOW)));
        when(adminSentenceQueryDao.count(eq(5L), eq("他"), isNull(), eq("DRAFT"), eq(false)))
                .thenReturn(2L);

        PageResponse<SentenceDetail> page = service.adminListBankSentences(5L, "他", "DRAFT", 2, 5);

        assertThat(page.total()).isEqualTo(2);
        SentenceDetail active = page.items().get(0);
        assertThat(active.id()).isEqualTo("01A");
        assertThat(active.bankId()).isEqualTo("5");
        assertThat(active.sentenceVersionId()).isEqualTo("11");
        assertThat(active.questionVersion()).isEqualTo(2);
        assertThat(active.status()).isEqualTo("PUBLISHED");
        assertThat(active.deleted()).isFalse();
        assertThat(active.chunks()).hasSize(2);
        assertThat(active.chunks().get(0).answers()).containsExactly("He");
        assertThat(active.blank()).containsExactly(1, 2);
        assertThat(active.tags()).containsExactly("搭配", "副词结构");
        assertThat(active.level()).isEqualTo("CET-4");
        assertThat(page.items().get(1).deleted()).isTrue();
    }

    /** 跨题库列表缺省：status 为空 → 隐藏回收站（hideRecycled=true）。 */
    @Test
    void adminListSentencesHidesRecycledByDefault() {
        when(adminSentenceQueryDao.search(
                        isNull(), isNull(), isNull(), isNull(), eq(true), eq(0), eq(20)))
                .thenReturn(List.of());
        when(adminSentenceQueryDao.count(isNull(), isNull(), isNull(), isNull(), eq(true)))
                .thenReturn(0L);

        service.adminListSentences(null, null, null, null, 1, 20);

        verify(adminSentenceQueryDao)
                .search(isNull(), isNull(), isNull(), isNull(), eq(true), eq(0), eq(20));
    }

    /** 跨题库列表显式 status（含 RECYCLED）：按精确状态过滤、不再隐藏。 */
    @Test
    void adminListSentencesWithExplicitStatusDisablesHiding() {
        when(adminSentenceQueryDao.search(
                        isNull(), isNull(), isNull(), eq("RECYCLED"), eq(false), eq(0), eq(20)))
                .thenReturn(List.of());
        when(adminSentenceQueryDao.count(isNull(), isNull(), isNull(), eq("RECYCLED"), eq(false)))
                .thenReturn(0L);

        service.adminListSentences(null, null, null, "RECYCLED", 1, 20);

        verify(adminSentenceQueryDao)
                .search(isNull(), isNull(), isNull(), eq("RECYCLED"), eq(false), eq(0), eq(20));
    }

    /** 跨题库列表校验：bankId 非数字 / status 非法 / 分页越界 → 40000/40004。 */
    @Test
    void adminListSentencesRejectsBadBankIdStatusAndPaging() {
        assertThatThrownBy(() -> service.adminListSentences(null, "abc", null, null, 1, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.adminListSentences(null, null, null, "NOPE", 1, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.adminListSentences(null, null, null, null, 0, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.INVALID_PAGINATION));
        verifyNoInteractionsOnDaos();
    }

    /** 批量状态：每题递增银行修订号（同批各拿独立 version，增量游标不重版本分组），题库推进两版。 */
    @Test
    void adminBulkChangeStatusBumpsBankVersionPerSentence() {
        SentenceBank bank = bank(5L, ContentStatus.PUBLISHED, 0);
        Sentence first = sentence("01A", 5L, ContentStatus.PUBLISHED);
        Sentence second = sentence("01B", 5L, ContentStatus.PUBLISHED);
        when(sentenceDao.findByIds(List.of("01A", "01B"))).thenReturn(List.of(first, second));
        when(bankDao.lockById(5L)).thenReturn(Optional.of(bank));

        int updated = service.adminBulkChangeStatus(9L, List.of("01A", "01B"), "DISABLED", "批量停用");

        assertThat(updated).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(ContentStatus.DISABLED);
        assertThat(first.getVersion()).isEqualTo(1);
        assertThat(second.getStatus()).isEqualTo(ContentStatus.DISABLED);
        assertThat(second.getVersion()).isEqualTo(2);
        assertThat(bank.getVersion()).isEqualTo(2);
        verify(sentenceDao, times(2)).save(any(Sentence.class));
        verify(bankDao).save(bank);
    }

    /** 批量状态：不存在的 id 跳过（计数如实暴露），其余照常处理。 */
    @Test
    void adminBulkChangeStatusSkipsMissingIdsAndExposesCount() {
        SentenceBank bank = bank(5L, ContentStatus.PUBLISHED, 0);
        Sentence first = sentence("01A", 5L, ContentStatus.PUBLISHED);
        when(sentenceDao.findByIds(List.of("01A", "01GONE"))).thenReturn(List.of(first));
        when(bankDao.lockById(5L)).thenReturn(Optional.of(bank));

        int updated =
                service.adminBulkChangeStatus(9L, List.of("01A", "01GONE"), "DISABLED", "批量停用");

        assertThat(updated).isEqualTo(1);
        assertThat(first.getVersion()).isEqualTo(1);
        assertThat(bank.getVersion()).isEqualTo(1);
    }

    /** 批量状态：已处于目标状态的 id 跳过且不推进修订号（空操作不制造增量流量）。 */
    @Test
    void adminBulkChangeStatusSkipsSentencesAlreadyInTargetStatus() {
        Sentence first = sentence("01A", 5L, ContentStatus.DISABLED);
        when(sentenceDao.findByIds(List.of("01A"))).thenReturn(List.of(first));

        int updated = service.adminBulkChangeStatus(9L, List.of("01A"), "DISABLED", "重复操作");

        assertThat(updated).isZero();
        verify(bankDao, never()).lockById(5L);
        verify(sentenceDao, never()).save(any());
    }

    /** 批量状态：跨题库时悲观锁按题库 id 升序获取，避免并发批量 ABBA 死锁。 */
    @Test
    void adminBulkChangeStatusLocksBanksInAscendingOrder() {
        SentenceBank bank5 = bank(5L, ContentStatus.PUBLISHED, 0);
        SentenceBank bank7 = bank(7L, ContentStatus.PUBLISHED, 0);
        Sentence inBank7 = sentence("01B", 7L, ContentStatus.PUBLISHED);
        Sentence inBank5 = sentence("01A", 5L, ContentStatus.PUBLISHED);
        when(sentenceDao.findByIds(List.of("01A", "01B"))).thenReturn(List.of(inBank5, inBank7));
        when(bankDao.lockById(5L)).thenReturn(Optional.of(bank5));
        when(bankDao.lockById(7L)).thenReturn(Optional.of(bank7));

        service.adminBulkChangeStatus(9L, List.of("01A", "01B"), "DISABLED", "批量停用");

        InOrder inOrder = inOrder(bankDao);
        inOrder.verify(bankDao).lockById(5L);
        inOrder.verify(bankDao).lockById(7L);
    }

    /** 批量状态校验：空 ids / 空白 reason / 非法 status / 超 500 条 → 40000。 */
    @Test
    void adminBulkChangeStatusRejectsInvalidInput() {
        assertThatThrownBy(() -> service.adminBulkChangeStatus(9L, null, "DISABLED", "原因"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.adminBulkChangeStatus(9L, List.of("  "), "DISABLED", "原因"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.adminBulkChangeStatus(9L, List.of("01A"), "DISABLED", " "))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.adminBulkChangeStatus(9L, List.of("01A"), "NOPE", "原因"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(
                        () -> service.adminBulkChangeStatus(9L, uniqueIds(501), "DISABLED", "原因"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        verifyNoInteractionsOnDaos();
    }

    /** 批量移动：目标题库按每题递增推进并承载新版本，源题库推进一次。 */
    @Test
    void adminBulkMoveAssignsTargetVersionsAndBumpsSourceBankOnce() {
        SentenceBank source = bank(5L, ContentStatus.PUBLISHED, 3);
        SentenceBank target = bank(7L, ContentStatus.PUBLISHED, 10);
        Sentence movedSentence = sentence("01A", 5L, ContentStatus.PUBLISHED);
        when(sentenceDao.findByIds(List.of("01A"))).thenReturn(List.of(movedSentence));
        when(bankDao.findById(7L)).thenReturn(Optional.of(target));
        when(bankDao.lockById(5L)).thenReturn(Optional.of(source));
        when(bankDao.lockById(7L)).thenReturn(Optional.of(target));
        when(sentenceDao.findActiveFingerprints(eq(7L), anyCollection())).thenReturn(List.of());

        int moved = service.adminBulkMove(9L, List.of("01A"), "7", "归类");

        assertThat(moved).isEqualTo(1);
        assertThat(movedSentence.getBankId()).isEqualTo(7L);
        assertThat(movedSentence.getVersion()).isEqualTo(11L);
        assertThat(target.getVersion()).isEqualTo(11L);
        assertThat(source.getVersion()).isEqualTo(4L);
        verify(sentenceDao).save(movedSentence);
    }

    /** 批量移动：目标题库不存在或已进回收站 → 40401，不搬运。 */
    @Test
    void adminBulkMoveRejectsMissingOrRecycledTargetBank() {
        when(bankDao.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.adminBulkMove(9L, List.of("01A"), "9", "归类"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode()).isEqualTo(ApiCode.BANK_NOT_FOUND));

        SentenceBank recycled = bank(9L, ContentStatus.RECYCLED, 0);
        when(bankDao.findById(9L)).thenReturn(Optional.of(recycled));
        assertThatThrownBy(() -> service.adminBulkMove(9L, List.of("01A"), "9", "归类"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode()).isEqualTo(ApiCode.BANK_NOT_FOUND));
        verify(sentenceDao, never()).findByIds(any());
    }

    /** 批量移动：已在目标题库与不存在的 id 跳过，全跳过时不加锁不写库。 */
    @Test
    void adminBulkMoveSkipsSameBankAndMissingIds() {
        Sentence inTarget = sentence("01A", 7L, ContentStatus.PUBLISHED);
        when(sentenceDao.findByIds(List.of("01A", "01GONE"))).thenReturn(List.of(inTarget));
        when(bankDao.findById(7L)).thenReturn(Optional.of(bank(7L, ContentStatus.PUBLISHED, 10)));

        int moved = service.adminBulkMove(9L, List.of("01A", "01GONE"), "7", "归类");

        assertThat(moved).isZero();
        verify(bankDao, never()).lockById(anyLong());
        verify(sentenceDao, never()).save(any());
        verify(bankDao, never()).save(any());
    }

    /** 批量移动：目标题库已有相同内容 → 40000 整批拒绝（与导入指纹约束同源），单事务可整体回滚。 */
    @Test
    void adminBulkMoveRejectsDuplicateFingerprintInTargetBank() {
        SentenceBank target = bank(7L, ContentStatus.PUBLISHED, 10);
        Sentence movedSentence = sentence("01A", 5L, ContentStatus.PUBLISHED);
        when(sentenceDao.findByIds(List.of("01A"))).thenReturn(List.of(movedSentence));
        when(bankDao.findById(7L)).thenReturn(Optional.of(target));
        when(sentenceDao.findActiveFingerprints(eq(7L), anyCollection()))
                .thenReturn(List.of("fp-01A"));

        assertThatThrownBy(() -> service.adminBulkMove(9L, List.of("01A"), "7", "归类"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        verify(bankDao, never()).save(any());
        verify(sentenceDao, never()).save(any());
    }

    /** 批量移动校验：bankId 非数字 / 空白原因 → 40000。 */
    @Test
    void adminBulkMoveRejectsBadBankIdAndBlankReason() {
        assertThatThrownBy(() -> service.adminBulkMove(9L, List.of("01A"), "abc", "归类"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.adminBulkMove(9L, List.of("01A"), "7", " "))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        verifyNoInteractionsOnDaos();
    }

    /** 单题状态流转：回收站恢复清软删标记（恢复语义），并按「每题递增」推进所属题库修订号。 */
    @Test
    void adminChangeSentenceStatusRestoresFromRecycleBinAndClearsDeletedAt() {
        SentenceBank bank = bank(5L, ContentStatus.PUBLISHED, 3);
        Sentence recycled = sentence("01A", 5L, ContentStatus.RECYCLED);
        when(sentenceDao.findById("01A")).thenReturn(Optional.of(recycled));
        when(bankDao.lockById(5L)).thenReturn(Optional.of(bank));
        when(sentenceDao.findVersionsByIds(List.of(11L))).thenReturn(List.of(currentVersion()));

        SentenceDetail view = service.adminChangeSentenceStatus(9L, "01A", "PUBLISHED", "恢复");

        assertThat(recycled.getStatus()).isEqualTo(ContentStatus.PUBLISHED);
        assertThat(recycled.getDeletedAt()).isNull();
        assertThat(recycled.getVersion()).isEqualTo(4L);
        assertThat(bank.getVersion()).isEqualTo(4L);
        assertThat(view.deleted()).isFalse();
        assertThat(view.status()).isEqualTo("PUBLISHED");
        assertThat(view.zh()).isEqualTo("他越来越胖。");
        verify(sentenceDao).save(recycled);
        verify(bankDao).save(bank);
    }

    /** 单题状态流转幂等：已处于目标状态不推进修订号，直接回当前视图。 */
    @Test
    void adminChangeSentenceStatusSkipsNoOpWhenAlreadyInTargetStatus() {
        Sentence disabled = sentence("01A", 5L, ContentStatus.DISABLED);
        when(sentenceDao.findById("01A")).thenReturn(Optional.of(disabled));
        when(sentenceDao.findVersionsByIds(List.of(11L))).thenReturn(List.of(currentVersion()));

        SentenceDetail view = service.adminChangeSentenceStatus(9L, "01A", "DISABLED", "重复操作");

        assertThat(view.status()).isEqualTo("DISABLED");
        verify(bankDao, never()).lockById(anyLong());
        verify(sentenceDao, never()).save(any());
    }

    /** 单题状态流转校验：空白原因 / 题目缺失（40402）/ 非法状态 → 业务失败收敛。 */
    @Test
    void adminChangeSentenceStatusRejectsBlankReasonMissingSentenceAndBadStatus() {
        assertThatThrownBy(() -> service.adminChangeSentenceStatus(9L, "01A", "PUBLISHED", " "))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        when(sentenceDao.findById("01A")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.adminChangeSentenceStatus(9L, "01A", "PUBLISHED", "原因"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.SENTENCE_NOT_FOUND));
        assertThatThrownBy(() -> service.adminChangeSentenceStatus(9L, "01A", "NOPE", "原因"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        verify(sentenceDao, never()).save(any());
        verify(bankDao, never()).save(any());
    }

    /**
     * 行内编辑版本字段：zh/level/tags/note 产生新版本行（version_no+1、保留 ref/chunks/blank 与来源溯源列）， content_hash
     * 与导入同式、指纹与导入共用 canonical 算法；题库按「每题递增」推进。
     */
    @Test
    void adminUpdateSentenceDetailCreatesNewVersionForVersionFields() {
        SentenceBank bank = bank(5L, ContentStatus.PUBLISHED, 7);
        Sentence sentence = sentenceWithVersion("01A", 5L, ContentStatus.PUBLISHED, 7);
        List<SentenceVersion> versions = new ArrayList<>(List.of(currentVersion()));
        when(sentenceDao.findById("01A")).thenReturn(Optional.of(sentence));
        when(sentenceDao.findVersionsByIds(anyCollection()))
                .thenAnswer(
                        invocation -> {
                            Collection<Long> ids = invocation.getArgument(0);
                            return versions.stream()
                                    .filter(version -> ids.contains(version.getId()))
                                    .toList();
                        });
        when(sentenceDao.findLatestVersion("01A")).thenReturn(Optional.of(currentVersion()));
        when(bankDao.lockById(5L)).thenReturn(Optional.of(bank));
        when(sentenceDao.saveVersion(any(SentenceVersion.class)))
                .thenAnswer(
                        invocation -> {
                            SentenceVersion version = invocation.getArgument(0);
                            ReflectionTestUtils.setField(version, "id", 12L);
                            versions.add(version);
                            return version;
                        });
        when(contentImportService.fingerprintsOf(any())).thenReturn(List.of("fp-new"));

        SentenceDetail view =
                service.adminUpdateSentenceDetail(
                        9L,
                        "01A",
                        "  他越来越胖了呀。  ",
                        "  ",
                        List.of(" 搭配 ", "", "新考点"),
                        " 新解析。 ",
                        null,
                        null,
                        "编辑题目");

        ArgumentCaptor<SentenceVersion> versionCaptor =
                ArgumentCaptor.forClass(SentenceVersion.class);
        verify(sentenceDao).saveVersion(versionCaptor.capture());
        SentenceVersion saved = versionCaptor.getValue();
        assertThat(saved.getSentenceId()).isEqualTo("01A");
        assertThat(saved.getBankId()).isEqualTo(5L);
        assertThat(saved.getVersionNo()).isEqualTo(3);
        assertThat(saved.getZh()).isEqualTo("他越来越胖了呀。");
        assertThat(saved.getRef()).isEqualTo("He is getting fatter and fatter.");
        assertThat(saved.getNote()).isEqualTo("新解析。");
        assertThat(saved.getChunks()).isEqualTo(CHUNKS_JSON);
        assertThat(saved.getBlank()).isEqualTo(BLANK_JSON);
        assertThat(saved.getTags()).isEqualTo("[\"搭配\",\"新考点\"]");
        assertThat(saved.getLevel()).isNull();
        assertThat(saved.getSource()).isEqualTo("import");
        assertThat(saved.getLicense()).isEqualTo("CC-BY");
        assertThat(saved.getImportBatchId()).isEqualTo("batch-1");
        assertThat(saved.getContentHash())
                .isEqualTo(
                        TextNormalizer.sha256(
                                "他越来越胖了呀。|He is getting fatter and fatter.|新解析。|"
                                        + CHUNKS_JSON
                                        + "|"
                                        + BLANK_JSON
                                        + "|[\"搭配\",\"新考点\"]"));
        verify(contentImportService)
                .fingerprintsOf(
                        argThat(
                                rows -> {
                                    SentenceContent content = rows.get(0);
                                    return content.zh().equals("他越来越胖了呀。")
                                            && content.ref()
                                                    .equals("He is getting fatter and fatter.")
                                            && content.note().equals("新解析。")
                                            && content.chunks().size() == 2
                                            && content.tags().equals(List.of("搭配", "新考点"));
                                }));
        assertThat(sentence.getCurrentVersionId()).isEqualTo(12L);
        assertThat(sentence.getVersion()).isEqualTo(8L);
        assertThat(sentence.getFingerprint()).isEqualTo("fp-new");
        assertThat(bank.getVersion()).isEqualTo(8L);
        assertThat(view.questionVersion()).isEqualTo(3);
        assertThat(view.sentenceVersionId()).isEqualTo("12");
        assertThat(view.zh()).isEqualTo("他越来越胖了呀。");
    }

    /** 行内编辑只带元字段（bankId/status）时不产生新版本行：状态流转推进源库一次，移动按目标库每题递增、 源库再推进一次。 */
    @Test
    void adminUpdateSentenceDetailAppliesMetaOnlyChangesWithoutNewVersion() {
        SentenceBank sourceBank = bank(5L, ContentStatus.PUBLISHED, 7);
        SentenceBank targetBank = bank(7L, ContentStatus.PUBLISHED, 10);
        Sentence sentence = sentenceWithVersion("01A", 5L, ContentStatus.PUBLISHED, 7);
        when(sentenceDao.findById("01A")).thenReturn(Optional.of(sentence));
        when(sentenceDao.findVersionsByIds(List.of(11L))).thenReturn(List.of(currentVersion()));
        when(bankDao.findById(7L)).thenReturn(Optional.of(targetBank));
        when(bankDao.lockById(5L)).thenReturn(Optional.of(sourceBank));
        when(bankDao.lockById(7L)).thenReturn(Optional.of(targetBank));
        when(sentenceDao.findActiveFingerprints(eq(7L), anyCollection())).thenReturn(List.of());

        SentenceDetail view =
                service.adminUpdateSentenceDetail(
                        9L, "01A", null, null, null, null, "7", "DISABLED", "整理");

        verify(sentenceDao, never()).saveVersion(any());
        verify(contentImportService, never()).fingerprintsOf(any());
        assertThat(sentence.getStatus()).isEqualTo(ContentStatus.DISABLED);
        assertThat(sentence.getBankId()).isEqualTo(7L);
        assertThat(sentence.getVersion()).isEqualTo(11L);
        assertThat(sourceBank.getVersion()).isEqualTo(9L);
        assertThat(targetBank.getVersion()).isEqualTo(11L);
        assertThat(view.bankId()).isEqualTo("7");
        assertThat(view.status()).isEqualTo("DISABLED");
    }

    /** 行内编辑先改内容后移动：移动的指纹预检用的是回填后的新指纹。 */
    @Test
    void adminUpdateSentenceDetailChecksNewFingerprintWhenMovingAfterContentEdit() {
        SentenceBank sourceBank = bank(5L, ContentStatus.PUBLISHED, 7);
        SentenceBank targetBank = bank(7L, ContentStatus.PUBLISHED, 10);
        Sentence sentence = sentenceWithVersion("01A", 5L, ContentStatus.PUBLISHED, 7);
        List<SentenceVersion> versions = new ArrayList<>(List.of(currentVersion()));
        when(sentenceDao.findById("01A")).thenReturn(Optional.of(sentence));
        when(sentenceDao.findVersionsByIds(anyCollection()))
                .thenAnswer(
                        invocation -> {
                            Collection<Long> ids = invocation.getArgument(0);
                            return versions.stream()
                                    .filter(version -> ids.contains(version.getId()))
                                    .toList();
                        });
        when(sentenceDao.findLatestVersion("01A")).thenReturn(Optional.of(currentVersion()));
        when(sentenceDao.saveVersion(any(SentenceVersion.class)))
                .thenAnswer(
                        invocation -> {
                            SentenceVersion version = invocation.getArgument(0);
                            ReflectionTestUtils.setField(version, "id", 12L);
                            versions.add(version);
                            return version;
                        });
        when(contentImportService.fingerprintsOf(any())).thenReturn(List.of("fp-new"));
        when(bankDao.findById(7L)).thenReturn(Optional.of(targetBank));
        when(bankDao.lockById(5L)).thenReturn(Optional.of(sourceBank));
        when(bankDao.lockById(7L)).thenReturn(Optional.of(targetBank));
        when(sentenceDao.findActiveFingerprints(eq(7L), anyCollection())).thenReturn(List.of());

        service.adminUpdateSentenceDetail(
                9L, "01A", "  他越来越胖了呀。  ", null, null, null, "7", null, "编辑题目");

        verify(sentenceDao)
                .findActiveFingerprints(
                        eq(7L), argThat(asked -> asked.size() == 1 && asked.contains("fp-new")));
        assertThat(sentence.getBankId()).isEqualTo(7L);
        assertThat(sentence.getFingerprint()).isEqualTo("fp-new");
    }

    /** 行内编辑移动：目标库已有相同内容（按指纹）→ 40000 整体不落库。 */
    @Test
    void adminUpdateSentenceDetailRejectsDuplicateFingerprintInTargetBank() {
        SentenceBank targetBank = bank(7L, ContentStatus.PUBLISHED, 10);
        Sentence sentence = sentenceWithVersion("01A", 5L, ContentStatus.PUBLISHED, 7);
        when(sentenceDao.findById("01A")).thenReturn(Optional.of(sentence));
        when(sentenceDao.findVersionsByIds(List.of(11L))).thenReturn(List.of(currentVersion()));
        when(bankDao.findById(7L)).thenReturn(Optional.of(targetBank));
        when(sentenceDao.findActiveFingerprints(eq(7L), anyCollection()))
                .thenReturn(List.of("fp-01A"));

        assertThatThrownBy(
                        () ->
                                service.adminUpdateSentenceDetail(
                                        9L, "01A", null, null, null, null, "7", null, "整理"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        verify(sentenceDao, never()).save(any());
        verify(bankDao, never()).save(any());
        verify(sentenceDao, never()).saveVersion(any());
    }

    /** 行内编辑校验：中文太短 / 非法状态 / 题目缺失（40402）。 */
    @Test
    void adminUpdateSentenceDetailRejectsShortZhBadStatusAndMissingSentence() {
        assertThatThrownBy(
                        () ->
                                service.adminUpdateSentenceDetail(
                                        9L, "01A", "  中文  ", null, null, null, null, null, "编辑题目"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(
                        () ->
                                service.adminUpdateSentenceDetail(
                                        9L, "01A", null, null, null, null, null, "NOPE", null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        when(sentenceDao.findById("01A")).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                service.adminUpdateSentenceDetail(
                                        9L, "01A", "他越来越胖了呀。", null, null, null, null, null, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.SENTENCE_NOT_FOUND));
        verify(sentenceDao, never()).saveVersion(any());
    }

    /** 行内编辑空体与同状态 status：无操作回当前视图，不推进修订号、不写版本行。 */
    @Test
    void adminUpdateSentenceDetailNoOpReturnsCurrentView() {
        Sentence sentence = sentenceWithVersion("01A", 5L, ContentStatus.PUBLISHED, 7);
        when(sentenceDao.findById("01A")).thenReturn(Optional.of(sentence));
        when(sentenceDao.findVersionsByIds(List.of(11L))).thenReturn(List.of(currentVersion()));

        SentenceDetail view =
                service.adminUpdateSentenceDetail(
                        9L, "01A", null, null, null, null, null, null, null);

        assertThat(view.zh()).isEqualTo("他越来越胖。");
        verify(sentenceDao, never()).saveVersion(any());
        verify(sentenceDao, never()).save(any());
        // 前端编辑弹窗恒带当前 status：同状态属空操作，不推进修订号。
        SentenceDetail unchanged =
                service.adminUpdateSentenceDetail(
                        9L, "01A", null, null, null, null, null, "PUBLISHED", "编辑题目");
        assertThat(unchanged.status()).isEqualTo("PUBLISHED");
        verify(bankDao, never()).lockById(anyLong());
    }

    /**
     * 构造 N 条去重题目 id。
     *
     * @param count 条数
     * @return id 列表
     */
    private static List<String> uniqueIds(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(String.format("01J%04d", i));
        }
        return ids;
    }

    /**
     * 构造题库实体。
     *
     * @param id 主键
     * @param status 生命周期（DRAFT 之外的值会走一次 changeStatus 置位）
     * @param version 修订号
     * @return 题库实体
     */
    private static SentenceBank bank(long id, ContentStatus status, long version) {
        SentenceBank created =
                SentenceBank.create(null, "官方题库", "说明", BankVisibility.PUBLIC, 9L, NOW);
        ReflectionTestUtils.setField(created, "id", id);
        if (status != ContentStatus.DRAFT) {
            created.changeStatus(status, 9L, NOW);
        }
        ReflectionTestUtils.setField(created, "version", version);
        return created;
    }

    /**
     * 构造题目实体。
     *
     * @param id ULID
     * @param bankId 所属题库
     * @param status 生命周期（DRAFT 之外的值会走一次 changeStatus 置位）
     * @return 题目实体
     */
    private static Sentence sentence(String id, long bankId, ContentStatus status) {
        Sentence created = Sentence.create(id, bankId, null, "fp-" + id, 9L, NOW);
        ReflectionTestUtils.setField(created, "currentVersionId", 11L);
        if (status != ContentStatus.DRAFT) {
            created.changeStatus(status, 0L, 9L, NOW);
        }
        return created;
    }

    /**
     * 构造带初始修订号的题目实体（行内编辑用例需要非 0 起点验证递增）。
     *
     * @param id ULID
     * @param bankId 所属题库
     * @param status 生命周期
     * @param version 初始修订号
     * @return 题目实体
     */
    private static Sentence sentenceWithVersion(
            String id, long bankId, ContentStatus status, long version) {
        Sentence created = sentence(id, bankId, status);
        ReflectionTestUtils.setField(created, "version", version);
        return created;
    }

    /**
     * 构造当前内容快照（version_no=2、id=11），与题目实体的 currentVersionId=11 对齐。
     *
     * @return 快照实体
     */
    private static SentenceVersion currentVersion() {
        SentenceVersion created =
                SentenceVersion.create(
                        "01A",
                        5L,
                        2,
                        "他越来越胖。",
                        "He is getting fatter and fatter.",
                        "主语 + 现在进行时。",
                        CHUNKS_JSON,
                        BLANK_JSON,
                        TAGS_JSON,
                        "CET-4",
                        "import",
                        "CC-BY",
                        "hash-old",
                        "batch-1",
                        9L,
                        NOW);
        ReflectionTestUtils.setField(created, "id", 11L);
        return created;
    }

    /**
     * 构造管理端题目查询行。
     *
     * @param deletedAt 软删时间，null 表示未删
     * @return 查询行
     */
    private static AdminSentenceRow row(Instant deletedAt) {
        return new AdminSentenceRow(
                "01A",
                5L,
                7L,
                11L,
                2,
                "PUBLISHED",
                deletedAt,
                "他越来越胖。",
                "He is getting fatter and fatter.",
                "主语 + 现在进行时。",
                CHUNKS_JSON,
                BLANK_JSON,
                TAGS_JSON,
                "CET-4");
    }

    /** 断言三个 Dao 完全未被触达（参数校验失败的用例收尾）。 */
    private void verifyNoInteractionsOnDaos() {
        verify(bankDao, never()).findById(anyLong());
        verify(bankDao, never()).save(any());
        verify(sentenceDao, never()).findByIds(any());
        verify(sentenceDao, never()).save(any());
        verify(adminSentenceQueryDao, never())
                .search(any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
        verify(adminSentenceQueryDao, never()).count(any(), any(), any(), any(), anyBoolean());
    }
}
