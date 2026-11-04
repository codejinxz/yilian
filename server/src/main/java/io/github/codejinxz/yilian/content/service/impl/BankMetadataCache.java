package io.github.codejinxz.yilian.content.service.impl;

import io.github.codejinxz.yilian.common.config.CacheConfig;
import io.github.codejinxz.yilian.common.config.NegativeCacheEntry;
import io.github.codejinxz.yilian.content.dao.BankDao;
import io.github.codejinxz.yilian.content.entity.BankVisibility;
import io.github.codejinxz.yilian.content.entity.ContentStatus;
import io.github.codejinxz.yilian.content.entity.SentenceBank;
import io.github.codejinxz.yilian.content.service.vo.BankView;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 题库元数据的 L1 Caffeine 缓存装配。
 *
 * <p>只缓存「匿名公开读」这一条高频且结果与调用者无关的路径：登录用户的结果集含其私有题库 （键需带 userId，命中率趋零），管理端（{@code
 * adminListBanks}/{@code adminGetBank}）低频且 要求实时——两者都不走缓存，维持直查库。写路径（题库 CRUD/状态/删除、句子导入与增删改） 一律经
 * {@link #evictWrittenBanks} 在事务提交后失效缓存（先落库再删）。
 *
 * <p>三防护落点：穿透=不存在/不可见缓存 {@link Absent} 负条目（短 TTL，见 {@link JitteredTtlExpiry}）；
 * 击穿={@code @Cacheable(sync = true)} 同键并发重建互斥（Spring 约束 sync 与 unless 互斥， 故本类不用 unless，负结果靠显式
 * {@link Absent} 条目表达）；雪崩=CacheConfig 的 ±10% TTL 抖动。
 */
@Service
public class BankMetadataCache {

    private final BankDao bankDao;

    private final CacheManager cacheManager;

    /**
     * 匿名公开题库缓存条目：正缓存 {@link Present} / 负缓存 {@link Absent}。
     *
     * <p>用「包装哨兵」而不是缓存 null：Caffeine 原生不允许 null 值，且缓存 null 会把 「方法未执行」与「结果为空」混在一起，语义不清。
     */
    public sealed interface Entry permits Present, Absent {}

    /** 正缓存：对匿名可见题库的视图。 */
    public record Present(BankView view) implements Entry {}

    /** 负缓存：题库不存在或对匿名不可见；实现 {@link NegativeCacheEntry} 以获得短 TTL， 命中后由调用方转 40401。 */
    public record Absent() implements Entry, NegativeCacheEntry {}

    /**
     * 注入题库 Dao 与缓存管理器。
     *
     * @param bankDao 题库 Dao
     * @param cacheManager 缓存管理器（CacheConfig 显式清单）
     */
    public BankMetadataCache(BankDao bankDao, CacheManager cacheManager) {
        this.bankDao = bankDao;
        this.cacheManager = cacheManager;
    }

    /**
     * 匿名视角的题库详情（缓存方法）。
     *
     * <p>键 = bankId；{@code sync = true} 让同一 key 的并发未命中只有一个线程重建， 其余线程等待结果（防击穿）。匿名不可见的分支与 {@code
     * ContentQueryServiceImpl#requireVisibleBank} 的匿名口径一致：私有、未发布、已软删、不存在统统算「不可见」，避免泄露存在性。
     *
     * @param bankId 题库 id
     * @return 正/负缓存条目
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_BANK_DETAIL, key = "#bankId", sync = true)
    public Entry loadPublicBankView(long bankId) {
        Optional<SentenceBank> bank = bankDao.findById(bankId);
        if (bank.isEmpty() || !isAnonymousVisible(bank.get())) {
            return new Absent();
        }
        return new Present(toView(bank.get()));
    }

    /**
     * 列表缓存的键：筛选参数的规范化指纹。
     *
     * <p>归一规则与查询语义对齐：库排序规则 utf8mb4_0900_ai_ci 大小写不敏感，「X」与「x」 是同一结果集，必须同键否则缓存碎裂；首尾空白与 {@code
     * ContentQueryServiceImpl} 的 normalizeKeyword 一致（串内空白保留，LIKE 语义如此）。
     *
     * @param keyword 关键字，可为 null
     * @param visibility 可见性过滤，可为 null
     * @param status 状态过滤，可为 null
     * @param page 页码
     * @param size 每页大小
     * @return 规范化键
     */
    public static String listCacheKey(
            String keyword, BankVisibility visibility, ContentStatus status, int page, int size) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return page
                + "|"
                + size
                + "|"
                + normalizedKeyword
                + "|"
                + (visibility == null ? "" : visibility.name())
                + "|"
                + (status == null ? "" : status.name());
    }

    /**
     * 写路径的缓存失效：先落库（调用方事务内），提交后删缓存。
     *
     * <p>题库详情按 bankId 逐键精准失效；列表键是参数指纹哈希、无法反查受影响的键， 任何影响题库元数据或句子计数的写都整体清空列表缓存（容量 ≤1000，代价可忽略）。
     * 事务提交后才执行删除：删早了、提交前有并发读会把旧值重新灌进缓存。无事务上下文时 （理论上不会发生，写路径都在 @Transactional 内）退化为立即删除。
     *
     * @param bankIds 受本次写影响的题库 id 集合
     */
    public void evictWrittenBanks(Collection<Long> bankIds) {
        if (bankIds == null || bankIds.isEmpty()) {
            return;
        }
        List<Long> ids = List.copyOf(bankIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            doEvict(ids);
                        }
                    });
        } else {
            doEvict(ids);
        }
    }

    /**
     * 实际删除：列表缓存整体清空，详情缓存逐键失效。
     *
     * @param bankIds 受影响题库 id
     */
    private void doEvict(Collection<Long> bankIds) {
        Cache detailCache = cacheManager.getCache(CacheConfig.CACHE_BANK_DETAIL);
        if (detailCache != null) {
            bankIds.forEach(detailCache::evict);
        }
        Cache listCache = cacheManager.getCache(CacheConfig.CACHE_BANK_LIST);
        if (listCache != null) {
            listCache.clear();
        }
    }

    /**
     * 判断题库对匿名是否可见：公开、已发布、未软删三者缺一不可。
     *
     * @param bank 题库实体
     * @return 对匿名可见时为 true
     */
    private static boolean isAnonymousVisible(SentenceBank bank) {
        return bank.getVisibility() == BankVisibility.PUBLIC
                && bank.getStatus() == ContentStatus.PUBLISHED
                && bank.getDeletedAt() == null;
    }

    /**
     * 构造匿名视角视图，题数按「已发布且未删」统计（与 ContentQueryServiceImpl#toBankView 同口径）。
     *
     * @param bank 题库实体
     * @return 题库视图
     */
    private BankView toView(SentenceBank bank) {
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
}
