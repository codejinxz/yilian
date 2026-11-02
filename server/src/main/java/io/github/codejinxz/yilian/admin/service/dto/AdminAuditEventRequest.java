package io.github.codejinxz.yilian.admin.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 审计事件请求体（API §8.12）：演示动作的通用审计入口。
 *
 * @param action 审计动作，必填且必须在白名单内
 * @param targetType 对象类型，可空
 * @param targetId 对象标识，可空
 * @param reason 操作原因，可空
 */
@Schema(description = "审计事件请求")
public record AdminAuditEventRequest(
        @Schema(description = "审计动作") String action,
        @Schema(description = "对象类型，可空") String targetType,
        @Schema(description = "对象标识，可空") String targetId,
        @Schema(description = "操作原因，可空") String reason) {}
