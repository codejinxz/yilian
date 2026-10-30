package io.github.codejinxz.yilian.practice.controller;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.ratelimit.RateLimit;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.AppealRequest;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.dto.DisputeRequest;
import io.github.codejinxz.yilian.practice.service.dto.PostponeRequest;
import io.github.codejinxz.yilian.practice.service.dto.ReviewResetRequest;
import io.github.codejinxz.yilian.practice.service.vo.AppealView;
import io.github.codejinxz.yilian.practice.service.vo.AttemptView;
import io.github.codejinxz.yilian.practice.service.vo.NextPracticeView;
import io.github.codejinxz.yilian.practice.service.vo.OverloadView;
import io.github.codejinxz.yilian.practice.service.vo.PostponeView;
import io.github.codejinxz.yilian.practice.service.vo.ReviewCardView;
import io.github.codejinxz.yilian.practice.service.vo.TodayView;
import io.github.codejinxz.yilian.practice.service.vo.WrongQuestionView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 练习接口层：只做参数校验、调用 {@link PracticeService} 并组装 {@link ApiResponse}。
 *
 * <p>提交接口按用户限流 60 次/分钟（离线攒批重放也在该额度内）；判分与排程全部在服务端完成， 客户端本地判分只做即时反馈。
 */
@Tag(name = "练习", description = "作答提交（判分 + FSRS 排程）、作答详情与复习队列外围。")
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

    /**
     * 提交批注申诉（「我觉得也对」）；M2 记录 PENDING，M3 接 LLM 裁决。
     *
     * @param attemptId 提交 id
     * @param annotationKey 批注键（slot:N / free:0）
     * @param request 建议答案与理由，均可空
     * @param principal 当前用户
     * @return 申诉回执
     */
    @Operation(
            summary = "提交批注申诉",
            description =
                    "key 必须存在于该提交最新判分的批注列表（否则 40000）；同一 (attempt, key, kind)"
                            + " 只能提交一次（40900）；M2 只记录 PENDING，不改判分与题目。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "返回 AppealView（status=PENDING）"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "批注不存在或字段超长（40000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "提交不属于当前用户（40300）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "答题记录不存在（40403）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "该批注已提交过同类记录（40900）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping("/attempts/{attemptId}/annotations/{annotationKey}/appeal")
    public ApiResponse<AppealView> submitAppeal(
            @PathVariable String attemptId,
            @PathVariable String annotationKey,
            @Valid @RequestBody(required = false) AppealRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(
                practiceService.submitAppeal(
                        principal.id(),
                        attemptId,
                        annotationKey,
                        request == null ? null : request.proposedAnswer(),
                        request == null ? null : request.reason()));
    }

    /**
     * 提交不认同（「不认同」该批注）：立即 ACCEPTED，错题与统计按 overlay 排除，不改题目。
     *
     * @param attemptId 提交 id
     * @param annotationKey 批注键（slot:N / free:0）
     * @param request 理由，可空
     * @param principal 当前用户
     * @return 回执
     */
    @Operation(
            summary = "提交批注不认同",
            description =
                    "status=ACCEPTED、decided_by=USER：该批注立即从错题列表与考点统计排除；"
                            + "规则可见、可撤销（重练改对即自然失效），不修改 sentence_versions。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "返回 AppealView（status=ACCEPTED）"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "批注不存在或字段超长（40000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "提交不属于当前用户（40300）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "答题记录不存在（40403）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "该批注已提交过同类记录（40900）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping("/attempts/{attemptId}/annotations/{annotationKey}/dispute")
    public ApiResponse<AppealView> submitDispute(
            @PathVariable String attemptId,
            @PathVariable String annotationKey,
            @Valid @RequestBody(required = false) DisputeRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(
                practiceService.submitDispute(
                        principal.id(),
                        attemptId,
                        annotationKey,
                        request == null ? null : request.reason()));
    }

    /**
     * 取下一题：优先到期复习，其次新题；没有可练题时 data=null（HTTP 200，不是 404）。
     *
     * @param bankId 题库过滤，可空
     * @param mode 模式过滤（CLOZE/FREE）；Q1 一句一卡下选题不按模式区分，仅做取值校验
     * @param excludeSentenceId 排除题目，可空
     * @param principal 当前用户
     * @return 下一题视图；无题时 data 为 null
     */
    @Operation(
            summary = "取下一题",
            description =
                    "优先到期复习（selection=DUE），其次新题（selection=NEW）；" + "没有可练题时 data=null，客户端显示空状态。")
    @GetMapping("/next")
    public ApiResponse<NextPracticeView> next(
            @RequestParam(required = false) Long bankId,
            @RequestParam(required = false) String mode,
            @RequestParam(name = "excludeSentenceId", required = false) String excludeSentenceId,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        requireValidMode(mode);
        return ApiResponse.success(practiceService.next(principal.id(), bankId, excludeSentenceId));
    }

    /**
     * 错题列表：LEARNING 卡 + 最近判分未全对；disputed 批注按参数过滤。
     *
     * @param page 页码
     * @param size 每页大小
     * @param includeDisputed 是否包含已被 DISPUTE 的批注，默认 false
     * @param bankId 题库过滤，可空
     * @param tag 考点过滤，可空
     * @param principal 当前用户
     * @return 分页错题
     */
    @Operation(summary = "错题列表", description = "按到期时间排序；includeDisputed=false 时排除已被用户「不认同」的批注。")
    @GetMapping("/wrong-questions")
    public ApiResponse<PageResponse<WrongQuestionView>> wrongQuestions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "includeDisputed", defaultValue = "false") boolean includeDisputed,
            @RequestParam(required = false) Long bankId,
            @RequestParam(required = false) String tag,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        requireValidPagination(page, size);
        return ApiResponse.success(
                practiceService.wrongQuestions(
                        principal.id(), page, size, includeDisputed, bankId, tag));
    }

    /**
     * 复习队列：按 dueAt 升序；可按三档状态/模式/是否到期过滤。
     *
     * @param page 页码
     * @param size 每页大小
     * @param state 三档状态过滤（NEW/LEARNING/MASTERED），可空
     * @param mode 模式过滤，可空
     * @param due true 时只看到期卡
     * @param principal 当前用户
     * @return 分页复习卡
     */
    @Operation(summary = "复习队列", description = "dueAt 升序；state 是三档投影（DATA-MODEL §8.4）。")
    @GetMapping("/reviews")
    public ApiResponse<PageResponse<ReviewCardView>> reviews(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String mode,
            @RequestParam(defaultValue = "false") boolean due,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        requireValidPagination(page, size);
        requireValidMode(mode);
        return ApiResponse.success(
                practiceService.reviews(principal.id(), page, size, state, mode, due));
    }

    /**
     * 重置复习卡为 NEW（「重新学」）；保留历史日志，不写 review_logs。
     *
     * @param reviewId 卡 id
     * @param request 重置理由，可空
     * @param principal 当前用户
     * @return 重置后的卡视图
     */
    @Operation(summary = "重置复习卡", description = "state=NEW、清零排程痕迹；不写 review_logs，理由进结构化日志。")
    @PostMapping("/reviews/{reviewId}/reset")
    public ApiResponse<ReviewCardView> resetReview(
            @PathVariable Long reviewId,
            @Valid @RequestBody(required = false) ReviewResetRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(
                practiceService.resetReview(
                        principal.id(), reviewId, request == null ? null : request.reason()));
    }

    /**
     * 积压降载状态：到期数超阈值时客户端只推最早到期的 pushLimit 道。
     *
     * @param principal 当前用户
     * @return 降载视图
     */
    @Operation(summary = "积压降载状态", description = "overload=true 表示到期数超过阈值，取题只推 pushLimit 道。")
    @GetMapping("/reviews/overload")
    public ApiResponse<OverloadView> overload(
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(practiceService.overload(principal.id()));
    }

    /**
     * 顺延：把推送上限之外的到期卡整体推迟一天；用户动作，不写 review_logs。
     *
     * @param request 顺延理由，可空
     * @param principal 当前用户
     * @return 顺延结果
     */
    @Operation(summary = "顺延到期卡", description = "保留最早到期的 5 道，其余推迟一天；所有顺延可见、可撤销（重练即恢复）、有理由。")
    @PostMapping("/reviews/postpone")
    public ApiResponse<PostponeView> postpone(
            @Valid @RequestBody(required = false) PostponeRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(
                practiceService.postpone(
                        principal.id(), request == null ? null : request.reason()));
    }

    /**
     * 今日启动台数字：用户时区本地日界。
     *
     * @param principal 当前用户
     * @return 今日概览
     */
    @Operation(
            summary = "今日概览",
            description = "dueCount/doneCount 按用户本地日界；streakDays 由 attempts 派生。")
    @GetMapping("/today")
    public ApiResponse<TodayView> today(@AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(practiceService.today(principal.id()));
    }

    /**
     * 分页参数白名单校验。
     *
     * @param page 页码
     * @param size 每页大小
     */
    private void requireValidPagination(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ApiCode.INVALID_PAGINATION);
        }
    }

    /**
     * mode 取值校验（CLOZE/FREE）；Q1 一句一卡下不参与选题，仅挡非法值。
     *
     * @param mode 模式字面量，可空
     */
    private void requireValidMode(String mode) {
        if (mode != null && !mode.isBlank() && !"CLOZE".equals(mode) && !"FREE".equals(mode)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "mode 取值不合法");
        }
    }
}
