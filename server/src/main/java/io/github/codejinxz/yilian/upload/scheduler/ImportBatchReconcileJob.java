package io.github.codejinxz.yilian.upload.scheduler;

import io.github.codejinxz.yilian.upload.service.ImportBatchService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 导入批次对账任务：定时收敛残留 COMMITTING 状态的批次（015 缺陷 #3 的恢复面）。
 *
 * <p>新版本的 commit 是单原子事务，COMMITTING 状态只存在于未提交事务内，对外不可见； 这里兜的是历史遗留的残留行（旧版分事务提交中断）。 启动后 2
 * 分钟首跑（覆盖「启动对账」），此后每 30 分钟一次；收敛动作与判定口径见 {@link
 * ImportBatchService#reconcileCommittingBatches()}。任务可重入：单批独立事务 + 批次行锁， 重复执行只会收敛一次。 集成测试用 {@code
 * yilian.import.reconcile-enabled=false} 关闭，避免与对账断言产生计数竞态。
 */
@Component
@ConditionalOnProperty(
        name = "yilian.import.reconcile-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ImportBatchReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(ImportBatchReconcileJob.class);

    private final ImportBatchService importBatchService;

    /**
     * @param importBatchService 导入批次服务
     */
    public ImportBatchReconcileJob(ImportBatchService importBatchService) {
        this.importBatchService = importBatchService;
    }

    /** 定时对账；收敛数大于 0 时告警级日志（自动化可见，AGENTS 规则 9）。 */
    @Scheduled(initialDelay = 2 * 60 * 1000L, fixedDelay = 30 * 60 * 1000L)
    public void reconcile() {
        int reconciled = importBatchService.reconcileCommittingBatches();
        if (reconciled > 0) {
            log.warn("import_batch_reconcile_job reconciled={}", reconciled);
        }
    }
}
