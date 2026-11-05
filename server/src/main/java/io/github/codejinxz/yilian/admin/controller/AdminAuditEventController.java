package io.github.codejinxz.yilian.admin.controller;

import io.github.codejinxz.yilian.admin.service.AdminAuditEventService;
import io.github.codejinxz.yilian.admin.service.dto.AdminAuditEventRequest;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端审计事件入口（/api/admin/audit-events，API §8.12）：把演示动作写入操作日志。
 *
 * <p>本端点刻意不标 {@code @OperationLog}：它的产出就是一条审计行（action 随请求体动态变化，
 * 注解写不了运行期值），再标一次会产生动作名相同的重复行。白名单校验在服务层， 任意字符串不能污染只增不改的审计表。
 */
@RestController
@RequestMapping("/api/admin/audit-events")
@Tag(name = "admin/audit-events", description = "管理端审计事件（演示动作通用入口）")
public class AdminAuditEventController {

    private final AdminAuditEventService adminAuditEventService;

    /**
     * @param adminAuditEventService 审计事件服务
     */
    public AdminAuditEventController(AdminAuditEventService adminAuditEventService) {
        this.adminAuditEventService = adminAuditEventService;
    }

    /**
     * 记录一条审计事件；动作必须在白名单内。
     *
     * @param request 动作、对象与原因
     * @param actor 当前管理员
     * @return 新审计行 id（字符串化，与前端 {@code appendAuditEvent} 的解析契约一致）
     */
    @Operation(
            summary = "记录审计事件",
            description =
                    "action 白名单：EXPORT_USER_DATA/SESSION_REVOKE/MODEL_CONFIG_UPDATE/MODEL_TOGGLE/PROMPT_UPDATE；"
                            + "重置密码已迁移到真端点 POST /api/admin/users/{id}/reset-password，不在本入口白名单内。")
    @PostMapping
    public ApiResponse<Map<String, String>> record(
            @RequestBody AdminAuditEventRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        long id =
                adminAuditEventService.recordEvent(
                        actor.id(), actor.getUsername(), actor.role(), request);
        // 前端 appendAuditEvent 以 z.string() 解析 id，字符串化而不是数字。
        return ApiResponse.success(Map.of("id", Long.toString(id)));
    }
}
