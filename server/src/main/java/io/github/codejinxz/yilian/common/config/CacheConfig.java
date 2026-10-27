package io.github.codejinxz.yilian.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 目前只启用 L1（Caffeine）缓存。L2（Redis）依赖已引入但尚未接入业务缓存或限流； refresh 会话由 Spring Session JDBC 保存，不使用
 * Redis。真正需要跨实例热点缓存时再组合两级缓存， 避免多重缓存带来的一致性问题。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 进程内缓存管理器：容量与 TTL 都刻意取小，保证数据变更后能在 5 分钟内自愈， 且不会因缓存占用掩盖数据库问题。
     *
     * @return 全局唯一的缓存管理器
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(
                Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(Duration.ofMinutes(5)));
        return manager;
    }
}
