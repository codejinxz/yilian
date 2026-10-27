package io.github.codejinxz.yilian.common.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.common.security.SecurityUtils;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 异步上下文传播：MDC 与 SecurityContext 进入工作线程，任务结束后不污染线程池与调用线程。 */
class ContextAwareTaskExecutorTest {

    private static final YilianUserPrincipal PRINCIPAL =
            YilianUserPrincipal.of(7L, "alice", "USER", true);

    @AfterEach
    void clearContext() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void propagatesMdcAndSecurityContextToWorkerThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ContextAwareTaskExecutor executor =
                new ContextAwareTaskExecutor(
                        pool, new MdcSecurityTaskDecorator(), Duration.ofSeconds(2));
        try {
            MDC.put("traceId", "trace-abc");
            authenticate();

            Future<CapturedContext> future =
                    executor.submit(
                            () ->
                                    new CapturedContext(
                                            MDC.get("traceId"),
                                            SecurityUtils.currentUserId().orElse(null)));
            CapturedContext captured = future.get(2, TimeUnit.SECONDS);

            assertThat(captured.traceId()).isEqualTo("trace-abc");
            assertThat(captured.userId()).isEqualTo(7L);
            assertThat(MDC.get("traceId")).isEqualTo("trace-abc");
            assertThat(SecurityUtils.currentUserId()).contains(7L);
        } finally {
            executor.close();
        }
    }

    @Test
    void clearsWorkerContextWhenSubmitterHasNoContext() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ContextAwareTaskExecutor executor =
                new ContextAwareTaskExecutor(
                        pool, new MdcSecurityTaskDecorator(), Duration.ofSeconds(2));
        try {
            MDC.put("traceId", "trace-first");
            authenticate();
            executor.submit(() -> MDC.get("traceId")).get(2, TimeUnit.SECONDS);

            MDC.clear();
            SecurityContextHolder.clearContext();
            Future<CapturedContext> second =
                    executor.submit(
                            () ->
                                    new CapturedContext(
                                            MDC.get("traceId"),
                                            SecurityUtils.currentUserId().orElse(null)));

            assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo(new CapturedContext(null, null));
        } finally {
            executor.close();
        }
    }

    /** 把当前线程设为已认证用户，模拟请求线程进入异步调用前的状态。 */
    private static void authenticate() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                PRINCIPAL, "ignored", PRINCIPAL.getAuthorities()));
    }

    /**
     * 工作线程内捕获到的上下文快照。
     *
     * @param traceId MDC 中的链路 id
     * @param userId 安全上下文中的用户 id
     */
    private record CapturedContext(String traceId, Long userId) {}
}
