package io.github.codejinxz.yilian.upload.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 导入异步线程池。
 *
 * <p>刻意不用 {@code @Async}：导入任务需要明确拒绝策略、线程名与关闭语义，直接注入 {@link ExecutorService}
 * 更容易测试（测试可换成单线程或受控执行器），也避免 {@code @Async} 自调用失效的问题。线程数固定为 2，管理端导入不是高并发路径；队列无界但 单文件上限
 * 5MB，实际堆积风险很低。线程标记为 daemon，应用关闭时不会因为残留导入任务 阻塞 JVM，遗留的 PROCESSING 批次由启动恢复钩子转 PAUSED。
 */
@Configuration(proxyBeanMethods = false)
public class ImportAsyncConfig {

    /** 导入线程池 Bean 名，Service 通过 {@code @Qualifier} 引用。 */
    public static final String IMPORT_EXECUTOR = "importExecutor";

    /** 同时处理的导入批次数；单实例部署下足够，避免多个大文件争抢 CPU 与数据库连接。 */
    private static final int WORKER_THREADS = 2;

    /**
     * 创建导入专用线程池。
     *
     * @return 固定大小线程池，线程为 daemon 且有稳定名称前缀
     */
    @Bean(name = IMPORT_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService importExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory =
                runnable -> {
                    Thread thread =
                            new Thread(runnable, "yilian-import-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                };
        return Executors.newFixedThreadPool(WORKER_THREADS, threadFactory);
    }
}
