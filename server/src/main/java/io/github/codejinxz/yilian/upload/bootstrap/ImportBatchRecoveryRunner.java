package io.github.codejinxz.yilian.upload.bootstrap;

import io.github.codejinxz.yilian.upload.service.ImportBatchService;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动恢复钩子：只负责在容器就绪后触发一次遗留批次恢复，状态判断与持久化都在服务实现里。
 *
 * <p>导入任务只存在进程内线程池，进程退出后不会自动恢复；恢复由 {@link ImportBatchService#recoverInterrupted()} 完成，本类不直接接触
 * DAO，避免把启动编排和持久化细节绑在一起。
 *
 * <p>该实现依赖单实例部署（AGENTS.md 明确不做多副本）：启动时不会有其他实例正在写同一批次。 若将来多副本，应改为带实例标识的租约或数据库状态 CAS。
 *
 * <p>恢复查询绑定旧 {@code import_batch} 表结构（V6 已重建为 {@code import_batches}，见 upload→imports 重构）；imports
 * 切片完成前， 本地真实联调可用 {@code yilian.upload.recovery.enabled=false} 关闭本钩子启动，默认开启不影响既有行为。
 */
@Component
@ConditionalOnProperty(
        name = "yilian.upload.recovery.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ImportBatchRecoveryRunner implements ApplicationRunner {

    private final ImportBatchService importBatchService;

    /**
     * 只依赖 Service 接口，便于测试替换。
     *
     * @param importBatchService 导入批次服务
     */
    public ImportBatchRecoveryRunner(ImportBatchService importBatchService) {
        this.importBatchService = importBatchService;
    }

    /**
     * 触发一次遗留批次恢复；单个批次失败已由服务内记日志，不阻塞应用启动。
     *
     * @param args 启动参数，未使用
     */
    @Override
    public void run(ApplicationArguments args) {
        importBatchService.recoverInterrupted();
    }
}
