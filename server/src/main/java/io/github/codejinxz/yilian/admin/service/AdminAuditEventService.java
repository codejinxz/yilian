package io.github.codejinxz.yilian.admin.service;

import io.github.codejinxz.yilian.admin.service.dto.AdminAuditEventRequest;

/**
 * 审计事件用例（API §8.12）：把演示动作（重置密码/导出数据/模型配置/运维吊销等）写入操作日志。
 *
 * <p>动作名走白名单——这些动作目前没有专属服务端写端点，若不设白名单， 任意字符串都能污染只增不改的审计表。
 */
public interface AdminAuditEventService {

    /**
     * 记录一条管理端审计事件；actor 为当前管理员，写入行 module=admin、outcome=SUCCESS。
     *
     * @param actorId 操作管理员 id
     * @param actorName 操作管理员用户名
     * @param actorRole 操作管理员角色
     * @param request 动作、对象与原因
     * @return 新审计行 id
     * @throws io.github.codejinxz.yilian.common.api.BusinessException action 缺失或不在白名单（40000）
     */
    long recordEvent(
            Long actorId, String actorName, String actorRole, AdminAuditEventRequest request);

    /** 查看答题原文的审计动作（API §8.7；固定值，不进演示白名单）。 */
    String ACTION_VIEW_ATTEMPT_RAW = "VIEW_ATTEMPT_RAW";

    /** 查看答题原文审计的 targetType 固定值。 */
    String TARGET_TYPE_ATTEMPT = "ATTEMPT";

    /**
     * 记录一次「查看答题原文」审计（API §8.7）：动作与对象类型固定，供原文端点拿到审计行 id。
     *
     * <p>独立于 {@link #recordEvent} 的白名单入口——原文查看是服务端专属用例， 若放进通用白名单，演示入口就能伪造查看痕迹。
     *
     * @param actorId 操作管理员 id
     * @param actorName 操作管理员用户名
     * @param actorRole 操作管理员角色
     * @param attemptId 被查看的答题提交 id（targetId）
     * @param reason 查看原因（原文默认不可见红线要求必填）
     * @return 新审计行 id
     */
    long recordViewAttemptRaw(
            Long actorId, String actorName, String actorRole, String attemptId, String reason);
}
