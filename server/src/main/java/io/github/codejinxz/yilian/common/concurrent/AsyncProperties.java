package io.github.codejinxz.yilian.common.concurrent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 异步执行配置，前缀 {@code yilian.async}。
 *
 * <p>虚拟线程执行器按任务创建线程，不需要容量参数；受控平台线程池的参数集中在本记录里， 便于按环境调整而不用改代码。默认值面向单机开发与小型部署：池子刻意不大，队列有界，
 * 拒绝时由调用线程兜底执行，避免无界队列把内存吃满。
 *
 * @param defaultTimeout {@link AsyncTasks} 未显式指定超时时使用的默认值，必须为正
 * @param bounded 受控平台线程池参数
 */
@ConfigurationProperties(prefix = "yilian.async")
public record AsyncProperties(Duration defaultTimeout, Bounded bounded) {

    /** 默认超时：2 秒足够覆盖一次轻量外部调用，过长会让请求线程被拖住。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 校验并补全默认值。
     *
     * @throws IllegalArgumentException 超时为负数或零时抛出，启动即失败而不是运行期静默
     */
    public AsyncProperties {
        defaultTimeout = defaultTimeout == null ? DEFAULT_TIMEOUT : defaultTimeout;
        bounded = bounded == null ? new Bounded(4, 16, 200, Duration.ofSeconds(60), 30) : bounded;
        if (defaultTimeout.isZero() || defaultTimeout.isNegative()) {
            throw new IllegalArgumentException("yilian.async.default-timeout 必须为正数");
        }
    }

    /**
     * 受控平台线程池参数。
     *
     * @param corePoolSize 核心线程数，必须大于 0
     * @param maxPoolSize 最大线程数，必须不小于核心线程数
     * @param queueCapacity 有界队列容量，必须不小于 0；0 表示使用同步移交
     * @param keepAlive 非核心线程空闲存活时间，必须为正
     * @param awaitTerminationSeconds 关闭时等待任务结束的秒数，必须不小于 0
     */
    public record Bounded(
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            Duration keepAlive,
            int awaitTerminationSeconds) {

        /**
         * 校验参数；非法值直接让应用启动失败，避免带着错误容量上线。
         *
         * @throws IllegalArgumentException 任一参数越界时抛出
         */
        public Bounded {
            keepAlive = keepAlive == null ? Duration.ofSeconds(60) : keepAlive;
            if (corePoolSize <= 0) {
                throw new IllegalArgumentException("yilian.async.bounded.core-pool-size 必须大于 0");
            }
            if (maxPoolSize < corePoolSize) {
                throw new IllegalArgumentException(
                        "yilian.async.bounded.max-pool-size 不能小于 core-pool-size");
            }
            if (queueCapacity < 0) {
                throw new IllegalArgumentException("yilian.async.bounded.queue-capacity 不能为负数");
            }
            if (keepAlive.isZero() || keepAlive.isNegative()) {
                throw new IllegalArgumentException("yilian.async.bounded.keep-alive 必须为正数");
            }
            if (awaitTerminationSeconds < 0) {
                throw new IllegalArgumentException(
                        "yilian.async.bounded.await-termination-seconds 不能为负数");
            }
        }
    }
}
