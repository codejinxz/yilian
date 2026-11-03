package io.github.codejinxz.yilian.practice.controller;

import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.ratelimit.RateLimit;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.TutorService;
import io.github.codejinxz.yilian.practice.service.dto.AskQuestionRequest;
import io.github.codejinxz.yilian.practice.service.vo.TutorAskView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题目答疑接口（017，API §6.12）：只做参数校验、调用 {@link TutorService} 并组装 {@link ApiResponse}。
 *
 * <p>答疑按账号限流 5 次/分钟（成本护栏）；回答带固定免责标注，不参与判分与学习闭环。
 */
@Tag(name = "练习", description = "作答提交（判分 + FSRS 排程）、作答详情、复习队列外围与题目答疑。")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/practice")
public class AskController {

    private final TutorService tutorService;

    /**
     * 只依赖 Service 接口，具体实现由 Spring 注入。
     *
     * @param tutorService 答疑用例服务
     */
    public AskController(TutorService tutorService) {
        this.tutorService = tutorService;
    }

    /**
     * 就当前句提问。
     *
     * @param sentenceId 题目 id
     * @param request 已校验的问题（1-500 字符）
     * @param principal 当前用户
     * @return 回答与免责标注
     */
    @Operation(
            summary = "题目答疑",
            description =
                    "被动一问一答（非 AI 外教）：上下文为当前句中文、考点与本人最近批注，不含参考译文。"
                            + "未配置模型凭证返回 40400（功能不存在）；已配置未开启返回 40304；"
                            + "模型调用失败统一降级 50000「答疑暂时不可用」。回答必带 disclaimer。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "回答成功；data 为 TutorAskView"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "问题缺失或超过 500 字符（code=40000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "未登录（40100）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "答疑未开放（40304）或句子不可见（40300）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "题目不存在（40402）或功能未配置（40400）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "触发限流（42900，5 次/分钟/账号），带 Retry-After",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "500",
                description = "模型调用失败降级（50000，文案「答疑暂时不可用」）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @RateLimit(
            scene = "tutor-ask",
            dimensions = RateLimitDimension.ACCOUNT,
            limit = 5,
            windowSeconds = 60)
    @PostMapping("/{sentenceId}/ask")
    public ApiResponse<TutorAskView> ask(
            @PathVariable("sentenceId") String sentenceId,
            @Valid @RequestBody AskQuestionRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(
                tutorService.ask(principal.id(), sentenceId, request.question()));
    }
}
