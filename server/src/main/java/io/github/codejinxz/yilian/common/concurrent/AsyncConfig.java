package io.github.codejinxz.yilian.common.concurrent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行器集中配置，启用 {@code @Async} 并定义两类执行器。
 *
 * <p>两类执行器解决不同问题，不能互相替代：
 *
 * <ul>
 *   <li>{@value #VIRTUAL_TASK_EXECUTOR}：每次任务创建一个虚拟线程，适合大量「等待型」任务
 *       （HTTP、数据库、对象存储）。它不限制并发，因此不得用来包装会打满外部依赖的调用。
 *   <li>{@value #BOUNDED_TASK_EXECUTOR}：固定平台线程池，核心/最大/队列可配，队列满时由调用线程 兜底执行（{@link
 *       ThreadPoolExecutor.CallerRunsPolicy}），用于必须限制并发、保护下游的任务。
 * </ul>
 *
 * <p>虚拟线程执行器额外注册别名 {@code taskExecutor} 并标记 {@link Primary}，使不带限定符的 {@code @Async} 有确定落点；需要受控池时显式写
 * {@code @Async("boundedTaskExecutor")}。
 *
 * <p>两个执行器都套用 {@link MdcSecurityTaskDecorator}，保证异步任务里的日志 {@code traceId} 与 当前登录用户不丢失。线程名为便于排查，统一带
 * {@code yilian-} 前缀。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 通用虚拟线程执行器 bean 名，默认供 {@code @Async} 使用。 */
    public static final String VIRTUAL_TASK_EXECUTOR = "virtualThreadTaskExecutor";

    /** 受控平台线程池 bean 名，需显式指定时使用。 */
    public static final String BOUNDED_TASK_EXECUTOR = "boundedTaskExecutor";

    /** {@code @Async} 未指定限定符时按此名称查找执行器。 */
    private static final String DEFAULT_TASK_EXECUTOR = "taskExecutor";

    /** 受控池线程名前缀，与项目内其他线程区分。 */
    private static final String BOUNDED_THREAD_NAME_PREFIX = "yilian-async-";

    /** 虚拟线程执行器退出时等待任务收尾的时间，避免停机无限期挂起。 */
    private static final Duration VIRTUAL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 上下文传播装饰器：两个执行器共用同一实现，避免出现两套不一致的传播逻辑。
     *
     * @return 无状态、可安全复用的装饰器
     */
    @Bean
    public MdcSecurityTaskDecorator contextTaskDecorator() {
        return new MdcSecurityTaskDecorator();
    }

    /**
     * 通用虚拟线程执行器，按任务创建虚拟线程，适合等待型 IO。
     *
     * <p>返回 {@link ContextAwareTaskExecutor} 而不是裸 {@code TaskExecutorAdapter}，是为了把 {@code
     * Executors.newVirtualThreadPerTaskExecutor()} 的关闭责任、上下文传播和 Future 语义 收在一个类里；容器关闭时会调用 {@link
     * ContextAwareTaskExecutor#close()} 优雅收尾。
     *
     * @param decorator 上下文传播装饰器
     * @return 默认的异步执行器
     */
    @Bean(
            name = {VIRTUAL_TASK_EXECUTOR, DEFAULT_TASK_EXECUTOR},
            destroyMethod = "close")
    @Primary
    public ContextAwareTaskExecutor virtualThreadTaskExecutor(MdcSecurityTaskDecorator decorator) {
        ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
        return new ContextAwareTaskExecutor(delegate, decorator, VIRTUAL_SHUTDOWN_TIMEOUT);
    }

    /**
     * 受控平台线程池：有界队列 + CallerRunsPolicy，过载时反压给提交线程而不是静默丢弃。
     *
     * @param properties 线程池参数
     * @param decorator 上下文传播装饰器
     * @return 受控异步执行器
     */
    @Bean(name = BOUNDED_TASK_EXECUTOR)
    public ThreadPoolTaskExecutor boundedTaskExecutor(
            AsyncProperties properties, MdcSecurityTaskDecorator decorator) {
        AsyncProperties.Bounded config = properties.bounded();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(BOUNDED_THREAD_NAME_PREFIX);
        executor.setCorePoolSize(config.corePoolSize());
        executor.setMaxPoolSize(config.maxPoolSize());
        executor.setQueueCapacity(config.queueCapacity());
        executor.setKeepAliveSeconds((int) Math.max(1, config.keepAlive().toSeconds()));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(decorator);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(config.awaitTerminationSeconds());
        return executor;
    }
}
