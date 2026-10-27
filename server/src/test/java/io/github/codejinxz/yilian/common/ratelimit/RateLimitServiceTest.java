package io.github.codejinxz.yilian.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

/**
 * {@link RateLimitService} 的键格式、双维度、拒绝秒数与 fail-open 行为。
 *
 * <p>用内存中的 {@link StringRedisTemplate} 子类替代真实 Redis，不启动任何外部服务。
 */
class RateLimitServiceTest {

    private final RecordingRedisTemplate redisTemplate = new RecordingRedisTemplate();
    private final RateLimitService service =
            new RateLimitService(
                    redisTemplate, new MockEnvironment().withProperty("yilian.env", "test"));

    /** 放行时返回 1，并写入约定的 IP 键。 */
    @Test
    void allowedRequestWritesIpKey() {
        RateLimitDecision decision =
                service.acquire(
                        "login", List.of(RateLimitDimension.IP), 10, 60, "203.0.113.7", null);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.retryAfterSeconds()).isZero();
        assertThat(redisTemplate.keys)
                .containsExactly("yilian:test:ratelimit:ip:login:203.0.113.7");
        assertThat(redisTemplate.arguments[1]).isEqualTo("10");
        assertThat(redisTemplate.arguments[2]).isEqualTo("60000");
        assertThat(redisTemplate.arguments[3]).isEqualTo("61000");
    }

    /** IP + 账号双维度应同时生成两个键，并只做一次 Lua 调用。 */
    @Test
    void dualDimensionsUseBothKeys() {
        service.acquire(
                "login",
                List.of(RateLimitDimension.IP, RateLimitDimension.ACCOUNT),
                10,
                60,
                "203.0.113.7",
                "alice");

        assertThat(redisTemplate.keys)
                .containsExactly(
                        "yilian:test:ratelimit:ip:login:203.0.113.7",
                        "yilian:test:ratelimit:account:login:alice");
    }

    /** 脚本返回 0:秒数 时，判定结果应保留等待秒数。 */
    @Test
    void overLimitKeepsRetryAfterSeconds() {
        redisTemplate.result = "0:17";

        RateLimitDecision decision =
                service.acquire(
                        "deep-grade",
                        List.of(RateLimitDimension.ACCOUNT),
                        20,
                        3600,
                        "1.2.3.4",
                        "42");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(17);
        assertThat(redisTemplate.keys)
                .containsExactly("yilian:test:ratelimit:account:deep-grade:42");
    }

    /** Redis 不可用时必须 fail-open，不能把登录等主链路拖垮。 */
    @Test
    void redisFailureFailsOpen() {
        redisTemplate.failure = new IllegalStateException("redis down");

        RateLimitDecision decision =
                service.acquire(
                        "invite-code", List.of(RateLimitDimension.IP), 5, 600, "1.2.3.4", null);

        assertThat(decision.allowed()).isTrue();
    }

    /** 脚本返回无法识别的值时同样 fail-open，并保留 ERROR 日志路径。 */
    @Test
    void malformedResultFailsOpen() {
        redisTemplate.result = "unexpected";

        RateLimitDecision decision =
                service.acquire(
                        "invite-code", List.of(RateLimitDimension.IP), 5, 600, "1.2.3.4", null);

        assertThat(decision.allowed()).isTrue();
    }

    /** 记录键与参数的内存替身，避免单测依赖真实 Redis。 */
    static final class RecordingRedisTemplate extends StringRedisTemplate {

        private String result = "1";
        private RuntimeException failure;
        private List<String> keys = List.of();
        private Object[] arguments = new Object[0];

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            this.keys = List.copyOf(keys);
            this.arguments = args == null ? new Object[0] : args.clone();
            if (failure != null) {
                throw failure;
            }
            return (T) result;
        }
    }
}
