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
 * <p>穿透=无 ACTIVE 行缓存 {@link PromptRegistry.Absent} 负条目（短 TTL）； 击穿={@code @Cacheable(sync = true)}
 * 同键并发重建互斥； 雪崩=CacheConfig 的 ±10% TTL 抖动。L2 Redis 不接入：prompt 版本是低频读、进程内命中率即够，
 * 注册表读失败由调用方回退代码常量兜底（本方法对缺行返回 Absent 而不是抛异常， 主链路不阻塞）。
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
