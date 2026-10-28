package io.github.codejinxz.yilian.upload.entity;

/**
 * 导入批次状态机，对应 {@code import_batches.status}（DATA-MODEL §5.6）。
 *
 * <p>主流转 {@code PREVIEWING → PREVIEWED → COMMITTING → COMMITTED}；任一步失败转 {@link #FAILED}； 已提交批次可回滚为
 * {@link #ROLLED_BACK}；超过 24 小时未提交的预览批次由清扫任务置 {@link #EXPIRED}。 合法迁移集中在 {@link ImportBatch}
 * 的方法内，调用方不得直接改 {@code status} 绕过校验。
 */
public enum ImportBatchStatus {

    /** 文件已接收，正在同步逐行校验；同请求结束时会落到 PREVIEWED 或 FAILED。 */
    PREVIEWING,

    /** 预览完成，行级校验结果已落 {@code import_batch_rows}，等待管理员提交。 */
    PREVIEWED,

    /** 提交事务进行中；乐观兜底状态，正常请求结束时落到 COMMITTED 或 FAILED。 */
    COMMITTING,

    /** 已入库；终态前唯一可继续迁移的状态（仅可转 ROLLED_BACK）。 */
    COMMITTED,

    /** 整批失败（解析/校验/提交异常）；终态，不提供续跑。 */
    FAILED,

    /** 已回滚；题目置 RECYCLED 软删，终态。 */
    ROLLED_BACK,

    /** 超期未提交被清扫；终态，可重新发起预览。 */
    EXPIRED;

    /**
     * 是否终态。
     *
     * @return FAILED / ROLLED_BACK / EXPIRED 返回 true；COMMITTED 仍可回滚，不算终态
     */
    public boolean isTerminal() {
        return this == FAILED || this == ROLLED_BACK || this == EXPIRED;
    }
}
