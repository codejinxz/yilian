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
 *
 * <p>缓存名契约（013 AC-2）：只接受本类显式登记的缓存名清单，禁用运行期动态隐式建缓存—— 防止误用方法名建缓存、共享到不合适的统一 spec。命名 {@code
 * {module}:{topic}:{version}}， 版本段进缓存名：条目结构或键语义变更时改版本号（如 v1→v2）即整体失效，无需灰度迁移； 与 AGENTS §3.8 的 Redis
 * 键 {@code yilian:{env}:{module}:{key}:{version}} 的版本思想一致。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 题库详情缓存（匿名公开读）；末段 v1 为条目结构版本。 */
    public static final String CACHE_BANK_DETAIL = "content:bank:detail:v1";

    /** 题库列表缓存（匿名公开读）；末段 v1 为键结构版本（键 = 筛选参数指纹，见 BankMetadataCache#listCacheKey）。 */
    public static final String CACHE_BANK_LIST = "content:bank:list:v1";

    /** 元数据基准 TTL：写后失效负责主动清，TTL 只是自愈兜底（分析报告 §3 建议元数据 5–15 分钟）。 */
    private static final Duration BASE_TTL = Duration.ofMinutes(10);

    /** 负缓存（不存在/不可见）TTL：远短于正缓存，防穿透同时让「暂不可见」尽快收敛到真实状态。 */
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(60);

    /**
     * 进程内缓存管理器：容量与 TTL 都刻意取小，保证数据变更后能快速自愈， 且不会因缓存占用掩盖数据库问题。
     *
     * <p>TTL 带 ±10% 随机抖动（{@link JitteredTtlExpiry}）防同批条目同时过期的雪崩； 负缓存条目自动使用更短的 TTL
     * 防穿透。sync/穿透/雪崩三防护的落点见 BankMetadataCache。
     *
     * @return 全局唯一的缓存管理器
     */
    @Bean
    public CacheManager cacheManager() {
        // 构造器传入显式清单后 CaffeineCacheManager 即关闭动态建缓存（dynamic=false）。
        CaffeineCacheManager manager = new CaffeineCacheManager(CACHE_BANK_DETAIL, CACHE_BANK_LIST);
        manager.setCaffeine(
                Caffeine.newBuilder()
                        .maximumSize(1_000)
                        .expireAfter(new JitteredTtlExpiry(BASE_TTL, NEGATIVE_TTL)));
        return manager;
    }
}
