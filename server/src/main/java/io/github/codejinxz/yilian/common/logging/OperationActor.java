package io.github.codejinxz.yilian.common.logging;

/**
 * 审计日志中的操作者快照。
 *
 * <p>用户名与角色是写入时的快照：账号改名或角色变更后，历史日志仍应显示当时的值；{@code id} 为真实用户 id，系统任务为 null。
 *
 * @param id 用户 id；系统任务为 null
 * @param name 用户名快照；未知时为 unknown
 * @param role 角色快照；系统任务为 SYSTEM
 */
public record OperationActor(Long id, String name, String role) {

    /** 未认证/系统任务的占位操作者。 */
    public static final OperationActor SYSTEM = new OperationActor(null, "unknown", "SYSTEM");
}
