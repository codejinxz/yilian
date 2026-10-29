package io.github.codejinxz.yilian.auth.dto;

import io.github.codejinxz.yilian.system.service.dto.UserSettingsPatch;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
        @Schema(description = "IANA 时区", example = "Asia/Shanghai")
                @Size(max = 64, message = "时区标识最长 64 个字符")
                String timezone,
        @Schema(description = "每日目标 1/3/5", example = "3")
                @Min(value = 1, message = "每日目标最小为 1")
                @Max(value = 5, message = "每日目标最大为 5")
                Integer dailyGoal,
        @Schema(description = "评分标准", example = "TEM8")
                @Pattern(regexp = "TEM8|CATTI|IELTS", message = "评分标准只支持 TEM8、CATTI 或 IELTS")
                String ratingStandard,
        @Schema(description = "期望保留率 0.800–0.970", example = "0.9")
                @DecimalMin(value = "0.800", message = "期望保留率最小为 0.800")
                @DecimalMax(value = "0.970", message = "期望保留率最大为 0.970")
                @Digits(integer = 1, fraction = 3, message = "期望保留率最多 3 位小数")
                BigDecimal desiredRetention,
        @Schema(description = "主题 paper/night", example = "paper")
                @Pattern(regexp = "paper|night", message = "主题只支持 paper 或 night")
                String theme,
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
