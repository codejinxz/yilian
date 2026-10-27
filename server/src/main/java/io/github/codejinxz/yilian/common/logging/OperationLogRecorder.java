package io.github.codejinxz.yilian.common.logging;

/**
 * 操作日志落库入口。
 *
 * <p>由 {@link OperationLogAspect} 在标注方法结束后调用；实现必须自行吞掉持久化失败，
 * 不能因为审计表不可用而影响原始业务响应。可选审计字段未声明且上下文未提供时切面透传空串， 落库实现负责归一为 NULL。
 */
public interface OperationLogRecorder {

    /**
     * 记录一次操作结果。
     *
     * @param operation 方法上的操作注解
     * @param targetType 被操作对象类型；未声明时为空串
     * @param targetId 被操作对象标识；未声明时为空串
     * @param reason 审计原因；未声明时为空串
     * @param outcome {@code SUCCESS} 或 {@code FAILURE}
     * @param durationMs 方法耗时毫秒
     * @param failure 原始异常，成功时为 null
     * @param actorId 操作者真实 id；系统任务为 null
     * @param actorName 操作者用户名快照；未知时为 unknown
     * @param actorRole 操作者角色快照；系统任务为 SYSTEM
     */
    void record(
            OperationLog operation,
            String targetType,
            String targetId,
            String reason,
            String outcome,
            long durationMs,
            Throwable failure,
            Long actorId,
            String actorName,
            String actorRole);
}
