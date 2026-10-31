package io.github.codejinxz.yilian.admin.service.vo;

import java.util.List;

/**
 * 管理端概览聚合视图（API §8.1）：四项指标 + 近 7 天活跃 + 静态待办 + 最近管理活动。
 *
 * @param metrics 指标组
 * @param active7d 近 7 天每日活跃，从旧到新，缺数日期补 0
 * @param todos 待办引导；011-b 接入真实待办前为静态引导项
 * @param activity 最近管理活动，取 admin_audit_log 倒序 5 条
 */
public record AdminOverviewView(
        Metrics metrics,
        List<DailyActivity> active7d,
        List<TodoItem> todos,
        List<ActivityItem> activity) {

    /**
     * 概览指标组。
     *
     * @param totalUsers 注册用户总数（含已软删，口径为注册总量）
     * @param activeToday 当日活跃用户数
     * @param totalAttempts 答题总数
     * @param estimatedCost 模型调用估算成本；011-a 无模型调用恒为 0，011-b 接入后接真实口径
     */
    public record Metrics(
            long totalUsers, long activeToday, long totalAttempts, long estimatedCost) {}

    /**
     * 单日活跃计数。
     *
     * @param date 日期，{@code YYYY-MM-DD}（UTC）
     * @param count 当日活跃用户数
     */
    public record DailyActivity(String date, long count) {}

    /**
     * 待办引导项。
     *
     * @param id 稳定标识，便于前端跳转
     * @param title 待办标题
     * @param meta 补充说明
     * @param href 管理端跳转地址
     */
    public record TodoItem(String id, String title, String meta, String href) {}

    /**
     * 最近管理活动。
     *
     * @param id 审计行 id（字符串化，避免前端精度丢失）
     * @param who 操作者用户名快照
     * @param what 动作与对象描述
     * @param at 发生时间（ISO-8601，UTC）
     */
    public record ActivityItem(String id, String who, String what, String at) {}
}
