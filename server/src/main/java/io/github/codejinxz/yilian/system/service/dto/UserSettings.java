package io.github.codejinxz.yilian.system.service.dto;

import io.github.codejinxz.yilian.system.entity.AppUser;

import java.math.BigDecimal;

/**
 * 用户设置只读视图，对应 API §4.9。
 *
 * @param timezone IANA 时区
 * @param dailyGoal 每日目标 1/3/5
 * @param ratingStandard 评分标准 TEM8/CATTI/IELTS
 * @param desiredRetention FSRS 期望保留率
 * @param theme paper/night
 * @param notificationsEnabled 是否开启通知
 */
public record UserSettings(
        String timezone,
        int dailyGoal,
        String ratingStandard,
        BigDecimal desiredRetention,
        String theme,
        boolean notificationsEnabled) {

    /**
     * 从实体抽取设置字段。
     *
     * @param user 账号实体
     * @return 设置视图
     */
    public static UserSettings from(AppUser user) {
        return new UserSettings(
                user.getTimezone(),
                user.getDailyGoal(),
                user.getRatingStandard(),
                user.getDesiredRetention(),
                user.getTheme(),
                user.isNotificationsEnabled());
    }
}
