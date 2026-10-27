package io.github.codejinxz.yilian.common.ratelimit;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;

/**
 * 触发限流时抛出的业务异常。
 *
 * <p>除业务码 42900 外还携带 {@code Retry-After} 秒数，由全局异常出口写入响应头；继承 {@link BusinessException} 以保证不会被当作未知异常写
 * {@code error_log}。
 */
public class RateLimitExceededException extends BusinessException {

    private final long retryAfterSeconds;

    /**
     * 按判定结果构造异常，文案直接告诉用户还要等多久。
     *
     * @param retryAfterSeconds 距离窗口释放一个名额的秒数，至少为 1
     */
    public RateLimitExceededException(long retryAfterSeconds) {
        super(ApiCode.RATE_LIMITED, "操作过于频繁，请 " + Math.max(1, retryAfterSeconds) + " 秒后再试");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /**
     * 建议客户端等待的秒数。
     *
     * @return 正整数，写入 {@code Retry-After} 响应头
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
