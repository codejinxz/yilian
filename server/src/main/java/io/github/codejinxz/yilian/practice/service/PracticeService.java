package io.github.codejinxz.yilian.practice.service;

import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.dto.GuestUpgradeCommand;
import io.github.codejinxz.yilian.practice.service.dto.NextQuery;
import io.github.codejinxz.yilian.practice.service.vo.AccuracyView;
import io.github.codejinxz.yilian.practice.service.vo.AppealView;
import io.github.codejinxz.yilian.practice.service.vo.AttemptView;
import io.github.codejinxz.yilian.practice.service.vo.ErrorTagsView;
import io.github.codejinxz.yilian.practice.service.vo.GuestUpgradeView;
import io.github.codejinxz.yilian.practice.service.vo.NextPracticeView;
import io.github.codejinxz.yilian.practice.service.vo.OverloadView;
import io.github.codejinxz.yilian.practice.service.vo.PostponeView;
import io.github.codejinxz.yilian.practice.service.vo.ReviewCardView;
import io.github.codejinxz.yilian.practice.service.vo.TodayView;
import io.github.codejinxz.yilian.practice.service.vo.WrongQuestionView;

import org.springframework.lang.Nullable;

import java.time.LocalDate;

/**
 * 练习用例服务：作答提交（判分 + 幂等 + FSRS 排程）、作答详情与查询外围。
 *
 * <p>跨模块取题目内容只经 {@code ContentQueryService}；用户设置经 {@code UserAccountService}。
 */
public interface PracticeService {

    /**
     * 提交一次作答：校验题目与版本、幂等判定、本地判分、写 attempt_gradings、 按 SRS 四分支推进复习卡并写 review_logs。
     *
     * @param userId 当前用户 id
     * @param command 已校验的提交命令
     * @return 判分与排程视图；幂等重放时 replayed=true
     */
    AttemptView submitAttempt(Long userId, AttemptCommand command);

    /**
     * 查询作答详情（批改结果/刷新恢复）；只能看自己的。
     *
     * @param userId 当前用户 id
     * @param attemptId 提交 id
     * @return 与提交响应同构的视图
     */
    AttemptView getAttempt(Long userId, String attemptId);

    /**
     * 提交批注申诉（「我觉得也对」）：M2 只记录 PENDING 待裁决，M3 接 LLM。
     *
     * <p>提交必须属于当前用户（40403 不存在 / 40300 非本人）；批注 key 必须存在于该提交 最新判分结果的批注列表（40000）；同一 (attempt, key,
     * kind) 只能提交一次（40900）。
     *
     * @param userId 当前用户 id
     * @param attemptId 提交 id
     * @param annotationKey 批注键（slot:N / free:0）
     * @param proposedAnswer 用户认为可接受的答案，可空
     * @param reason 理由，可空
     * @return 申诉回执（status=PENDING）
     */
    AppealView submitAppeal(
            Long userId,
            String attemptId,
            String annotationKey,
            String proposedAnswer,
            String reason);

    /**
     * 提交不认同（「不认同」该批注）：立即 ACCEPTED，该批注从错题与统计按 overlay 排除， 不改题目内容。
     *
     * <p>归属与批注校验同 {@link #submitAppeal}；同一 (attempt, key, kind) 只能提交一次（40900）。
     *
     * @param userId 当前用户 id
     * @param attemptId 提交 id
     * @param annotationKey 批注键（slot:N / free:0）
     * @param reason 理由，可空
     * @return 回执（status=ACCEPTED）
     */
    AppealView submitDispute(Long userId, String attemptId, String annotationKey, String reason);

    /**
     * 取下一题：指定句优先，其次错题（errors=1），再次到期复习与新题；无可练题返回 null（客户端显示空状态）。
     *
     * <p>语义与本地引擎 handleLocalRequest 对齐（API §6.1 D7 参数表）：指定句命中且可练直接返回 （selection=MANUAL，卡不存在则
     * NEW），找不到退回常规取题；errors=1 走错题池（与错题列表同口径， dueAt 升序取最紧急）；tag 与排除集合在到期与新题召回中都生效。
     *
     * @param userId 当前用户 id
     * @param query 归一化后的查询参数（bankId/sentenceId/tag/errors/excludeIds）
     * @return 下一题视图；没有可练题时为 null
     */
    @Nullable
    NextPracticeView next(Long userId, NextQuery query);

    /**
     * 错题列表：LEARNING 卡 + 最近判分未全对；disputed 批注按参数过滤。
     *
     * @param userId 当前用户 id
     * @param page 页码，从 1 起
     * @param size 每页大小
     * @param includeDisputed 是否包含已被 DISPUTE 的批注
     * @param bankId 题库过滤，可空
     * @param tag 考点过滤，可空
     * @return 分页错题
     */
    PageResponse<WrongQuestionView> wrongQuestions(
            Long userId, int page, int size, boolean includeDisputed, Long bankId, String tag);

    /**
     * 复习队列：按 dueAt 升序，可按三档状态/模式/是否到期过滤。
     *
     * @param userId 当前用户 id
     * @param page 页码，从 1 起
     * @param size 每页大小
     * @param state 三档状态过滤，可空
     * @param mode 模式过滤，可空
     * @param due true 时只看到期卡
     * @return 分页复习卡
     */
    PageResponse<ReviewCardView> reviews(
            Long userId, int page, int size, String state, String mode, boolean due);

    /**
     * 重置复习卡为 NEW（「重新学」）；保留历史日志，不写 review_logs。
     *
     * @param userId 当前用户 id
     * @param reviewId 卡 id
     * @param reason 用户填写的理由，可空，仅进结构化日志
     * @return 重置后的卡视图
     */
    ReviewCardView resetReview(Long userId, Long reviewId, String reason);

    /**
     * 积压降载状态：到期数超阈值时客户端只推前 pushLimit 道。
     *
     * @param userId 当前用户 id
     * @return 降载视图
     */
    OverloadView overload(Long userId);

    /**
     * 顺延：把推送上限之外的到期卡整体推迟一天；用户动作，不写 review_logs。
     *
     * @param userId 当前用户 id
     * @param reason 用户填写的理由，可空，仅进结构化日志
     * @return 顺延结果
     */
    PostponeView postpone(Long userId, String reason);

    /**
     * 今日启动台数字：用户时区本地日界的 dueCount/doneCount/goal/correctRate/streakDays。
     *
     * @param userId 当前用户 id
     * @return 今日概览
     */
    TodayView today(Long userId);

    /**
     * 正确率统计（含 daily14/recent 扩展）；from/to 为用户本地日期，缺省最近 14 天。
     *
     * @param userId 当前用户 id
     * @param from 起始日期，可空
     * @param to 截止日期，可空
     * @param bankId 题库过滤，可空
     * @param mode 模式过滤，可空
     * @return 统计视图
     */
    AccuracyView accuracy(Long userId, LocalDate from, LocalDate to, Long bankId, String mode);

    /**
     * 考点错误分布：判分批注 tag 聚合，dispute 批注不计。
     *
     * @param userId 当前用户 id
     * @param from 起始日期，可空
     * @param to 截止日期，可空
     * @return 考点分布
     */
    ErrorTagsView errorTags(Long userId, LocalDate from, LocalDate to);

    /**
     * 游客升级：批量迁入本地提交；同 id 同载荷跳过、异载荷 40903 整体回滚。
     *
     * @param userId 当前用户 id
     * @param command 迁移命令（≤100 条）
     * @return 迁移结果
     */
    GuestUpgradeView guestUpgrade(Long userId, GuestUpgradeCommand command);
}
