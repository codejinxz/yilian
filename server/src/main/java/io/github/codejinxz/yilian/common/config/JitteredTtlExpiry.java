package io.github.codejinxz.yilian.common.config;

import com.github.benmanes.caffeine.cache.Expiry;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * 带 ±10% 随机抖动的 Caffeine 写后过期策略（防雪崩）。
 *
 * <p>批量重建的条目若共享固定 TTL，会在同一时刻集中过期、把流量同时打回数据库（缓存雪崩）； 本策略在写入时给每个条目独立乘上 0.9–1.1
 * 的随机系数，把到期点摊开。读取返回当前剩余时长， 即保持原到期点不变——热点键不会因为被频繁读而无限续期。
 *
 * <p>实现 {@link NegativeCacheEntry} 的值视为负缓存（结果为空/不可见），使用更短的 TTL： 挡穿透的同时尽快收敛到真实状态。
 */
public final class JitteredTtlExpiry implements Expiry<Object, Object> {

    private final Duration baseTtl;

    private final Duration negativeTtl;

    private final DoubleSupplier jitterSource;

    /**
     * 生产构造：抖动源用 {@code Math::random}（取值 [0,1)，线程安全）。
     *
     * @param baseTtl 正缓存基准 TTL
     * @param negativeTtl 负缓存（{@link NegativeCacheEntry}）TTL
     */
    public JitteredTtlExpiry(Duration baseTtl, Duration negativeTtl) {
        this(baseTtl, negativeTtl, Math::random);
    }

    /**
     * 测试构造：注入固定抖动源以断言边界。
     *
     * @param baseTtl 正缓存基准 TTL
     * @param negativeTtl 负缓存 TTL
     * @param jitterSource 抖动源，取值 [0,1)；0.0 取下界 0.9x，1.0 取上界 1.1x
     */
    public JitteredTtlExpiry(Duration baseTtl, Duration negativeTtl, DoubleSupplier jitterSource) {
        this.baseTtl = baseTtl;
        this.negativeTtl = negativeTtl;
        this.jitterSource = jitterSource;
    }

    /**
     * 首次写入（insert）时一次性确定抖动后的到期点。
     *
     * @param key 缓存键
     * @param value 缓存值
     * @param currentTimeNanos 当前单调时钟（纳秒）
     * @return 到期时间点（纳秒）
     */
    @Override
    public long expireAfterCreate(Object key, Object value, long currentTimeNanos) {
        return currentTimeNanos + jitteredNanos(ttlFor(value));
    }

    /**
     * 覆盖写（同键再 put）按新值重新抖动。
     *
     * @param key 缓存键
     * @param value 缓存值
     * @param currentTimeNanos 当前单调时钟（纳秒）
     * @param currentDurationNanos 原剩余时长（忽略）
     * @return 到期时间点（纳秒）
     */
    @Override
    public long expireAfterUpdate(
            Object key, Object value, long currentTimeNanos, long currentDurationNanos) {
        return currentTimeNanos + jitteredNanos(ttlFor(value));
    }

    /**
     * 读取不改变到期点：返回当前剩余时长，保持原到期时间。
     *
     * @param key 缓存键
     * @param value 缓存值
     * @param currentTimeNanos 当前单调时钟（纳秒）
     * @param currentDurationNanos 当前剩余时长
     * @return 未改变的剩余时长
     */
    @Override
    public long expireAfterRead(
            Object key, Object value, long currentTimeNanos, long currentDurationNanos) {
        return currentDurationNanos;
    }

    /**
     * 按条目类型选 TTL：负缓存短 TTL，其余用基准 TTL。
     *
     * @param value 缓存值
     * @return 该条目的基准 TTL
     */
    private Duration ttlFor(Object value) {
        return value instanceof NegativeCacheEntry ? negativeTtl : baseTtl;
    }

    /**
     * 应用 ±10% 抖动：0.9x–1.1x 均匀分布。
     *
     * @param ttl 基准 TTL
     * @return 抖动后的纳秒时长
     */
    private long jitteredNanos(Duration ttl) {
        double factor = 0.9 + 0.2 * jitterSource.getAsDouble();
        return (long) (ttl.toNanos() * factor);
    }
}
