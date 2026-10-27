package io.github.codejinxz.yilian.auth.dto;

import io.github.codejinxz.yilian.system.service.dto.UserSettingsPatch;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 更新用户设置请求；所有字段可选，至少一个非空。
 *
 * @param timezone IANA 时区
 * @param dailyGoal 1/3/5
 * @param ratingStandard TEM8/CATTI/IELTS
 * @param desiredRetention 0.800–0.970
 * @param theme paper/night
 * @param notificationsEnabled 是否开启通知
 */
@Schema(description = "更新用户设置：所有字段可选，但至少提供一个。")
public record UserSettingsRequest(
        @Schema(description = "IANA 时区", example = "Asia/Shanghai") String timezone,
        @Schema(description = "每日目标 1/3/5", example = "3") Integer dailyGoal,
        @Schema(description = "评分标准", example = "TEM8") String ratingStandard,
        @Schema(description = "期望保留率 0.800–0.970", example = "0.9") BigDecimal desiredRetention,
        @Schema(description = "主题 paper/night", example = "paper") String theme,
        @Schema(description = "是否开启通知") Boolean notificationsEnabled) {

    /**
     * 转成 system 模块的补丁 DTO。
     *
     * @return 设置补丁
     */
    public UserSettingsPatch toPatch() {
        return new UserSettingsPatch(
                timezone, dailyGoal, ratingStandard, desiredRetention, theme, notificationsEnabled);
    }
}
