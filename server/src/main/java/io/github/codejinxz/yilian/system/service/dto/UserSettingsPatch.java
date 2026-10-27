package io.github.codejinxz.yilian.system.service.dto;

import java.math.BigDecimal;

/**
 * 更新用户设置的入参；null 表示保留原值，至少一个字段非空由服务层校验。
 *
 * @param timezone IANA 时区
 * @param dailyGoal 1/3/5
 * @param ratingStandard TEM8/CATTI/IELTS
 * @param desiredRetention 0.800–0.970
 * @param theme paper/night
 * @param notificationsEnabled 是否开启通知
 */
public record UserSettingsPatch(
        String timezone,
        Integer dailyGoal,
        String ratingStandard,
        BigDecimal desiredRetention,
        String theme,
        Boolean notificationsEnabled) {

    /**
     * 是否至少包含一个待更新字段。
     *
     * @return 全部为 null 时返回 false
     */
    public boolean isEmpty() {
        return timezone == null
                && dailyGoal == null
                && ratingStandard == null
                && desiredRetention == null
                && theme == null
                && notificationsEnabled == null;
    }
}
