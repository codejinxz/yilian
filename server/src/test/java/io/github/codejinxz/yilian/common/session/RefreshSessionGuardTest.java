package io.github.codejinxz.yilian.common.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** refresh 会话吊销护栏单测：纪元只增、吊销「先增后删」、同用户刷新与吊销互斥（016 P2·03#08）。 */
class RefreshSessionGuardTest {

    private final RefreshSessionGuard guard = new RefreshSessionGuard();

    /** 未吊销过纪元为 0；每次吊销恰好 +1，且动作内已能看到新纪元（先增后删）。 */
    @Test
    void revokeBumpsEpochBeforeRunningAction() {
        assertThat(guard.epoch("alice")).isZero();

        List<Long> epochsInsideAction = new ArrayList<>();
        guard.revoke("alice", () -> epochsInsideAction.add(guard.epoch("alice")));
        guard.revoke("alice", () -> epochsInsideAction.add(guard.epoch("alice")));

        assertThat(epochsInsideAction).containsExactly(1L, 2L);
        assertThat(guard.epoch("alice")).isEqualTo(2L);
    }

    /** 不同用户的纪元与锁互不影响。 */
    @Test
    void epochsAreIsolatedPerPrincipal() {
        guard.revoke("alice", () -> {});
        guard.revoke("bob", () -> {});
        guard.revoke("bob", () -> {});

        assertThat(guard.epoch("alice")).isEqualTo(1L);
        assertThat(guard.epoch("bob")).isEqualTo(2L);
    }

    /** 同一用户的 withLock 串行化并发临界区：计数器无丢失更新（互斥证据）。 */
    @Test
    void withLockSerializesSamePrincipal() throws Exception {
        int threads = 8;
        int rounds = 200;
        int[] shared = {0};
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    for (int round = 0; round < rounds; round++) {
                                        guard.withLock(
                                                "alice",
                                                () -> {
                                                    shared[0] = shared[0] + 1;
                                                    return null;
                                                });
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(45, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(shared[0]).isEqualTo(threads * rounds);
    }
}
