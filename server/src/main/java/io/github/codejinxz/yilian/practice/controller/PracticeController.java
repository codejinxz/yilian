package io.github.codejinxz.yilian.practice.controller;

import io.github.codejinxz.yilian.common.api.ApiResponse;

import io.github.codejinxz.yilian.common.ratelimit.RateLimit;

import io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension;

import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;

import io.github.codejinxz.yilian.practice.service.PracticeService;

import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;

import io.github.codejinxz.yilian.practice.service.vo.AttemptView;

import io.swagger.v3.oas.annotations.Operation;

import io.swagger.v3.oas.annotations.media.Content;

import io.swagger.v3.oas.annotations.media.Schema;

import io.swagger.v3.oas.annotations.responses.ApiResponses;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.web.bind.annotation.GetMapping;

import org.springframework.web.bind.annotation.PathVariable;

import org.springframework.web.bind.annotation.PostMapping;

import org.springframework.web.bind.annotation.RequestBody;

import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * 练习接口层：只做参数校验、调用 {@link PracticeService} 并组装 {@link ApiResponse}。
 *
 * <p>提交接口按用户限流 60 次/分钟（离线攒批重放也在该额度内）；判分与排程全部在服务端完成，
 * 客户端本地判分只做即时反馈。
 */
@Tag(name = "练习", description = "作答提交（判分 + FSRS 排程）与作答详情。")
@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    private final PracticeService practiceService;

    /**
     * 只依赖 Service 接口，具体实现由 Spring 注入。
     *
     * @param practiceService 练习用例服务
     */
    public PracticeController(PracticeService practiceService) {
        this.practiceService = practiceService;
    }

    /**
     * 提交一次作答；幂等；判分；更新 FSRS。
     *
     * @param command 已校验的提交命令
     * @param principal 当前用户
     * @return 判分与排程视图
     */
    @Operation(
            summary = "提交作答",
            description =
                    "attemptId 为客户端生成的幂等键：同 id 同载荷重放返回原结果（replayed=true），"
                            + "异载荷返回 40903。score<100 且 kind=FIRST 时必须重打或看答案才能下一题；"
                            + "只有 FIRST 与「没有 FIRST 的 REVEAL」触发排程，RETRY 不排程。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "提交成功（含幂等重放）；data 为 AttemptView"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "参数校验失败或请求体不合法（code=40000/40001）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "题目不存在/版本不一致（40402）或答题记录不存在（40403）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "attemptId 被其他用户或不同载荷复用（40903）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "500",
                description = "未预期异常（50000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @RateLimit(
            scene = "practice-attempt",
            dimensions = RateLimitDimension.ACCOUNT,
            limit = 60,
            windowSeconds = 60)
    @PostMapping("/attempts")
    public ApiResponse<AttemptView> submitAttempt(
            @Valid @RequestBody AttemptCommand command,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(practiceService.submitAttempt(principal.id(), command));
    }

    /**
     * 批改结果详情/刷新恢复；只能看自己的。
     *
     * @param attemptId 提交 id
     * @param principal 当前用户
     * @return 与提交响应同构的视图
     */
    @Operation(summary = "作答详情", description = "按 attemptId 读取判分结果与排程投影；不属于当前用户按 40403 返回。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "返回 AttemptView"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "答题记录不存在或不属于当前用户（40403）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "500",
                description = "未预期异常（50000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/attempts/{attemptId}")
    public ApiResponse<AttemptView> getAttempt(
            @PathVariable String attemptId,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(practiceService.getAttempt(principal.id(), attemptId));
    }
}
