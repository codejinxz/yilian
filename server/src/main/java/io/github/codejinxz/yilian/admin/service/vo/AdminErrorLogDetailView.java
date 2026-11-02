package io.github.codejinxz.yilian.admin.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 异常日志详情（响应）：在列表行之上追加 {@code stackTrace}，堆栈只进后台详情抽屉。
 *
 * <p>堆栈入库前已做敏感字段遮盖与长度截断（16KB），本视图不再二次处理。
 *
 * @param id 异常行 id
 * @param traceId 链路追踪 id
 * @param module 业务模块
 * @param exceptionClass 异常类全名
 * @param message 脱敏后的异常消息，可为 null
 * @param requestMethod 请求方法，可为 null
 * @param requestPath 请求路径，可为 null
 * @param userId 脱敏后的用户 id，未认证为 null
 * @param operator 操作者用户名快照，可为 null
 * @param clientIp 客户端 IP，可为 null
 * @param createdAt 发生时间（UTC）
 * @param stackTrace 脱敏后的完整堆栈，可为 null
 */
@Schema(description = "异常日志详情（含堆栈）")
public record AdminErrorLogDetailView(
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
        @Schema(description = "发生时间（UTC）") Instant createdAt,
        @Schema(description = "脱敏后的完整堆栈") String stackTrace) {}
