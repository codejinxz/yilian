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
}
