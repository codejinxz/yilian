package io.github.codejinxz.yilian.upload.entity;

/**
 * 导入批次状态。
 *
 * <p>状态机：{@link #UPLOADED} 在文件落对象存储并建批次后进入；异步任务开始后转 {@link #PROCESSING}；正常读完文件转 {@link
 * #COMPLETED}；发生不可恢复错误（存储读失败、 进度持久化失败等）转 {@link #FAILED}。服务重启时遗留的 {@link #PROCESSING} 由启动钩子转 {@link
 * #PAUSED}，等待管理员调用 resume 续跑。
 *
 * <p>行级校验失败（空行、超长、非法 JSON 行）只累加 {@code failedRows}，不改变批次状态： 这与 PRD「50 句导入、3 行标红、47 句入库」的语义一致。{@link
 * #COMPLETED} 是终态， 重复 resume 直接返回当前状态，不会再次读文件。
 */
public enum ImportBatchStatus {
    /** 文件已写入对象存储，批次已创建，异步任务尚未开始。 */
    UPLOADED,
    /** 异步任务正在读取并校验文件。 */
    PROCESSING,
    /** 进程重启后遗留的任务，或后续人工暂停；可从 resume_offset 续跑。 */
    PAUSED,
    /** 文件已读到末尾，计数已落库；终态。 */
    COMPLETED,
    /** 整批处理失败，可从 resume_offset 续跑；不是行级校验失败。 */
    FAILED
}
