package io.github.codejinxz.yilian.auth.vo;

import io.github.codejinxz.yilian.practice.service.vo.UserDataExportView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * 服务端账号数据导出视图（GET /api/me/export 的响应体，非统一信封——文件下载语义）。
 *
 * <p>整包含：账号块（id/登录名/邮箱/注册时间/设置）与练习域切片（attempts、全部判分行、 申诉/不认同、复习卡）。只含本人数据（AGENTS §2.6
 * 允许单用户、禁批量）；不含密码哈希、 TOTP 密钥材料、恢复码等凭据投影，也不含他人数据与题库内容。练习域行字段与 DATA-MODEL §5.8–5.11 对齐（camelCase
 * 投影），定义在 practice 模块的 {@link UserDataExportView}。
 *
 * @param schemaVersion 导出结构版本，当前 1
 * @param exportedAtUtc 导出时刻（UTC）
 * @param account 账号块
 * @param attempts 本人答题提交
 * @param attemptGradings 本人提交关联的全部判分行
 * @param appeals 本人申诉/不认同记录
 * @param reviewCards 本人复习卡（FSRS 当前投影）
 */
@Schema(description = "服务端账号数据导出；响应体为导出 JSON 本身（带 Content-Disposition 附件），非统一信封。")
public record AccountExportView(
        @Schema(description = "导出结构版本") int schemaVersion,
        @Schema(description = "导出时刻（UTC）") String exportedAtUtc,
        @Schema(description = "账号块") AccountBlock account,
        @Schema(description = "本人答题提交") List<UserDataExportView.AttemptLine> attempts,
        @Schema(description = "本人提交关联的全部判分行") List<UserDataExportView.GradingLine> attemptGradings,
        @Schema(description = "本人申诉/不认同记录") List<UserDataExportView.AppealLine> appeals,
        @Schema(description = "本人复习卡") List<UserDataExportView.ReviewCardLine> reviewCards) {

    /**
     * 账号块：身份投影与个人化设置。
     *
     * @param id 用户 id
     * @param username 登录名
     * @param email 邮箱，可为 null
     * @param createdAtUtc 注册时间（UTC）
     * @param settings 个人化设置
     */
    @Schema(description = "账号块：身份投影与个人化设置。")
    public record AccountBlock(
            @Schema(description = "用户 id") Long id,
            @Schema(description = "登录名") String username,
            @Schema(description = "邮箱") String email,
            @Schema(description = "注册时间（UTC）") String createdAtUtc,
            @Schema(description = "个人化设置") SettingsBlock settings) {}

    /**
     * 个人化设置块。
     *
     * @param timezone IANA 时区
     * @param dailyGoal 每日目标 1/3/5
     * @param ratingStandard 评分标准
     * @param desiredRetention FSRS 期望保留率
     * @param theme 主题
     * @param notificationsEnabled 通知开关
     */
    @Schema(description = "个人化设置块。")
    public record SettingsBlock(
            @Schema(description = "IANA 时区") String timezone,
            @Schema(description = "每日目标") int dailyGoal,
            @Schema(description = "评分标准") String ratingStandard,
            @Schema(description = "FSRS 期望保留率") BigDecimal desiredRetention,
            @Schema(description = "主题") String theme,
            @Schema(description = "通知开关") boolean notificationsEnabled) {}
}
