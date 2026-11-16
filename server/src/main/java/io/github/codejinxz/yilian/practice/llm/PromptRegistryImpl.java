package io.github.codejinxz.yilian.practice.llm;

import io.github.codejinxz.yilian.common.config.CacheConfig;
import io.github.codejinxz.yilian.practice.dao.PromptVersionDao;
import io.github.codejinxz.yilian.practice.entity.PromptVersion;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Prompt 注册表实现：L1 Caffeine 缓存装配（BankMetadataCache 同款三防护）。
 *
 * <p>穿透=无 ACTIVE 行缓存 {@link PromptRegistry.Absent} 负条目； 击穿={@code @Cacheable(sync = true)}
 * 同键并发重建互斥； 雪崩=CacheConfig 的 ±10% TTL 抖动。如实说明（029 C-01 注释部分修正）：负条目与正条目共用 CacheConfig 同一 10 分钟
 * TTL，没有独立「短 TTL」——手工补插 ACTIVE 行最长延迟 10 分钟生效（或调 {@link #evictAll()} 立即失效）。L2 Redis 不接入：prompt
 * 版本是低频读、进程内命中率即够， 注册表读失败由调用方回退代码常量兜底（本方法对缺行返回 Absent 而不是抛异常， 主链路不阻塞）。
 */
@Service
public class PromptRegistryImpl implements PromptRegistry {

    private final PromptVersionDao promptVersionDao;

    private final CacheManager cacheManager;

    /**
     * @param promptVersionDao Prompt 版本 Dao
     * @param cacheManager 缓存管理器（CacheConfig 显式清单）
     */
    public PromptRegistryImpl(PromptVersionDao promptVersionDao, CacheManager cacheManager) {
        this.promptVersionDao = promptVersionDao;
        this.cacheManager = cacheManager;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_PROMPT_VERSION, key = "#name", sync = true)
    public Entry load(String name) {
        return promptVersionDao
                .findActiveByName(name)
                .map(PromptRegistryImpl::toEntry)
                .orElseGet(Absent::new);
    }

    /**
     * 清空 prompt 缓存（写后失效入口）。预留 API：当前 prompt_versions 无应用内写路径（版本流转走手工 SQL，见 GAP
     * 登记），管理端写路径落地后由其调用；现在调用无害，缺行由调用方常量兜底。
     */
    @Override
    public void evictAll() {
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_PROMPT_VERSION);
        if (cache != null) {
            cache.clear();
        }
    }

    /**
     * 实体 → 正缓存条目。
     *
     * @param version ACTIVE 版本行
     * @return 条目
     */
    private static Entry toEntry(PromptVersion version) {
        return new Present(version.getId(), version.getContent());
    }
}
