package io.github.codejinxz.yilian.auth.dto;

import io.github.codejinxz.yilian.system.service.dto.UserSettings;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 用户设置视图，对应 API §4.9。
 *
 * @param timezone IANA 时区
 * @param dailyGoal 每日目标
 * @param ratingStandard 评分标准
 * @param desiredRetention FSRS 期望保留率
 * @param theme paper/night
 * @param notificationsEnabled 是否开启通知
 */
@Schema(description = "用户设置视图；PUT 时所有字段可选，但至少提供一个。")
public record UserSettingsResponse(
        @Schema(description = "IANA 时区", example = "Asia/Shanghai") String timezone,
        @Schema(description = "每日目标 1/3/5", example = "3") int dailyGoal,
        @Schema(description = "评分标准 TEM8/CATTI/IELTS", example = "TEM8") String ratingStandard,
        @Schema(description = "FSRS 期望保留率 0.800–0.970", example = "0.9")
                BigDecimal desiredRetention,
        @Schema(description = "主题 paper/night", example = "paper") String theme,
        @Schema(description = "是否开启通知") boolean notificationsEnabled) {

    /**
     * 从 system 模块的设置 DTO 转换。
     *
     * @param settings 设置 DTO
     * @return 接口视图
     */
    public static UserSettingsResponse from(UserSettings settings) {
        return new UserSettingsResponse(
                settings.timezone(),
                settings.dailyGoal(),
                settings.ratingStandard(),
                settings.desiredRetention(),
                settings.theme(),
                settings.notificationsEnabled());
    }
}
