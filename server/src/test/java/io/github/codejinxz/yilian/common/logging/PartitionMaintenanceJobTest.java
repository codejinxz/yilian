package io.github.codejinxz.yilian.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;

/**
 * {@link PartitionMaintenanceJob} 的幂等清理分支单测：Mock JdbcTemplate，不启动 Spring 容器。
 *
 * <p>覆盖 013 AC-1：过期边界 SQL 语义（{@code expires_at <= ?} 含边界）、分批截断与循环收敛、 失败只记日志不上抛、以及 @Scheduled
 * 注册的存在性断言。分区维护主流程已有集成环境兜底，此处不重复。
 */
class PartitionMaintenanceJobTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private final PartitionMaintenanceJob job = new PartitionMaintenanceJob(jdbcTemplate, 365, 30);

    /** [AC-1] 清理使用「expires_at <= 截止时间」的含边界语义，截止时间取执行时刻（正好过期即删）。 */
    @Test
    void purgeUsesInclusiveExpiryBoundaryWithNowCutoff_ac1() {
        long before = System.currentTimeMillis();
        when(jdbcTemplate.update(anyString(), any(Timestamp.class))).thenReturn(3);

        job.purgeExpiredIdempotencyRecords();

        ArgumentCaptor<Timestamp> captor = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbcTemplate)
                .update(
                        contains("DELETE FROM idempotency_record WHERE expires_at <= ? LIMIT 500"),
                        captor.capture());
        long cutoff = captor.getValue().getTime();
        // 截止时间必须是清理执行时刻附近（允许 1 秒调度误差），未过期记录（expires_at > cutoff）不会被命中。
        assertThat(cutoff).isBetween(before - 1_000, System.currentTimeMillis() + 1_000);
    }

    /** [AC-1] 批满 500 触发下一批，直到批不满为止；清理量跨批累加。 */
    @Test
    void purgeLoopsWhileBatchIsFull_ac1() {
        when(jdbcTemplate.update(anyString(), any(Timestamp.class))).thenReturn(500).thenReturn(0);

        job.purgeExpiredIdempotencyRecords();

        verify(jdbcTemplate, times(2)).update(anyString(), any(Timestamp.class));
    }

    /** [AC-1] 批不满即收敛：单批 123 行只发一条 DELETE，不做多余空删。 */
    @Test
    void purgeStopsWhenBatchIsNotFull_ac1() {
        when(jdbcTemplate.update(anyString(), any(Timestamp.class))).thenReturn(123);

        job.purgeExpiredIdempotencyRecords();

        verify(jdbcTemplate, times(1)).update(anyString(), any(Timestamp.class));
    }

    /** [AC-1] 单轮有 200 批硬上限：持续批满时最多 10 万行，剩余留给下个半周期（防御异常积压拖死调度）。 */
    @Test
    void purgeCapsBatchesPerRound_ac1() {
        when(jdbcTemplate.update(anyString(), any(Timestamp.class))).thenReturn(500);

        job.purgeExpiredIdempotencyRecords();

        verify(jdbcTemplate, times(200)).update(anyString(), any(Timestamp.class));
    }

    /** [AC-1] 数据库不可用时失败关闭不上抛：只记日志，等下个周期重试（与分区维护同口径）。 */
    @Test
    void purgeSwallowsDatabaseFailure_ac1() {
        when(jdbcTemplate.update(anyString(), any(Timestamp.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> job.purgeExpiredIdempotencyRecords()).doesNotThrowAnyException();
    }

    /** [AC-1] 零清理量不打日志：半周期空跑是常态，不制造日志噪音（删 0 行时 update 仍会执行一次）。 */
    @Test
    void purgeIssuesSingleDeleteEvenWhenNothingExpired_ac1() {
        when(jdbcTemplate.update(anyString(), any(Timestamp.class))).thenReturn(0);

        job.purgeExpiredIdempotencyRecords();

        verify(jdbcTemplate, times(1)).update(anyString(), any(Timestamp.class));
    }

    /** [AC-1] 调度注册断言：两个 @Scheduled 方法在位，幂等清理为半小时级 fixedDelay。 */
    @Test
    void scheduledMethodsAreRegistered_ac1() throws Exception {
        var maintain = PartitionMaintenanceJob.class.getMethod("maintain");
        var purge = PartitionMaintenanceJob.class.getMethod("purgeExpiredIdempotencyRecords");

        assertThat(maintain.isAnnotationPresent(Scheduled.class)).isTrue();
        Scheduled purgeSchedule = purge.getAnnotation(Scheduled.class);
        assertThat(purgeSchedule).isNotNull();
        // fixedDelay 30 分钟：串行调度不与自身重叠，是可重入的一部分。
        assertThat(purgeSchedule.fixedDelay()).isEqualTo(30 * 60 * 1000L);
    }
}
