package io.github.codejinxz.yilian.common.ratelimit;

/**
 * 一次限流判定的结果。
 *
 * @param allowed 是否放行；Redis 不可用时按 fail-open 策略固定为 true
 * @param retryAfterSeconds 拒绝时建议客户端等待的秒数，至少 1；放行时为 0
 */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {

    /**
     * 放行结果。
     *
     * @return {@code allowed=true}、{@code retryAfterSeconds=0} 的判定
     */
    public static RateLimitDecision permit() {
        return new RateLimitDecision(true, 0);
    }

    /**
     * 拒绝结果，保证 Retry-After 至少为 1 秒。
     *
     * @param retryAfterSeconds 距离窗口释放一个名额的秒数
     * @return 拒绝判定
     */
    public static RateLimitDecision reject(long retryAfterSeconds) {
        return new RateLimitDecision(false, Math.max(1, retryAfterSeconds));
    }
}
