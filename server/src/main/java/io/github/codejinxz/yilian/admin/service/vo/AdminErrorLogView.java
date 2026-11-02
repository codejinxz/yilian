package io.github.codejinxz.yilian.admin.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 异常日志检索行（响应，MyBatis 直出 {@code error_log}，列表剥离 stack_trace）。
 *
 * <p>堆栈可能长达 16KB，列表页不返回（前端 {@code ErrorsPage} 列表也不消费）， 只在详情端点 {@link AdminErrorLogDetailView} 出现。
 *
 * @param id 异常行 id
 * @param traceId 链路追踪 id
 * @param module 业务模块（account/content/practice/ai/admin/analytics/system）
 * @param exceptionClass 异常类全名
 * @param message 脱敏后的异常消息，可为 null
 * @param requestMethod 请求方法，可为 null
 * @param requestPath 请求路径，可为 null
 * @param userId 脱敏后的用户 id，未认证为 null
 * @param operator 操作者用户名快照，可为 null
 * @param clientIp 客户端 IP，可为 null
 * @param createdAt 发生时间（UTC）
 */
@Schema(description = "异常日志行（列表不含堆栈）")
public record AdminErrorLogView(
        @Schema(description = "异常行 id") long id,
        @Schema(description = "链路追踪 id") String traceId,
        @Schema(description = "业务模块") String module,
        @Schema(description = "异常类全名") String exceptionClass,
        @Schema(description = "脱敏后的异常消息") String message,
        @Schema(description = "请求方法") String requestMethod,
        @Schema(description = "请求路径") String requestPath,
        @Schema(description = "用户 id") Long userId,
        @Schema(description = "操作者用户名快照") String operator,
        @Schema(description = "客户端 IP") String clientIp,
        @Schema(description = "发生时间（UTC）") Instant createdAt) {}
