package io.github.codejinxz.yilian.admin.service;

import io.github.codejinxz.yilian.admin.service.vo.AdminAuditLogView;
import io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogDetailView;
import io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogView;
import io.github.codejinxz.yilian.common.api.PageResponse;

/**
 * 管理端日志检索用例（操作日志 + 异常日志，API §8.4–§8.6）；纯只读，不做任何状态流转。
 *
 * <p>两张表都是按月分区表（主键含 created_at），检索强制时间窗口兜底： from/to 缺省时按「近 30 天」收口，避免全分区扫描；列表刻意剥离 {@code
 * stack_trace}，堆栈只在详情出现。
 */
public interface AdminLogService {

    /**
     * 检索操作日志（{@code admin_audit_log}，只读倒序）。
     *
     * @param keyword 拼串模糊匹配 action/操作者/对象类型/对象 id/原因，空白表示不过滤
     * @param module 模块精确过滤，空白表示不过滤
     * @param action 动作精确过滤，空白表示不过滤
     * @param actorId 操作者 id 过滤，空白或非数字约定见实现（非法数字 40000）
     * @param from 窗口起点（YYYY-MM-DD，含当日），缺省回退近 30 天
     * @param to 窗口终点（YYYY-MM-DD，含当日），缺省回退当前时刻
     * @param page 页码，从 1 开始
     * @param size 每页条数，缺省 20、上限 100（越界归 100）
     * @return 分页操作日志
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 参数非法（40000）或分页越界（40004）
     */
    PageResponse<AdminAuditLogView> searchAuditLogs(
            String keyword,
            String module,
            String action,
            String actorId,
            String from,
            String to,
            int page,
            int size);

    /**
     * 检索异常日志（{@code error_log}，只读倒序；列表不含堆栈）。
     *
     * @param keyword 拼串模糊匹配异常类/消息/traceId/操作者，空白表示不过滤
     * @param module 模块精确过滤，空白表示不过滤
     * @param from 窗口起点（YYYY-MM-DD，含当日），缺省回退近 30 天
     * @param to 窗口终点（YYYY-MM-DD，含当日），缺省回退当前时刻
     * @param page 页码，从 1 开始
     * @param size 每页条数，缺省 20、上限 100（越界归 100）
     * @return 分页异常日志（不含 stackTrace）
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 参数非法（40000）或分页越界（40004）
     */
    PageResponse<AdminErrorLogView> searchErrorLogs(
            String keyword, String module, String from, String to, int page, int size);

    /**
     * 异常详情（含堆栈；堆栈只在此出现）。
     *
     * @param id 异常行 id
     * @return 详情视图
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 不存在（40400）
     */
    AdminErrorLogDetailView errorLogDetail(long id);
}
