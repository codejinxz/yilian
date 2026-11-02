package io.github.codejinxz.yilian.admin.service.impl;

import io.github.codejinxz.yilian.admin.dao.AdminAuditEventDao;
import io.github.codejinxz.yilian.admin.dao.AdminAuditEventRow;
import io.github.codejinxz.yilian.admin.service.AdminAuditEventService;
import io.github.codejinxz.yilian.admin.service.dto.AdminAuditEventRequest;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.util.IdUtils;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.common.util.WebUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;

/**
 * 审计事件实现：白名单校验后单行 append 到 {@code admin_audit_log}。
 *
 * <p>traceId/client_ip/request_* 与 common 的 {@code JdbcOperationLogRecorder} 同源取值，
 * 保证审计行形状一致、可被同一张检索页消费。落库失败不吞（本端点的产出就是这条审计）， 随事务回滚并返回 500。
 */
@Service
public class AdminAuditEventServiceImpl implements AdminAuditEventService {

    /**
     * 动作白名单：前端 appendAuditEvent 的实际调用点（UsersPage/OpsPage/ModelsPage）。
     * 未来动作上线专属写端点后，从这里移除并改走对应的 @OperationLog。
     */
    static final Set<String> ALLOWED_ACTIONS =
            Set.of(
                    // UsersPage：用户抽屉的演示动作（无专属端点，先留审计痕迹）
                    "RESET_PASSWORD",
                    "EXPORT_USER_DATA",
                    // OpsPage：运维页会话吊销演示
                    "SESSION_REVOKE",
                    // ModelsPage：模型目录与 Prompt 模板演示动作
                    "MODEL_CONFIG_UPDATE",
                    "MODEL_TOGGLE",
                    "PROMPT_UPDATE");

    private static final int MAX_TEXT_LENGTH = 500;

    private final AdminAuditEventDao adminAuditEventDao;

    /**
     * @param adminAuditEventDao 审计事件写入 Dao
     */
    public AdminAuditEventServiceImpl(AdminAuditEventDao adminAuditEventDao) {
        this.adminAuditEventDao = adminAuditEventDao;
    }

    @Override
    @Transactional
    public long recordEvent(
            Long actorId, String actorName, String actorRole, AdminAuditEventRequest request) {
        String action = normalize(request == null ? null : request.action());
        if (action == null) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "缺少审计动作");
        }
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "不支持的审计动作: " + action);
        }
        HttpServletRequest httpRequest = currentRequest();
        return adminAuditEventDao.insert(
                new AdminAuditEventRow(
                        StringUtils.defaultIfBlank(MDC.get("traceId"), IdUtils.newCompactId()),
                        actorId,
                        StringUtils.truncate(StringUtils.defaultIfBlank(actorName, "unknown"), 64),
                        StringUtils.truncate(StringUtils.defaultIfBlank(actorRole, "SYSTEM"), 16),
                        StringUtils.truncate(WebUtils.clientIp(httpRequest), 45),
                        httpRequest == null ? null : httpRequest.getMethod(),
                        httpRequest == null
                                ? null
                                : StringUtils.truncate(httpRequest.getRequestURI(), 512),
                        action,
                        auditText(request.targetType(), 32),
                        auditText(request.targetId(), 64),
                        auditText(request.reason(), MAX_TEXT_LENGTH)));
    }

    /**
     * 文本归一：去空白、空白归 null、按列宽截断（与 JdbcOperationLogRecorder.auditText 同语义）。
     *
     * @param value 入参，可空
     * @param maxLength 列宽
     * @return 归一值
     */
    private static String auditText(String value, int maxLength) {
        return StringUtils.truncate(StringUtils.defaultIfBlank(value, null), maxLength);
    }

    /**
     * 动作名归一：去空白，空白归 null。
     *
     * @param value 入参，可空
     * @return 归一值
     */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 当前 HTTP 请求；非 Web 线程（测试直调）返回 null。
     *
     * @return 请求对象，可空
     */
    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
