package io.github.codejinxz.yilian.content.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.config.CacheConfig;
import io.github.codejinxz.yilian.content.dao.BankDao;
import io.github.codejinxz.yilian.content.dao.PickerSentenceQueryDao;
import io.github.codejinxz.yilian.content.dao.PracticeCandidateQueryDao;
import io.github.codejinxz.yilian.content.dao.SentenceDao;
import io.github.codejinxz.yilian.content.entity.BankVisibility;
import io.github.codejinxz.yilian.content.entity.ContentStatus;
import io.github.codejinxz.yilian.content.entity.SentenceBank;
import io.github.codejinxz.yilian.content.service.ContentQueryService;
import io.github.codejinxz.yilian.content.service.vo.BankView;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 题库元数据缓存的接线测试：真实 Spring 缓存代理 + Mock Dao。
 *
 * <p>用最小上下文（{@link CacheConfig} + 被测 Bean）证明缓存注解真的生效：命中不查库、
 * 写后失效、参数指纹归一、负缓存（防穿透）。纯单测直调实现类不经过代理，无法覆盖这些行为， 故本类是 {@code ContentQueryServiceImplTest} 的必要补充。
 */
// SpringJUnitConfig = @ExtendWith(SpringExtension) + @ContextConfiguration：
// 纯 @ContextConfiguration 不会启用 SpringExtension，字段注入不会发生。
@SpringJUnitConfig(classes = {CacheConfig.class, ContentBankCacheTest.TestConfig.class})
class ContentBankCacheTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    private static final long BANK_ID = 1L;

    @Autowired private BankDao bankDao;

    @Autowired private ContentQueryService contentQueryService;

    @Autowired private BankMetadataCache bankMetadataCache;

    @Autowired private CacheManager cacheManager;

    /** 最小装配：Mock Dao + 真实 CacheConfig（显式缓存名 + 抖动 TTL）。 */
    @Configuration
    static class TestConfig {

        @Bean
        BankDao bankDao() {
            return mock(BankDao.class);
        }

        @Bean
        SentenceDao sentenceDao() {
            return mock(SentenceDao.class);
        }

        @Bean
        PracticeCandidateQueryDao practiceCandidateQueryDao() {
            return mock(PracticeCandidateQueryDao.class);
        }

        @Bean
        PickerSentenceQueryDao pickerSentenceQueryDao() {
            return mock(PickerSentenceQueryDao.class);
        }

        @Bean
        BankMetadataCache bankMetadataCache(BankDao bankDao, CacheManager cacheManager) {
            return new BankMetadataCache(bankDao, cacheManager);
        }

        @Bean
        ContentQueryServiceImpl contentQueryService(
                BankDao bankDao,
                SentenceDao sentenceDao,
                PracticeCandidateQueryDao practiceCandidateQueryDao,
                PickerSentenceQueryDao pickerSentenceQueryDao,
                BankMetadataCache bankMetadataCache) {
            return new ContentQueryServiceImpl(
                    bankDao,
                    sentenceDao,
                    practiceCandidateQueryDao,
                    pickerSentenceQueryDao,
                    bankMetadataCache,
                    new ObjectMapper());
        }
    }

    @BeforeEach
    void resetMocks() {
        reset(bankDao);
        // Caffeine 缓存随应用上下文存活、跨测试不销毁：每个用例前清空，避免上个用例的
        // 正/负缓存条目污染本用例的命中断言。
        cacheManager.getCache(CacheConfig.CACHE_BANK_DETAIL).clear();
        cacheManager.getCache(CacheConfig.CACHE_BANK_LIST).clear();
    }

    /** [AC-2] 匿名列表第二次调用命中缓存，Dao 只查一次。 */
    @Test
    void listBanksSecondCallHitsCacheWithoutDao_ac2() {
        stubSearchVisibleEmpty();

        contentQueryService.listBanks(null, null, null, null, 1, 20);
        contentQueryService.listBanks(null, null, null, null, 1, 20);

        verify(bankDao, times(1)).searchVisible(any(), any(), any(), any(Pageable.class));
    }

    /** [AC-2] 关键字指纹归一：大小写与首尾空白不影响键（与库 utf8mb4_0900_ai_ci 不敏感语义对齐），Dao 只查一次。 */
    @Test
    void listBanksKeywordNormalizationSharesKey_ac2() {
        stubSearchVisibleEmpty();

        contentQueryService.listBanks(null, "  Prose ", null, null, 1, 20);
        contentQueryService.listBanks(null, "prose", null, null, 1, 20);

        verify(bankDao, times(1)).searchVisible(any(), any(), any(), any(Pageable.class));
    }

    /** [AC-2] 登录请求绕过缓存：结果含私有题库，两次调用查两次库。 */
    @Test
    void listBanksLoggedInBypassesCache_ac2() {
        stubSearchVisibleEmpty();

        contentQueryService.listBanks(7L, null, null, null, 1, 20);
        contentQueryService.listBanks(7L, null, null, null, 1, 20);

        verify(bankDao, times(2)).searchVisible(any(), any(), any(), any(Pageable.class));
    }

    /** [AC-2] 写后失效：事务提交后列表缓存清空，下一次读重新查库。 */
    @Test
    void listBanksEvictedAfterCommit_ac2() {
        stubSearchVisibleEmpty();
        contentQueryService.listBanks(null, null, null, null, 1, 20);

        bankMetadataCache.evictWrittenBanks(List.of(BANK_ID));

        contentQueryService.listBanks(null, null, null, null, 1, 20);
        verify(bankDao, times(2)).searchVisible(any(), any(), any(), any(Pageable.class));
    }

    /** [AC-2] 写后失效的「先落库再删」语义：事务未提交前缓存仍命中，afterCommit 才失效。 */
    @Test
    void detailEvictionWaitsForCommit_ac2() {
        stubPublicBank();
        contentQueryService.getBank(BANK_ID, null, false);

        TransactionSynchronizationManager.initSynchronization();
        try {
            bankMetadataCache.evictWrittenBanks(List.of(BANK_ID));
            // 提交前：详情缓存仍命中，不查库。
            contentQueryService.getBank(BANK_ID, null, false);
            verify(bankDao, times(1)).findById(BANK_ID);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            // 提交后：缓存已删，重新查库。
            contentQueryService.getBank(BANK_ID, null, false);
            verify(bankDao, times(2)).findById(BANK_ID);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** [AC-2] 匿名详情命中缓存：Dao（findById + countPublishedSentences）只查一次。 */
    @Test
    void getBankAnonymousSecondCallHitsCache_ac2() {
        stubPublicBank();

        BankView first = contentQueryService.getBank(BANK_ID, null, false);
        BankView second = contentQueryService.getBank(BANK_ID, null, false);

        assertThat(second).isEqualTo(first);
        assertThat(second.sentenceCount()).isEqualTo(48L);
        verify(bankDao, times(1)).findById(BANK_ID);
        verify(bankDao, times(1)).countPublishedSentences(BANK_ID);
    }

    /** [AC-2] 详情写后失效：提交后下一次读重新查库。 */
    @Test
    void getBankEvictedAfterImmediateEvict_ac2() {
        stubPublicBank();
        contentQueryService.getBank(BANK_ID, null, false);

        // 无事务上下文时退化为立即删除（写路径理论上都在事务内，这里覆盖兜底分支）。
        bankMetadataCache.evictWrittenBanks(List.of(BANK_ID));

        contentQueryService.getBank(BANK_ID, null, false);
        verify(bankDao, times(2)).findById(BANK_ID);
    }

    /** [AC-3] 负缓存防穿透：不存在的题库两次请求只查一次库，且都返回 40401。 */
    @Test
    void getBankMissingBankIsNegativeCached_ac3() {
        when(bankDao.findById(BANK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contentQueryService.getBank(BANK_ID, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.BANK_NOT_FOUND);
        assertThatThrownBy(() -> contentQueryService.getBank(BANK_ID, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.BANK_NOT_FOUND);

        verify(bankDao, times(1)).findById(BANK_ID);
    }

    /** [AC-3] 私有题库对匿名同样落负缓存：存在性不泄露，重复探测不穿透。 */
    @Test
    void getBankPrivateBankIsNegativeCached_ac3() {
        when(bankDao.findById(BANK_ID)).thenReturn(Optional.of(publishedPrivateBank(BANK_ID, 9L)));

        assertThatThrownBy(() -> contentQueryService.getBank(BANK_ID, null, false))
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.BANK_NOT_FOUND);
        assertThatThrownBy(() -> contentQueryService.getBank(BANK_ID, null, false))
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.BANK_NOT_FOUND);

        verify(bankDao, times(1)).findById(BANK_ID);
    }

    /** [AC-3] 负缓存条目以 NullValue 以外的哨兵（Absent 记录）存储并带短 TTL 语义：命中后返回 Absent 而不是空指针。 */
    @Test
    void negativeEntriesAreSentinelRecordsNotRawNulls_ac3() {
        when(bankDao.findById(BANK_ID)).thenReturn(Optional.empty());

        BankMetadataCache.Entry entry = bankMetadataCache.loadPublicBankView(BANK_ID);
        BankMetadataCache.Entry cached = bankMetadataCache.loadPublicBankView(BANK_ID);

        assertThat(entry).isInstanceOf(BankMetadataCache.Absent.class);
        assertThat(cached).isInstanceOf(BankMetadataCache.Absent.class);
        verify(bankDao, times(1)).findById(BANK_ID);
    }

    /** [AC-3] 击穿防护注解在位：详情与列表的 @Cacheable 均为 sync=true，且列表条件为匿名（userId==null）。 */
    @Test
    void cacheableAnnotationsUseSyncAndAnonymousCondition_ac3() throws Exception {
        var detail = BankMetadataCache.class.getMethod("loadPublicBankView", long.class);
        var list =
                ContentQueryServiceImpl.class.getMethod(
                        "listBanks",
                        Long.class,
                        String.class,
                        BankVisibility.class,
                        ContentStatus.class,
                        int.class,
                        int.class);

        Cacheable detailAnnotation = detail.getAnnotation(Cacheable.class);
        Cacheable listAnnotation = list.getAnnotation(Cacheable.class);
        assertThat(detailAnnotation).isNotNull();
        assertThat(listAnnotation).isNotNull();
        // Spring 约束 sync 与 unless 互斥：本实现用 sync 防击穿，故两处都不得使用 unless。
        assertThat(detailAnnotation.sync()).isTrue();
        assertThat(detailAnnotation.unless()).isEmpty();
        assertThat(listAnnotation.sync()).isTrue();
        assertThat(listAnnotation.unless()).isEmpty();
        assertThat(listAnnotation.condition()).isEqualTo("#userId == null");
        assertThat(listAnnotation.cacheNames()).containsExactly(CacheConfig.CACHE_BANK_LIST);
        assertThat(detailAnnotation.cacheNames()).containsExactly(CacheConfig.CACHE_BANK_DETAIL);
    }

    /** [AC-2] 缓存管理器只登记显式缓存名，无动态缓存。 */
    @Test
    void cacheManagerExposesOnlyExplicitNames_ac2() {
        var manager = (CaffeineCacheManager) new CacheConfig().cacheManager();

        assertThat(manager.getCacheNames())
                .containsExactlyInAnyOrder(
                        CacheConfig.CACHE_BANK_DETAIL,
                        CacheConfig.CACHE_BANK_LIST,
                        CacheConfig.CACHE_PROMPT_VERSION);
    }

    private void stubSearchVisibleEmpty() {
        when(bankDao.searchVisible(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
    }

    private void stubPublicBank() {
        SentenceBank bank =
                SentenceBank.create(1L, "官方题库", "专八翻译语块", BankVisibility.PUBLIC, 1L, NOW);
        bank.advanceVersion(12, 1L, NOW);
        ReflectionTestUtils.setField(bank, "id", BANK_ID);
        when(bankDao.findById(BANK_ID)).thenReturn(Optional.of(bank));
        when(bankDao.countPublishedSentences(BANK_ID)).thenReturn(48L);
    }

    private static SentenceBank publishedPrivateBank(long id, long ownerId) {
        SentenceBank bank =
                SentenceBank.create(ownerId, "我的题库", null, BankVisibility.PRIVATE, ownerId, NOW);
        bank.advanceVersion(1, ownerId, NOW);
        ReflectionTestUtils.setField(bank, "id", id);
        return bank;
    }
}
