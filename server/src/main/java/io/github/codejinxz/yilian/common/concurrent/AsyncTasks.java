package io.github.codejinxz.yilian.common.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 异步超时与降级工具，用 {@link CompletableFuture} 实现结构化并发语义的常用子集。
 *
 * <p><strong>为什么不用 {@code StructuredTaskScope}</strong>：Java 21 中它仍是 preview API， 编译与运行都必须显式开启
 * {@code --enable-preview}。项目没有改 pom 与启动参数，直接使用会让构建 绑定到特定 JDK 与 preview 开关，并可能在升级 JDK 时改变语义。因此这里用
 * {@code CompletableFuture + orTimeout} 实现，代价必须写清楚：
 *
 * <ul>
 *   <li>超时或失败后的 {@code cancel(true)} 不会中断已经在运行的任务，只是让等待方提前返回； 提交的任务必须自己响应中断并在有界时间内结束。
 *   <li>没有 {@code StructuredTaskScope} 的自动 join/cancel 与作用域继承；批量方法只能尽力取消 兄弟任务，仍在运行的任务会继续占用执行器。
 *   <li>没有 {@code ScopedValue} 传播；上下文传播仍由 {@link MdcSecurityTaskDecorator} 负责。
 * </ul>
 *
 * <p>约定：所有方法不抛出任务自身的受检异常。{@link #callWithFallback} 在超时、中断或执行失败时 返回调用方给定的降级值并记录 WARN；{@link
 * #invokeAll} 保持「全部成功或整体失败」的语义，把失败 包装成 {@link AsyncTaskException} 抛出，交由上层决定是否降级。
 */
@Component
public class AsyncTasks {

    private static final Logger log = LoggerFactory.getLogger(AsyncTasks.class);

    private final AsyncTaskExecutor executor;
    private final Duration defaultTimeout;

    /**
     * 由容器注入默认虚拟线程执行器与全局默认超时。
     *
     * @param executor 默认执行器，必须与 {@link AsyncConfig#VIRTUAL_TASK_EXECUTOR} 一致
     * @param properties 异步配置，提供默认超时
     */
    public AsyncTasks(
            @Qualifier(AsyncConfig.VIRTUAL_TASK_EXECUTOR) AsyncTaskExecutor executor,
            AsyncProperties properties) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.defaultTimeout = Objects.requireNonNull(properties, "properties").defaultTimeout();
    }

    /**
     * 异步执行单个任务，超时、中断或失败时返回降级值并记录 WARN。
     *
     * <p>降级是产品规则「模型/外部依赖失败必须降级」的落地：调用方只关心「拿到一个可用结果」， 不需要为每次外部调用重复写 try/catch。WARN
     * 只记录操作名、超时与异常类名，不记录参数， 避免把译文或用户内容写进日志。
     *
     * @param operation 操作名，用于日志定位，不能为 null
     * @param task 待执行任务，不能为 null
     * @param timeout 超时时间；为 null 时使用 {@code yilian.async.default-timeout}
     * @param fallback 超时或失败时返回的降级值，允许为 null
     * @param <T> 返回值类型
     * @return 任务结果或降级值
     */
    public <T> T callWithFallback(
            String operation, Callable<T> task, Duration timeout, T fallback) {
        String name = requireOperation(operation);
        Duration effectiveTimeout = resolveTimeout(timeout);
        Future<T> future = executor.submit(task);
        try {
            return future.get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn(
                    "async task timed out, returning fallback: operation={}, timeoutMs={}",
                    name,
                    effectiveTimeout.toMillis());
            return fallback;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("async task interrupted, returning fallback: operation={}", name);
            return fallback;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn(
                    "async task failed, returning fallback: operation={}, error={}",
                    name,
                    cause.getClass().getSimpleName());
            return fallback;
        }
    }

    /**
     * 并发执行一组任务，全部成功才返回，任一失败或超时则取消其余任务并抛出异常。
     *
     * <p>这是 {@code StructuredTaskScope.ShutdownOnFailure} 的近似语义：要么整体成功，要么整体失败，
     * 不会把部分成功当成成功。返回值顺序与入参一致。超时/失败时对未完成任务的取消不保证中断， 具体取舍见类注释。
     *
     * @param operation 操作名，用于日志定位，不能为 null
     * @param tasks 待执行任务列表，不能为 null；空列表直接返回空列表
     * @param timeout 整批任务的超时时间；为 null 时使用默认超时
     * @param <T> 返回值类型
     * @return 与入参顺序一致的结果列表
     * @throws AsyncTaskException 任一任务失败或整批超时时抛出，cause 保留原始异常或 {@link TimeoutException}
     */
    public <T> List<T> invokeAll(String operation, List<Callable<T>> tasks, Duration timeout) {
        String name = requireOperation(operation);
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.isEmpty()) {
            return List.of();
        }
        Duration effectiveTimeout = resolveTimeout(timeout);
        List<CompletableFuture<T>> futures =
                tasks.stream()
                        .map(
                                task ->
                                        CompletableFuture.supplyAsync(
                                                () -> callUnchecked(task), executor))
                        .toList();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .orTimeout(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException e) {
            futures.forEach(AsyncTasks::cancelQuietly);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn(
                    "async batch aborted: operation={}, timeoutMs={}, error={}",
                    name,
                    effectiveTimeout.toMillis(),
                    cause.getClass().getSimpleName());
            throw new AsyncTaskException(name, cause);
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * 把受检异常转成便于 {@link CompletableFuture} 传播的非受检异常。
     *
     * @param task 待执行任务
     * @param <T> 返回值类型
     * @return 任务结果
     */
    private <T> T callUnchecked(Callable<T> task) {
        try {
            return task.call();
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    /**
     * 校验操作名，避免日志里出现空字段导致无法定位。
     *
     * @param operation 原始操作名
     * @return 去掉首尾空白后的操作名
     */
    private static String requireOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation 不能为空");
        }
        return operation.strip();
    }

    /**
     * 解析实际超时时间并校验。
     *
     * @param timeout 调用方传入的超时，允许为 null
     * @return 非空且为正的超时时间
     */
    private Duration resolveTimeout(Duration timeout) {
        Duration effective = timeout == null ? defaultTimeout : timeout;
        if (effective.isZero() || effective.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正数");
        }
        return effective;
    }

    /**
     * 取消任务但忽略取消失败的返回值，取消只用于尽早释放等待方。
     *
     * @param future 待取消的任务
     */
    private static void cancelQuietly(CompletableFuture<?> future) {
        future.cancel(true);
    }

    /** 批量任务整体失败时的异常，携带操作名便于上层按操作决定降级文案。 */
    public static final class AsyncTaskException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String operation;

        /**
         * 包装批量任务的原始失败原因。
         *
         * @param operation 操作名
         * @param cause 原始异常，通常为任务抛出的异常或 {@link TimeoutException}
         */
        public AsyncTaskException(String operation, Throwable cause) {
            super("异步任务失败: " + operation, cause);
            this.operation = operation;
        }

        /**
         * 失败的操作名。
         *
         * @return 调用方传入的 operation
         */
        public String operation() {
            return operation;
        }
    }
}
