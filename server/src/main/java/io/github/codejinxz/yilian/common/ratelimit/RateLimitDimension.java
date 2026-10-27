package io.github.codejinxz.yilian.common.ratelimit;

/**
 * 限流维度。
 *
 * <p>登录需要同时按来源 IP 与账号计数，深度批改只按登录账号计数，邀请码只按来源 IP 计数； 维度决定 Redis 键中的 {@code {dimension}}
 * 段，调用方按业务场景组合即可。
 */
public enum RateLimitDimension {
    /** 来源 IP，由 {@code WebUtils.clientIp} 统一提取，不信任可伪造的转发头。 */
    IP("ip"),
    /** 登录账号或登录用户 id，未登录且未配置账号表达式时该维度会被跳过。 */
    ACCOUNT("account");

    private final String keySegment;

    RateLimitDimension(String keySegment) {
        this.keySegment = keySegment;
    }

    /**
     * Redis 键中的维度段。
     *
     * @return 固定小写标识，例如 {@code ip}、{@code account}
     */
    public String keySegment() {
        return keySegment;
    }
}
