package io.github.codejinxz.yilian.practice.controller;

import io.github.codejinxz.yilian.common.api.ApiResponse;

import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;

import io.github.codejinxz.yilian.practice.service.PracticeService;

import io.github.codejinxz.yilian.practice.service.vo.AccuracyView;

import io.github.codejinxz.yilian.practice.service.vo.ErrorTagsView;

import io.swagger.v3.oas.annotations.Operation;

import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.web.bind.annotation.GetMapping;

import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RequestParam;

import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 统计接口层：正确率与考点错误分布；只读，聚合口径见 {@link PracticeService}。
 *
 * <p>from/to 是用户本地日期（ISO yyyy-MM-dd），日界换算在 Service 内按用户时区完成。
 */
@Tag(name = "统计", description = "正确率（含逐日与最近提交）与考点错误分布。")
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final PracticeService practiceService;

    /**
     * 只依赖 Service 接口，具体实现由 Spring 注入。
     *
     * @param practiceService 练习用例服务
     */
    public AnalyticsController(PracticeService practiceService) {
        this.practiceService = practiceService;
    }

    /**
     * 正确率统计（含 daily14/recent 扩展）。
     *
     * @param from 起始本地日期，可空（缺省最近 14 天）
     * @param to 截止本地日期，可空（缺省今天）
     * @param bankId 题库过滤，可空
     * @param mode 模式过滤，可空
     * @param principal 当前用户
     * @return 统计视图
     */
    @Operation(summary = "正确率统计", description = "correctRate/averageScore 以最新判分的有分数记录为口径；daily14 补零输出完整 14 天。")
    @GetMapping("/accuracy")
    public ApiResponse<AccuracyView> accuracy(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(required = false) Long bankId,
            @RequestParam(required = false) String mode,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(
                practiceService.accuracy(principal.id(), from, to, bankId, mode));
    }

    /**
     * 考点错误分布：判分批注 tag 聚合，dispute 批注不计。
     *
     * @param from 起始本地日期，可空
     * @param to 截止本地日期，可空
     * @param principal 当前用户
     * @return 考点分布
     */
    @Operation(summary = "考点错误分布", description = "来自判分批注的 tag 聚合；已 DISPUTE 的批注不计入。")
    @GetMapping("/error-tags")
    public ApiResponse<ErrorTagsView> errorTags(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(practiceService.errorTags(principal.id(), from, to));
    }
}
