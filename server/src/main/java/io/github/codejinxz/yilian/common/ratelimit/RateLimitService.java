package io.github.codejinxz.yilian.common.ratelimit;

import io.github.codejinxz.yilian.common.util.IdUtils;
import io.github.codejinxz.yilian.common.util.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis ZSET 的滑动窗口限流器。
 *
 * <p>键格式固定为 {@code yilian:{env}:ratelimit:{dimension}:{scene}:{key}}：{@code {dimension}} 是 {@code
 * ip}/{@code account}，{@code {scene}:{key}} 组成业务标识， 既保留约定的键前缀，又避免登录与邀请码共用同一 IP 桶。每个维度一个
 * ZSET，成员为「毫秒时间戳 + 随机串」，分数为毫秒时间戳。
 *
 * <p>Lua 脚本在一次调用中先对全部维度做「裁剪过期成员 + 计数」，全部未超限才统一写入； 任一维度超限则整次请求不计数，并返回需要等待的秒数。这样 IP + 账号双维度不会出现
 * 「账号被拒但 IP 已多记一次」的偏差，也避免并发下的 check-then-act 竞态。
 *
 * <p>键始终设置 {@code PEXPIRE}（窗口 + 1 秒）兜底，即使写入后长期无请求也会自动消失； Redis 不可用时 fail-open，只写 ERROR
 * 日志，保证登录等主链路不被缓存故障拖垮。
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    /** 脚本返回 {@code 1} 表示放行，返回 {@code 0:秒数} 表示拒绝。 */
    private static final RedisScript<String> SLIDING_WINDOW_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local now = tonumber(ARGV[1])
                    local limit = tonumber(ARGV[2])
                    local windowMillis = tonumber(ARGV[3])
                    local ttlMillis = tonumber(ARGV[4])
                    local cutoff = now - windowMillis
                    local retryAfterSeconds = 0
                    for i = 1, #KEYS do
                      redis.call('ZREMRANGEBYSCORE', KEYS[i], 0, cutoff)
                      local count = redis.call('ZCARD', KEYS[i])
                      if count >= limit then
                        local oldest = redis.call('ZRANGE', KEYS[i], 0, 0, 'WITHSCORES')
                        local oldestScore = now
                        if oldest[2] then
                          oldestScore = tonumber(oldest[2])
                        end
                        local waitMillis = oldestScore + windowMillis - now
                        if waitMillis < 1 then
                          waitMillis = 1
                        end
                        local waitSeconds = math.ceil(waitMillis / 1000)
                        if waitSeconds > retryAfterSeconds then
                          retryAfterSeconds = waitSeconds
                        end
                      end
                    end
                    if retryAfterSeconds > 0 then
                      return '0:' .. retryAfterSeconds
                    end
                    for i = 1, #KEYS do
                      redis.call('ZADD', KEYS[i], now, ARGV[4 + i])
                      redis.call('PEXPIRE', KEYS[i], ttlMillis)
                    end
                    return '1'
                    """,
                    String.class);

    private static final int MAX_SEGMENT_LENGTH = 64;

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    /**
     * 注入 Redis 模板并从环境解析键前缀中的 {@code {env}}。
     *
     * @param redisTemplate Spring StringRedisTemplate
     * @param environment 环境配置，优先读 {@code yilian.env}，否则取第一个激活 profile
     */
    public RateLimitService(StringRedisTemplate redisTemplate, Environment environment) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = "yilian:" + resolveEnvironment(environment) + ":ratelimit:";
    }

    /**
     * 对一次请求做多维度滑动窗口判定。
     *
     * @param scene 业务场景名，写入键并用于日志，不得包含用户输入
     * @param dimensions 参与判定的维度
     * @param limit 窗口内允许的最大请求数
     * @param windowSeconds 窗口长度，单位秒
     * @param clientIp 来源 IP，由调用方通过 {@code WebUtils.clientIp} 提取
     * @param account 账号标识；账号维度未提供时该维度被跳过
     * @return 放行或拒绝的判定；Redis 故障时为放行
     */
    public RateLimitDecision acquire(
            String scene,
            List<RateLimitDimension> dimensions,
            int limit,
            int windowSeconds,
            String clientIp,
            String account) {
        if (limit <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("限流阈值与窗口必须为正数");
        }
        String sceneSegment = normalize(scene, "unknown");
        List<String> keys = new ArrayList<>(2);
        if (dimensions != null && dimensions.contains(RateLimitDimension.IP)) {
            keys.add(
                    keyPrefix
                            + RateLimitDimension.IP.keySegment()
                            + ":"
                            + sceneSegment
                            + ":"
                            + normalize(clientIp, "unknown"));
        }
        if (dimensions != null
                && dimensions.contains(RateLimitDimension.ACCOUNT)
                && StringUtils.isNotBlank(account)) {
            keys.add(
                    keyPrefix
                            + RateLimitDimension.ACCOUNT.keySegment()
                            + ":"
                            + sceneSegment
                            + ":"
                            + normalize(account, "unknown"));
        }
        if (keys.isEmpty()) {
            return RateLimitDecision.permit();
        }

        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;
        long ttlMillis = windowMillis + 1000L;
        Object[] arguments = new Object[4 + keys.size()];
        arguments[0] = Long.toString(now);
        arguments[1] = Integer.toString(limit);
        arguments[2] = Long.toString(windowMillis);
        arguments[3] = Long.toString(ttlMillis);
        for (int index = 0; index < keys.size(); index++) {
            arguments[4 + index] = now + ":" + IdUtils.newCompactId();
        }

        String result;
        try {
            result = redisTemplate.execute(SLIDING_WINDOW_SCRIPT, keys, arguments);
        } catch (RuntimeException redisFailure) {
            log.error("rate limit check failed, fail-open scene={}", sceneSegment, redisFailure);
            return RateLimitDecision.permit();
        }
        if (result == null) {
            log.error("rate limit script returned null, fail-open scene={}", sceneSegment);
            return RateLimitDecision.permit();
        }
        if ("1".equals(result)) {
            return RateLimitDecision.permit();
        }
        if (result.startsWith("0:")) {
            try {
                return RateLimitDecision.reject(Long.parseLong(result.substring(2)));
            } catch (NumberFormatException malformed) {
                log.error(
                        "rate limit script returned malformed result, fail-open scene={}",
                        sceneSegment);
                return RateLimitDecision.permit();
            }
        }
        log.error("rate limit script returned unexpected result, fail-open scene={}", sceneSegment);
        return RateLimitDecision.permit();
    }

    private static String resolveEnvironment(Environment environment) {
        String configured = environment.getProperty("yilian.env");
        if (StringUtils.isNotBlank(configured)) {
            return normalize(configured, "local");
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles != null && activeProfiles.length > 0) {
            return normalize(activeProfiles[0], "local");
        }
        return "local";
    }

    private static String normalize(String value, String fallback) {
        String normalized = StringUtils.defaultIfBlank(value, fallback).trim();
        if (normalized.length() > MAX_SEGMENT_LENGTH) {
            normalized = normalized.substring(0, MAX_SEGMENT_LENGTH);
        }
        return normalized.replace(':', '_');
    }
}
