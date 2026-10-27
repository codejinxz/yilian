package io.github.codejinxz.yilian.common.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * 给任意 {@link Executor} 包一层上下文传播能力的异步执行器。
 *
 * <p>虚拟线程执行器由 {@code Executors.newVirtualThreadPerTaskExecutor()} 创建，本身只是普通 {@link Executor}，不支持
 * Spring 的 {@link TaskDecorator}。本类在提交侧统一套用装饰器，因此既能 包装 JDK 执行器，也能包装 Spring 执行器；执行器本身不解析任务类型，{@code
 * execute}/{@code submit} 与默认的 {@code submitCompletable} 都会走同一条传播路径。
 *
 * <p>本类只负责「让任务在正确的上下文里跑」，不做限流、重试或超时；超时与降级见 {@link AsyncTasks}。 关闭时先 {@code shutdown()} 等待，超时后
 * {@code shutdownNow()}，避免应用停机被挂起的任务拖住。
 */
public final class ContextAwareTaskExecutor implements AsyncTaskExecutor, AutoCloseable {

    /** 默认关闭等待时间：足够让短任务收尾，又不会让停机无限期挂起。 */
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(ContextAwareTaskExecutor.class);

    private final Executor delegate;
    private final TaskDecorator decorator;
    private final Duration shutdownTimeout;

    /**
     * 使用默认关闭等待时间创建执行器。
     *
     * @param delegate 真正执行任务的执行器，不能为 null
     * @param decorator 上下文传播装饰器，不能为 null
     */
    public ContextAwareTaskExecutor(Executor delegate, TaskDecorator decorator) {
        this(delegate, decorator, DEFAULT_SHUTDOWN_TIMEOUT);
    }

    /**
     * 创建执行器并指定关闭等待时间。
     *
     * @param delegate 真正执行任务的执行器，不能为 null
     * @param decorator 上下文传播装饰器，不能为 null
     * @param shutdownTimeout 关闭时等待任务结束的最长时间，不能为 null
     */
    public ContextAwareTaskExecutor(
            Executor delegate, TaskDecorator decorator, Duration shutdownTimeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.decorator = Objects.requireNonNull(decorator, "decorator");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
    }

    /**
     * 以「即发即忘」方式执行任务，任务异常交给执行器的异常处理策略。
     *
     * @param task 待执行任务，不能为 null
     */
    @Override
    public void execute(Runnable task) {
        delegate.execute(decorator.decorate(Objects.requireNonNull(task, "task")));
    }

    /**
     * 提交无返回值任务并返回可取消的 Future。
     *
     * <p>Future 本身就是被装饰的任务，因此 {@code get()} 的等待与取消语义不受包装影响。
     *
     * @param task 待执行任务，不能为 null
     * @return 与任务对应的 Future
     */
    @Override
    public Future<?> submit(Runnable task) {
        FutureTask<Void> future = new FutureTask<>(Objects.requireNonNull(task, "task"), null);
        delegate.execute(decorator.decorate(future));
        return future;
    }

    /**
     * 提交有返回值任务并返回可取消的 Future。
     *
     * @param task 待执行任务，不能为 null
     * @param <T> 返回值类型
     * @return 与任务对应的 Future
     */
    @Override
    public <T> Future<T> submit(Callable<T> task) {
        FutureTask<T> future = new FutureTask<>(Objects.requireNonNull(task, "task"));
        delegate.execute(decorator.decorate(future));
        return future;
    }

    /**
     * 暴露当前使用的装饰器，便于调用方在自建执行路径上复用同一套传播规则。
     *
     * @return 上下文传播装饰器
     */
    public TaskDecorator decorator() {
        return decorator;
    }

    /**
     * 关闭底层执行器：先等待任务自然结束，超时后强制中断仍在运行的任务。
     *
     * <p>底层不是 {@link ExecutorService} 时什么也不做，关闭责任留给它的所有者。
     */
    @Override
    public void close() {
        if (!(delegate instanceof ExecutorService service)) {
            return;
        }
        service.shutdown();
        try {
            if (!service.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn(
                        "task executor did not terminate in {}, forcing shutdown", shutdownTimeout);
                service.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            service.shutdownNow();
        }
    }
}
