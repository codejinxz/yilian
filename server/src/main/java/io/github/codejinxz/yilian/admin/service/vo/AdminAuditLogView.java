package io.github.codejinxz.yilian.admin.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 操作日志检索行（响应，MyBatis 直出 {@code admin_audit_log}）。
 *
 * <p>审计表只增不改，本视图为纯只读快照；不含 {@code detail} JSON 列（参数与请求体不进后台检索页）。
 *
 * @param id 审计行 id
 * @param module 业务模块（account/content/practice/ai/admin/analytics/system）
 * @param actorId 操作者用户 id，系统行为可为 null
 * @param actorName 操作者用户名快照
 * @param actorRole 操作者角色快照，可为 null
 * @param clientIp 客户端 IP，可为 null
 * @param action 动作标识，如 USER_STATUS_CHANGE
 * @param targetType 对象类型，可为 null
 * @param targetId 对象标识，可为 null
 * @param reason 操作原因，可为 null
 * @param outcome 执行结果 SUCCESS/FAILURE
 * @param durationMs 耗时毫秒，可为 null
 * @param createdAt 发生时间（UTC）
 */
@Schema(description = "操作日志行（不含 detail JSON 列）")
public record AdminAuditLogView(
        @Schema(description = "审计行 id") long id,
        @Schema(description = "业务模块") String module,
        @Schema(description = "操作者用户 id") Long actorId,
        @Schema(description = "操作者用户名快照") String actorName,
        @Schema(description = "操作者角色快照") String actorRole,
        @Schema(description = "客户端 IP") String clientIp,
        @Schema(description = "动作标识") String action,
        @Schema(description = "对象类型") String targetType,
        @Schema(description = "对象标识") String targetId,
        @Schema(description = "操作原因") String reason,
        @Schema(description = "执行结果 SUCCESS/FAILURE") String outcome,
        @Schema(description = "耗时毫秒") Integer durationMs,
        @Schema(description = "发生时间（UTC）") Instant createdAt) {}
