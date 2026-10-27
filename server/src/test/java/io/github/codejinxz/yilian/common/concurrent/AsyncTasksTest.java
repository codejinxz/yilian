package io.github.codejinxz.yilian.common.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 超时与降级：超时/异常返回 fallback，批量任务保持「全部成功或整体失败」。 */
class AsyncTasksTest {

    private ExecutorService pool;
    private AsyncTasks tasks;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);
        tasks =
                new AsyncTasks(
                        new TaskExecutorAdapter(pool),
                        new AsyncProperties(Duration.ofMillis(50), null));
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void returnsFallbackWhenTaskTimesOut() {
        String result =
                tasks.callWithFallback(
                        "test.timeout",
                        () -> {
                            Thread.sleep(5_000);
                            return "late";
                        },
                        Duration.ofMillis(30),
                        "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void returnsFallbackWhenTaskFails() {
        String result =
                tasks.callWithFallback(
                        "test.failure",
                        () -> {
                            throw new IllegalStateException("boom");
                        },
                        Duration.ofSeconds(1),
                        "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void usesConfiguredDefaultTimeoutWhenTimeoutIsNull() {
        String result =
                tasks.callWithFallback(
                        "test.default-timeout",
                        () -> {
                            Thread.sleep(5_000);
                            return "late";
                        },
                        null,
                        "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void invokeAllKeepsTaskOrder() {
        List<String> result =
                tasks.invokeAll(
                        "test.batch",
                        List.of(() -> "first", () -> "second", () -> "third"),
                        Duration.ofSeconds(1));

        assertThat(result).containsExactly("first", "second", "third");
    }

    @Test
    void invokeAllFailsWholeBatchWhenOneTaskFails() {
        assertThatThrownBy(
                        () ->
                                tasks.invokeAll(
                                        "test.batch-failure",
                                        List.of(
                                                () -> "ok",
                                                () -> {
                                                    throw new IllegalStateException("boom");
                                                }),
                                        Duration.ofSeconds(1)))
                .isInstanceOf(AsyncTasks.AsyncTaskException.class)
                .satisfies(
                        error ->
                                assertThat(((AsyncTasks.AsyncTaskException) error).operation())
                                        .isEqualTo("test.batch-failure"));
    }

    @Test
    void invokeAllFailsWhenBatchTimesOut() {
        assertThatThrownBy(
                        () ->
                                tasks.invokeAll(
                                        "test.batch-timeout",
                                        List.of(
                                                () -> {
                                                    Thread.sleep(5_000);
                                                    return "late";
                                                }),
                                        Duration.ofMillis(30)))
                .isInstanceOf(AsyncTasks.AsyncTaskException.class)
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
    }
}
