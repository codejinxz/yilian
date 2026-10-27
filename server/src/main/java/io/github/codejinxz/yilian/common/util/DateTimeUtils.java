package io.github.codejinxz.yilian.common.util;

import java.time.Duration;
import java.time.Instant;

/**
 * 时间相关公共工具，当前只承载“按签发时刻与有效期判断是否过期”这一条规则。
 *
 * <p>过期判断一律失败关闭：缺少签发时刻、有效期或当前时刻时按已过期处理， 避免调用方漏判导致凭证被无限期接受。本类无状态、线程安全，禁止实例化。
 */
public final class DateTimeUtils {

    private DateTimeUtils() {}

    /**
     * 获取当前 UTC 瞬时时间，集中入口便于后续替换为可控时钟。
     *
     * @return 当前时刻
     */
    public static Instant now() {
        return Instant.now();
    }

    /**
     * 判断凭证是否已过期：{@code issuedAt.plus(ttl)} 不晚于 now 即视为过期， 因此恰好到期的那一刻也算过期。
     *
     * <p>issuedAt、ttl 或 now 为 null 时无法证明凭证仍有效，一律按已过期处理。
     *
     * @param issuedAt 签发时刻，允许为 null
     * @param ttl 有效期，允许为 null
     * @param now 用于比较的当前时刻，允许为 null
     * @return 已过期返回 true；任一参数为 null 时同样返回 true
     */
    public static boolean isExpired(Instant issuedAt, Duration ttl, Instant now) {
        if (issuedAt == null || ttl == null || now == null) {
            return true;
        }
        return !issuedAt.plus(ttl).isAfter(now);
    }

    /**
     * 以 {@link Instant#now()} 为比较基准的过期判断，适用于请求处理现场的快速校验。
     *
     * @param issuedAt 签发时刻，允许为 null
     * @param ttl 有效期，允许为 null
     * @return 已过期返回 true；issuedAt 或 ttl 为 null 时同样返回 true
     */
    public static boolean isExpired(Instant issuedAt, Duration ttl) {
        return isExpired(issuedAt, ttl, Instant.now());
    }
}
