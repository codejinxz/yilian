package io.github.codejinxz.yilian.practice.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitDecision;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitService;
import io.github.codejinxz.yilian.content.service.ContentQueryService;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;
import io.github.codejinxz.yilian.practice.dao.AppealDao;
import io.github.codejinxz.yilian.practice.dao.AttemptDao;
import io.github.codejinxz.yilian.practice.dao.AttemptGradingDao;
import io.github.codejinxz.yilian.practice.dao.LlmUsageDao;
import io.github.codejinxz.yilian.practice.dao.ReviewDao;
import io.github.codejinxz.yilian.practice.dao.ReviewLogDao;
import io.github.codejinxz.yilian.practice.entity.Appeal;
import io.github.codejinxz.yilian.practice.entity.Attempt;
import io.github.codejinxz.yilian.practice.entity.AttemptGrading;
import io.github.codejinxz.yilian.practice.entity.LlmUsage;
import io.github.codejinxz.yilian.practice.entity.Review;
import io.github.codejinxz.yilian.practice.entity.ReviewLog;
import io.github.codejinxz.yilian.practice.grading.AppealAdjudicationPromptBuilder;
import io.github.codejinxz.yilian.practice.grading.Grader;
import io.github.codejinxz.yilian.practice.grading.GradingAnnotation;
import io.github.codejinxz.yilian.practice.grading.GradingResult;
import io.github.codejinxz.yilian.practice.grading.ModelReviewPromptBuilder;
import io.github.codejinxz.yilian.practice.grading.Norm1Normalizer;
import io.github.codejinxz.yilian.practice.llm.BudgetGuardService;
import io.github.codejinxz.yilian.practice.llm.LlmBudgetSettings;
import io.github.codejinxz.yilian.practice.llm.LlmChatClient;
import io.github.codejinxz.yilian.practice.llm.LlmClientRegistry;
import io.github.codejinxz.yilian.practice.llm.LlmCostCalculator;
import io.github.codejinxz.yilian.practice.llm.PromptRegistry;
import io.github.codejinxz.yilian.practice.scheduler.FsrsScheduleParams;
import io.github.codejinxz.yilian.practice.scheduler.FsrsScheduler;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.dto.GuestUpgradeCommand;
import io.github.codejinxz.yilian.practice.service.dto.NextQuery;
import io.github.codejinxz.yilian.practice.service.vo.AccuracyView;
import io.github.codejinxz.yilian.practice.service.vo.AppealView;
import io.github.codejinxz.yilian.practice.service.vo.AttemptView;
import io.github.codejinxz.yilian.practice.service.vo.ErrorTagsView;
import io.github.codejinxz.yilian.practice.service.vo.GuestUpgradeView;
import io.github.codejinxz.yilian.practice.service.vo.ModelReviewView;
import io.github.codejinxz.yilian.practice.service.vo.NextPracticeView;
import io.github.codejinxz.yilian.practice.service.vo.OverloadView;
import io.github.codejinxz.yilian.practice.service.vo.PostponeView;
import io.github.codejinxz.yilian.practice.service.vo.ReviewCardView;
import io.github.codejinxz.yilian.practice.service.vo.TodayView;
import io.github.codejinxz.yilian.practice.service.vo.WrongQuestionView;
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.dto.UserSettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 练习用例实现：判分/幂等/SRS 排程的唯一写入口。
 *
 * <p>SRS 四分支与本地引擎（web/src/lib/local-engine/engine.ts 的 scheduleReview）逐值对齐： RETRY 不排程；失败（REVEAL 或
 * score&lt;100）回炉 LEARNING 明天到期；学习中全对按 interval=min(7, scheduled+2) 推进、≥5 归档；归档全对只刷新复习痕迹；新题全对不入队。
 * 每次排程写一行 review_logs（before/after），uk_review_log_attempt 保证一次排程。
 */
@Service
public class PracticeServiceImpl implements PracticeService {

    private static final Logger log = LoggerFactory.getLogger(PracticeServiceImpl.class);

    /** deriveRating：全对且未用提示、耗时 ≤60s 记 EASY 的阈值（毫秒）。 */
    private static final long EASY_DURATION_MS = 60_000L;

    /** 降载阈值：到期数超过该值进入降载（当前为常量）。 */
    private static final int OVERLOAD_THRESHOLD = 20;

    /** 降载推送上限：降载时一次只推最早到期的 5 道。 */
    private static final int PUSH_LIMIT = 5;

    /**
     * 顺延天数：统一为 3 天。本地引擎是 golden 基准——{@code engine.ts postponeOverdue(days)} 参数化且调用方传
     * 3（与原型一致），服务端按同一口径对齐。
     */
    private static final int POSTPONE_DAYS = 3;

    /** analytics from/to 闭区间上限（本地日数）：全量内存聚合的量级保护，超长按 40000 拒绝。 */
    private static final int MAX_RANGE_DAYS = 92;

    /** 模型复核的 LLM 场景标识：llm_usage.scene 与结构化日志的 scene 同源。 */
    static final String MODEL_REVIEW_SCENE = "review";

    /** 申诉初裁的 LLM 场景标识：llm_usage.scene 与结构化日志的 scene 同源。 */
    static final String APPEAL_ADJUDICATION_SCENE = "appeal";

    /** 申诉初裁限流场景名：与端点注解限流（practice-attempt 等）相互独立。 */
    static final String APPEAL_ADJUDICATION_RATE_SCENE = "appeal-adjudication";

    /** 申诉初裁限流阈值：5 次/分钟/账号（与复核同口径），超出跳过初裁而不是拒绝申诉。 */
    static final int APPEAL_ADJUDICATION_RATE_LIMIT = 5;

    /** 申诉初裁限流窗口（秒）。 */
    static final int APPEAL_ADJUDICATION_RATE_WINDOW_SECONDS = 60;

    /** 初裁输出解析的最大调用次数：首次 + 解析失败重试 1 次，仍败即落占位。 */
    private static final int APPEAL_ADJUDICATION_MAX_ATTEMPTS = 2;

    /** 初裁占位 decision JSON：失败/超时/限流统一落「初裁不可用」，读端按 verdict 识别。 */
    static final String APPEAL_ADJUDICATION_UNAVAILABLE_JSON = "{\"verdict\":\"UNAVAILABLE\"}";

    /** 初裁占位理由：不外抛堆栈与供应商信息，与答疑降级同款口径。 */
    static final String APPEAL_ADJUDICATION_UNAVAILABLE_REASON = "AI 初裁暂不可用，等待人工终审";

    /** 初裁预算门占位 decision JSON：预算用尽跳过初裁，reason=BUDGET 区分于通用不可用（读端可归因）。 */
    static final String APPEAL_ADJUDICATION_BUDGET_JSON =
            "{\"verdict\":\"UNAVAILABLE\",\"reason\":\"BUDGET\"}";

    /** 初裁预算门占位理由：与通用占位同款不外抛供应商信息，只点明预算原因。 */
    static final String APPEAL_ADJUDICATION_BUDGET_REASON = "本月模型预算已用尽，AI 初裁暂不可用，等待人工终审";

    /** 模型复核输出解析的最大调用次数：首次 + 解析失败重试 1 次，仍败即降级。 */
    private static final int MODEL_REVIEW_MAX_ATTEMPTS = 2;

    /** 模型复核降级文案：与答疑同款固定话术，不外抛堆栈与供应商信息。 */
    static final String MODEL_REVIEW_DEGRADE_MESSAGE = "模型复核暂时不可用，请稍后再试";

    /** 模型复核预算门降级文案：走既有 50000 降级语义（不调模型不落行），文案点明预算原因。 */
    static final String MODEL_REVIEW_BUDGET_MESSAGE = "模型复核暂时不可用：本月模型预算已用尽，请下月再试或联系管理员调整预算";

    private final AttemptDao attemptDao;

    private final AttemptGradingDao gradingDao;

    private final ReviewDao reviewDao;

    private final ReviewLogDao reviewLogDao;

    private final AppealDao appealDao;

    private final LlmClientRegistry llmClients;

    private final LlmUsageDao llmUsageDao;

    private final PromptRegistry promptRegistry;

    private final RateLimitService rateLimitService;

    /** 月度预算与定价存取（app_settings 键 llm.budget）：记账补成本按当次定价查询。 */
    private final LlmBudgetSettings budgetSettings;

    /** 预算护栏判定：复核与申诉初裁调用前的强制门。 */
    private final BudgetGuardService budgetGuard;

    private final FsrsScheduler fsrsScheduler;

    private final ContentQueryService contentQueryService;

    private final UserAccountService userAccountService;

    private final ObjectMapper objectMapper;

    /**
     * 竞态隔离用的独立新事务（REQUIRES_NEW）：并发同 id/同句冲突后的读回（绕开本事务的失败会话与过期快照）， 以及复习卡建卡的隔离落库（冲突留在内层干净回滚，不污染外层会话）。
     */
    private final TransactionTemplate freshTxTemplate;

    /**
     * LLM 用量旁路记账的独立新事务（REQUIRES_NEW）：llm_usage 是与主链路成败无关的旁路账本—— 029 起复核在事务内跑，若沿用外层事务，降级抛 50000
     * 时记账会随回滚一起丢（三终态契约破坏）； 内层独立提交保证「调用即记账」与主事务成败解耦（答疑/初裁链路无外层事务，行为不变）。
     */
    private final TransactionTemplate usageTxTemplate;

    /**
     * 只依赖 Dao 接口与跨模块 service 接口，不触碰实体映射之外的具体实现。
     *
     * @param attemptDao 提交 Dao
     * @param gradingDao 判分 Dao
     * @param reviewDao 复习卡 Dao
     * @param reviewLogDao 排程日志 Dao
     * @param appealDao 申诉/不认同 Dao（disputed overlay + 裁决列更新）
     * @param llmClients LLM 客户端注册表（模型复核与申诉初裁用；目录 + legacy 兼容轨）
     * @param llmUsageDao LLM 用量账本 Dao（复核/初裁调用旁路记账，失败不阻塞）
     * @param promptRegistry Prompt 版本注册表（复核/初裁系统提示与 prompt_version_id 来源）
     * @param rateLimitService 限流器（申诉初裁的账号级滑动窗口，程序化判定）
     * @param budgetSettings 月度预算与定价存取（记账补成本按当次定价查询）
     * @param budgetGuard 预算护栏判定（复核/初裁强制门）
     * @param fsrsScheduler FSRS 排程参数装配（ACTIVE 权重解码）
     * @param contentQueryService 题目内容只读查询（content 模块）
     * @param userAccountService 用户设置查询（system 模块）
     * @param objectMapper JSON 序列化（答案载荷与判分结果落库）
     * @param transactionManager 事务管理器（装配竞态隔离用的 REQUIRES_NEW 模板）
     */
    public PracticeServiceImpl(
            AttemptDao attemptDao,
            AttemptGradingDao gradingDao,
            ReviewDao reviewDao,
            ReviewLogDao reviewLogDao,
            AppealDao appealDao,
            LlmClientRegistry llmClients,
            LlmUsageDao llmUsageDao,
            PromptRegistry promptRegistry,
            RateLimitService rateLimitService,
            LlmBudgetSettings budgetSettings,
            BudgetGuardService budgetGuard,
            FsrsScheduler fsrsScheduler,
            ContentQueryService contentQueryService,
            UserAccountService userAccountService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.attemptDao = attemptDao;
        this.gradingDao = gradingDao;
        this.reviewDao = reviewDao;
        this.reviewLogDao = reviewLogDao;
        this.appealDao = appealDao;
        this.llmClients = llmClients;
        this.llmUsageDao = llmUsageDao;
        this.promptRegistry = promptRegistry;
        this.rateLimitService = rateLimitService;
        this.budgetSettings = budgetSettings;
        this.budgetGuard = budgetGuard;
        this.fsrsScheduler = fsrsScheduler;
        this.contentQueryService = contentQueryService;
        this.userAccountService = userAccountService;
        this.objectMapper = objectMapper;
        this.freshTxTemplate = new TransactionTemplate(transactionManager);
        this.freshTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.usageTxTemplate = new TransactionTemplate(transactionManager);
        this.usageTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public AttemptView submitAttempt(Long userId, AttemptCommand command) {
        Instant now = Instant.now();
        // 幂等第一道闸：同 id 已存在则比对哈希与归属，同载荷重放、异载荷 40903。
        Optional<Attempt> existing = attemptDao.findById(command.attemptId());
        if (existing.isPresent()) {
            Attempt stored = existing.get();
            if (!stored.getUserId().equals(userId)
                    || !stored.getAnswerHash().equals(payloadHash(command))) {
                throw new BusinessException(ApiCode.ATTEMPT_ID_REUSED);
            }
            return buildReplayView(stored);
        }

        String answerJson = objectMapper.writeValueAsString(command.answer());
        String answerHash = payloadHash(command);

        // 校验链：题目存在且可练 → 库可见 → 版本一致 → RETRY 链合法。
        SentenceDetail detail = requirePracticeableSentence(userId, command);
        validateRetryChain(userId, command);

        Attempt attempt =
                Attempt.create(
                        command.attemptId(),
                        userId,
                        null,
                        command.sentenceId(),
                        parseVersionId(command.sentenceVersionId()),
                        detail.questionVersion() == null ? 0 : detail.questionVersion(),
                        toEntityMode(command.mode()),
                        toEntityKind(command.kind()),
                        command.retryOfId(),
                        answerJson,
                        answerHash,
                        (short) command.effectiveHintsUsed(),
                        command.durationMs(),
                        command.clientCreatedAt(),
                        now);
        try {
            // 立即 flush：并发同 id 的主键冲突在本方法内被捕获；若延迟到 commit 阶段，
            // 异常会绕过 catch 直接变成 500。
            attemptDao.saveAndFlush(attempt);
        } catch (DataIntegrityViolationException exception) {
            // 并发同 id 双发，PK 兜底（幂等以数据库唯一约束为准）。本事务的 INSERT 已失败：
            // 失败动作仍挂在会话里（提交前再次 flush 会复现同一条冲突），且 REPEATABLE READ
            // 快照读不到赢家已提交的行——因此先标记回滚丢弃本事务，再在独立新事务里读回
            // 赢家结果：同载荷按重放返回原结果（replayed=true），异载荷按 40903 拒绝。
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return replayAfterDuplicateInsert(userId, command, answerHash);
        }

        GradingResult result = gradeForCommand(detail, command);
        int sequence =
                gradingDao
                        .findLatestByAttemptId(command.attemptId())
                        .map(g -> g.getSequence() + 1)
                        .orElse(1);
        AttemptGrading grading =
                gradingDao.save(
                        AttemptGrading.createLocal(
                                command.attemptId(),
                                sequence,
                                result.score(),
                                objectMapper.writeValueAsString(result),
                                now));

        int rating =
                deriveRating(
                        result.score(),
                        command.effectiveHintsUsed(),
                        command.durationMs(),
                        command.kind());
        Review review = scheduleReview(attempt, result.score(), rating, command, now);

        return buildView(attempt, detail, grading, result, review, rating, false);
    }

    /**
     * 并发同 id 竞态后的幂等读回：在独立新事务里按 attemptId 取已提交的赢家行并比对载荷哈希。
     *
     * <p>必须用 REQUIRES_NEW：调用方的本事务已含失败的 INSERT 且被标记回滚， 其会话不可继续复用，其 REPEATABLE READ 快照也读不到赢家已提交的行。
     *
     * @param userId 当前用户 id
     * @param command 原提交命令
     * @param answerHash 原提交的载荷哈希
     * @return 赢家的完整判分视图（replayed=true）
     */
    private AttemptView replayAfterDuplicateInsert(
            Long userId, AttemptCommand command, String answerHash) {
        return freshTxTemplate.execute(
                status -> {
                    Attempt winner = attemptDao.findById(command.attemptId()).orElse(null);
                    if (winner == null
                            || !winner.getUserId().equals(userId)
                            || !winner.getAnswerHash().equals(answerHash)) {
                        throw new BusinessException(ApiCode.ATTEMPT_ID_REUSED);
                    }
                    return buildReplayView(winner);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public AttemptView getAttempt(Long userId, String attemptId) {
        Attempt attempt =
                attemptDao
                        .findById(attemptId)
                        .filter(a -> a.getUserId().equals(userId))
                        .orElseThrow(() -> new BusinessException(ApiCode.ATTEMPT_NOT_FOUND));
        // 回看统一口径：库可见或练习史豁免（本人已有卡），否则 40300——
        // attempt 归属校验只保证「是本人提交」，不保证「当前仍可读该句内容」；无卡且库已转私的
        // 提交回看不再回显参考译文与解析。submitAttempt 的幂等重放不走本判定：重放返回的是
        // 提交时已交付过的判分结果，不构成新的内容访问。
        SentenceDetail detail =
                contentQueryService.findSentence(attempt.getSentenceId()).orElse(null);
        if (detail != null && !accessibleForPractice(userId, detail)) {
            throw new BusinessException(ApiCode.ACCESS_DENIED);
        }
        return buildReplayView(attempt);
    }

    /**
     * 幂等重放/详情共用装配：从落库的判分 JSON 还原结果，复习卡取当前投影。
     *
     * @param attempt 已落库提交
     * @return 响应视图
     */
    private AttemptView buildReplayView(Attempt attempt) {
        AttemptGrading grading =
                gradingDao
                        .findLatestByAttemptId(attempt.getId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ApiCode.INTERNAL_ERROR,
                                                "判分记录缺失：" + attempt.getId()));
        GradingResult result = objectMapper.readValue(grading.getResult(), GradingResult.class);
        SentenceDetail detail =
                contentQueryService.findSentence(attempt.getSentenceId()).orElse(null);
        Review review =
                reviewDao
                        .findByUserIdAndSentenceId(attempt.getUserId(), attempt.getSentenceId())
                        .orElse(null);
        Integer rating =
                reviewLogDao
                        .findByAttemptId(attempt.getId())
                        .map(log -> (int) log.getRating())
                        .or(
                                () ->
                                        Optional.ofNullable(
                                                review == null ? null : review.getLastRating()))
                        .orElse(null);
        int displayRating = rating == null ? 0 : rating;
        return buildView(attempt, detail, grading, result, review, displayRating, true);
    }

    @Override
    public AppealView submitAppeal(
            Long userId,
            String attemptId,
            String annotationKey,
            String proposedAnswer,
            String reason) {
        // 刻意不加 @Transactional：落库（Dao 自身事务提交）之后要同步调 LLM 初裁（最长 15s 外呼），
        // 持着写事务外呼会占死数据库连接（模型复核 reviewAttempt 同款裁决）；初裁结果的落库由
        // Dao 定向 UPDATE 自身事务提交，两段之间崩溃只留下「无 AI 意见」的 PENDING 行，可终审。
        Appeal appeal =
                saveAppeal(
                        userId,
                        attemptId,
                        annotationKey,
                        Appeal.AppealKind.APPEAL,
                        proposedAnswer,
                        reason);
        AdjudicationOutcome outcome =
                adjudicateAppeal(
                        appeal,
                        requireAnnotationForAdjudication(attemptId, annotationKey),
                        proposedAnswer,
                        reason);
        log.info(
                "appeal_submitted userId={} attemptId={} key={} kind=APPEAL status=PENDING"
                        + " verdict={}",
                userId,
                attemptId,
                annotationKey,
                outcome == null ? "none" : outcome.verdict());
        return new AppealView(
                appeal.getId(),
                "PENDING",
                Appeal.AppealKind.APPEAL.name(),
                annotationKey,
                outcome == null ? null : outcome.verdict(),
                outcome == null ? null : outcome.decisionReason(),
                outcome == null ? null : outcome.decidedBy(),
                outcome == null ? null : outcome.decidedAt());
    }

    @Override
    @Transactional
    public AppealView submitDispute(
            Long userId, String attemptId, String annotationKey, String reason) {
        Appeal appeal =
                saveAppeal(
                        userId, attemptId, annotationKey, Appeal.AppealKind.DISPUTE, null, reason);
        return new AppealView(
                appeal.getId(),
                "ACCEPTED",
                Appeal.AppealKind.DISPUTE.name(),
                annotationKey,
                null,
                null,
                null,
                null);
    }

    /**
     * 申诉/不认同共用落库链路：归属校验 → 批注定位 → uk 预检 → 写 appeals。
     *
     * <p>uk_appeal_annotation 为 (attempt_id, annotation_key, kind)：APPEAL 与 DISPUTE 各自 可存一条；先查后插给友好
     * 40900，并发兜底靠数据库唯一约束。无外层事务要求：调用方在事务内或外都可， INSERT 由 Dao 自身事务提交。
     *
     * @param userId 当前用户 id
     * @param attemptId 提交 id
     * @param annotationKey 批注键（slot:N / free:0）
     * @param kind 记录类型
     * @param proposedAnswer 建议答案，DISPUTE 恒为 null
     * @param reason 理由，可空
     * @return 已持久化的记录（含 id）
     */
    private Appeal saveAppeal(
            Long userId,
            String attemptId,
            String annotationKey,
            Appeal.AppealKind kind,
            String proposedAnswer,
            String reason) {
        Attempt attempt =
                attemptDao
                        .findById(attemptId)
                        .orElseThrow(() -> new BusinessException(ApiCode.ATTEMPT_NOT_FOUND));
        if (!attempt.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.ACCESS_DENIED);
        }
        GradingAnnotation annotation = requireAnnotation(attempt, annotationKey);
        if (appealDao.findByAttemptAndKey(attemptId, annotationKey, kind).isPresent()) {
            throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT);
        }
        Appeal appeal =
                Appeal.create(
                        attemptId,
                        annotationKey,
                        attempt.getSentenceId(),
                        annotation.chunkIndex(),
                        userId,
                        kind,
                        proposedAnswer,
                        reason,
                        Instant.now());
        try {
            appealDao.save(appeal);
        } catch (DataIntegrityViolationException exception) {
            // 并发同键插入撞 uk：与预检同义，按重复提交拒绝（幂等以数据库唯一约束为准）。
            throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT);
        }
        // 所有自动化可见：status 由 kind 决定（DISPUTE 即时 ACCEPTED，APPEAL 留 PENDING）。
        log.info(
                "appeal_saved userId={} attemptId={} key={} kind={} status={}",
                userId,
                attemptId,
                annotationKey,
                kind.name(),
                kind == Appeal.AppealKind.DISPUTE ? "ACCEPTED" : "PENDING");
        return appeal;
    }

    /**
     * 申诉初裁的批注定位：落库成功后重新解析首判批注（初裁上下文用）。
     *
     * <p>与 {@link #requireAnnotation(Attempt, String)} 同源但容错语义不同： appeal 已落库，此刻批注解析失败不该让已提交的申诉变成
     * 5xx， 返回 null 由初裁按「上下文缺失」降级占位。
     *
     * @param attemptId 提交 id
     * @param annotationKey 批注键
     * @return 命中的批注；解析失败为 null
     */
    private GradingAnnotation requireAnnotationForAdjudication(
            String attemptId, String annotationKey) {
        try {
            Attempt attempt =
                    attemptDao
                            .findById(attemptId)
                            .orElseThrow(() -> new BusinessException(ApiCode.ATTEMPT_NOT_FOUND));
            return requireAnnotation(attempt, annotationKey);
        } catch (BusinessException | JacksonException exception) {
            return null;
        }
    }

    /** 一次初裁的终态：落库列与回执视图共用的投影（verdict=UNAVAILABLE 表示初裁不可用占位，DISPUTE/未生成场景回执 verdict 为 null）。 */
    private record AdjudicationOutcome(
            String verdict, String decisionReason, String decidedBy, Instant decidedAt) {}

    /**
     * 申诉同步 LLM 初裁（预披露裁决：同步不引队列，申诉低频，≤15s 可接受）。
     *
     * <p>四种终态都把申诉留在 PENDING 等人工终审，任何失败都不阻塞提交、不改判分：
     *
     * <ul>
     *   <li>限流（独立 scene，5 次/分钟/账号）→ 占位 UNAVAILABLE；
     *   <li>无可用模型客户端 → 占位 UNAVAILABLE（初裁是增强，答疑 40400 语义不适用于申诉）；
     *   <li>调用/解析失败（重试 1 次）→ 占位 UNAVAILABLE；
     *   <li>成功 → decision JSON + 理由 + decided_by=LLM + 快照 + prompt_version_id 落裁决列。
     * </ul>
     *
     * <p>本方法自身再兜一层 catch：初裁内部的任何未预期异常都收敛为占位， 绝不把已落库的申诉变成 5xx。
     *
     * @param appeal 已落库的申诉行
     * @param annotation 被申诉批注，可为 null（上下文缺失降级）
     * @param proposedAnswer 用户主张的可接受答案，可空
     * @param reason 申诉理由，可空
     * @return 初裁终态；占位/失败时 verdict 为 UNAVAILABLE
     */
    private AdjudicationOutcome adjudicateAppeal(
            Appeal appeal, GradingAnnotation annotation, String proposedAnswer, String reason) {
        try {
            // 初裁限流：程序化判定而不是端点注解——429 只跳过初裁，申诉本身不拒（预披露裁决）。
            RateLimitDecision decision =
                    rateLimitService.acquire(
                            APPEAL_ADJUDICATION_RATE_SCENE,
                            List.of(RateLimitDimension.ACCOUNT),
                            APPEAL_ADJUDICATION_RATE_LIMIT,
                            APPEAL_ADJUDICATION_RATE_WINDOW_SECONDS,
                            null,
                            String.valueOf(appeal.getUserId()));
            if (!decision.allowed()) {
                log.info(
                        "appeal_adjudication_skipped reason=rate_limited appealId={} userId={}",
                        appeal.getId(),
                        appeal.getUserId());
                return recordUnavailable(appeal.getId());
            }
            // 预算门：EXCEEDED 时跳过初裁（不调模型），落 reason=BUDGET 占位——与限流跳过同形态，
            // 申诉本身不拒不阻塞（仍 PENDING 等人工终审）。
            if (budgetGuard.gate() == BudgetGuardService.Status.EXCEEDED) {
                log.info(
                        "appeal_adjudication_skipped reason=budget_exhausted appealId={} userId={}",
                        appeal.getId(),
                        appeal.getUserId());
                return recordPlaceholder(
                        appeal.getId(),
                        APPEAL_ADJUDICATION_BUDGET_JSON,
                        APPEAL_ADJUDICATION_BUDGET_REASON);
            }
            LlmClientRegistry.ChatHandle handle = llmClients.chatHandle().orElse(null);
            if (handle == null) {
                return recordUnavailable(appeal.getId());
            }
            String protocol =
                    llmClients
                            .effectiveConfig()
                            .map(LlmClientRegistry.EffectiveConfig::protocol)
                            .orElse(null);
            PromptRegistry.Entry promptEntry =
                    promptRegistry.load(AppealAdjudicationPromptBuilder.PROMPT_NAME);
            String systemPrompt =
                    promptEntry instanceof PromptRegistry.Present present
                            ? present.content()
                            : AppealAdjudicationPromptBuilder.systemPrompt();
            Long promptVersionId =
                    promptEntry instanceof PromptRegistry.Present present
                            ? present.versionId()
                            : null;
            String userPrompt =
                    AppealAdjudicationPromptBuilder.userPrompt(
                            contentQueryService.findSentence(appeal.getSentenceId()).orElse(null),
                            firstResultOf(appeal.getAttemptId()),
                            annotation,
                            proposedAnswer,
                            reason);
            long startNanos = System.nanoTime();
            AppealAdjudicationPromptBuilder.AppealAdjudicationOutput output =
                    callAdjudicationWithRetry(
                            appeal.getUserId(),
                            appeal.getSentenceId(),
                            handle,
                            protocol,
                            systemPrompt,
                            userPrompt);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            String modelSnapshot = handle.providerId() + ":" + handle.model();
            Instant decidedAt = Instant.now();
            // decision 列落「裁决原文」：verdict/reason 两字段的规范 JSON（模型原文可能带围栏、
            // 多余空白或越界值，落库用解析校验后的干净值重排）。
            String decisionJson = normalizedDecisionJson(output);
            appealDao.updateAdjudication(
                    appeal.getId(),
                    decisionJson,
                    output.reason(),
                    "LLM",
                    decidedAt,
                    modelSnapshot,
                    promptVersionId);
            log.info(
                    "appeal_adjudicated appealId={} userId={} sentenceId={} verdict={}"
                            + " model={} latencyMs={}",
                    appeal.getId(),
                    appeal.getUserId(),
                    appeal.getSentenceId(),
                    output.verdict(),
                    modelSnapshot,
                    latencyMs);
            return new AdjudicationOutcome(output.verdict(), output.reason(), "LLM", decidedAt);
        } catch (RuntimeException exception) {
            // 兜底收敛：初裁内部的任何未预期异常（含 JSON 拼装）都降级为占位，不阻塞申诉。
            log.warn(
                    "appeal_adjudication_failed appealId={} error={}",
                    appeal.getId(),
                    exception.getClass().getSimpleName());
            return recordUnavailable(appeal.getId());
        }
    }

    /**
     * 规范化初裁 decision JSON：verdict/reason 两字段的固定形状（模型原文可能带围栏或多余空白， 落库用解析后的干净值重排）。
     *
     * @param output 已校验的初裁输出
     * @return {"verdict":"...","reason":"..."} JSON 文本
     */
    private String normalizedDecisionJson(
            AppealAdjudicationPromptBuilder.AppealAdjudicationOutput output) {
        return "{\"verdict\":\""
                + output.verdict()
                + "\",\"reason\":"
                + objectMapper.writeValueAsString(output.reason())
                + "}";
    }

    /**
     * 落初裁占位（限流/无模型/失败统一路径）：decision 落 UNAVAILABLE JSON + 占位理由， decided_by/decided_at/快照/版本保持
     * NULL。占位写失败也不外抛（申诉已在库，管理端可无 AI 意见终审）。
     *
     * <p>029（F-01 修复）：回执 verdict 对齐存储真相——decision 列落的是 UNAVAILABLE JSON，回执也发
     * verdict="UNAVAILABLE"，让「初裁不可用」回执态真实可达（GAP 025 声明的回执四态分化成立， 前端对应分支不再是对真实服务的死代码）；
     * decided_by=null 仍是无 AI 意见的判据。
     *
     * @param appealId 申诉行 id
     * @return 占位终态（verdict=UNAVAILABLE，decidedBy=null）
     */
    private AdjudicationOutcome recordUnavailable(long appealId) {
        return recordPlaceholder(
                appealId,
                APPEAL_ADJUDICATION_UNAVAILABLE_JSON,
                APPEAL_ADJUDICATION_UNAVAILABLE_REASON);
    }

    /**
     * 落初裁占位（指定 decision JSON 与理由，通用内核）：预算门与通用不可用共用本路径， 占位写失败也不外抛（申诉已在库，管理端可无 AI 意见终审）。
     *
     * @param appealId 申诉行 id
     * @param decisionJson 占位 decision JSON（UNAVAILABLE 形状，预算占位额外带 reason=BUDGET）
     * @param reason 占位理由（decision_reason 列与回执共用）
     * @return 占位终态（verdict=UNAVAILABLE，decidedBy=null）
     */
    private AdjudicationOutcome recordPlaceholder(
            long appealId, String decisionJson, String reason) {
        try {
            appealDao.updateAdjudication(appealId, decisionJson, reason, null, null, null, null);
        } catch (RuntimeException exception) {
            log.warn(
                    "appeal_adjudication_placeholder_failed appealId={} error={}",
                    appealId,
                    exception.getClass().getSimpleName());
        }
        return new AdjudicationOutcome("UNAVAILABLE", reason, null, null);
    }

    /**
     * 带重试的初裁调用：输出解析失败或异常重试 1 次（共最多 {@link #APPEAL_ADJUDICATION_MAX_ATTEMPTS} 次）， 仍败抛出由调用方落占位。
     *
     * <p>每次调用的终态旁路记一条 llm_usage（scene=appeal）：成功与非空但不可解析的回复 ok=true （token 消耗真实发生），空回复与异常 ok=false。
     *
     * @param userId 发起用户 id
     * @param sentenceId 题目 id
     * @param handle 当前可用的客户端句柄
     * @param protocol 当次生效协议（目录未命中时为 null）
     * @param systemPrompt 系统提示（注册表版本或兜底常量）
     * @param userPrompt 用户消息
     * @return 解析并校验后的初裁输出
     */
    private AppealAdjudicationPromptBuilder.AppealAdjudicationOutput callAdjudicationWithRetry(
            Long userId,
            String sentenceId,
            LlmClientRegistry.ChatHandle handle,
            String protocol,
            String systemPrompt,
            String userPrompt) {
        for (int call = 1; call <= APPEAL_ADJUDICATION_MAX_ATTEMPTS; call++) {
            long callStartNanos = System.nanoTime();
            try {
                LlmChatClient.Reply reply = handle.client().ask(systemPrompt, userPrompt);
                long durationMs = (System.nanoTime() - callStartNanos) / 1_000_000;
                if (reply.content() == null || reply.content().isBlank()) {
                    recordAppealUsage(
                            userId,
                            sentenceId,
                            protocol,
                            handle.model(),
                            reply.promptTokens(),
                            reply.completionTokens(),
                            durationMs,
                            false);
                    log.warn(
                            "LLM 空回复：scene={}, appealUserId={}, sentenceId={}, call={}/{},"
                                    + " durationMs={}, ok=false",
                            APPEAL_ADJUDICATION_SCENE,
                            userId,
                            sentenceId,
                            call,
                            APPEAL_ADJUDICATION_MAX_ATTEMPTS,
                            durationMs);
                    continue;
                }
                recordAppealUsage(
                        userId,
                        sentenceId,
                        protocol,
                        handle.model(),
                        reply.promptTokens(),
                        reply.completionTokens(),
                        durationMs,
                        true);
                Optional<AppealAdjudicationPromptBuilder.AppealAdjudicationOutput> parsed =
                        AppealAdjudicationPromptBuilder.parse(objectMapper, reply.content());
                if (parsed.isPresent()) {
                    return parsed.get();
                }
                log.warn(
                        "LLM 输出解析失败：scene={}, appealUserId={}, sentenceId={}, call={}/{}",
                        APPEAL_ADJUDICATION_SCENE,
                        userId,
                        sentenceId,
                        call,
                        APPEAL_ADJUDICATION_MAX_ATTEMPTS);
            } catch (RuntimeException exception) {
                recordAppealUsage(
                        userId,
                        sentenceId,
                        protocol,
                        handle.model(),
                        null,
                        null,
                        (System.nanoTime() - callStartNanos) / 1_000_000,
                        false);
                log.warn(
                        "LLM 调用失败：scene={}, appealUserId={}, sentenceId={}, call={}/{},"
                                + " error={}",
                        APPEAL_ADJUDICATION_SCENE,
                        userId,
                        sentenceId,
                        call,
                        APPEAL_ADJUDICATION_MAX_ATTEMPTS,
                        exception.getClass().getSimpleName());
            }
        }
        throw new BusinessException(ApiCode.INTERNAL_ERROR, APPEAL_ADJUDICATION_UNAVAILABLE_REASON);
    }

    /**
     * 落一条初裁调用终态用量记录（llm_usage append-only）。旁路纪律与复核/答疑一致：写失败吞掉只记告警。
     *
     * @param userId 发起用户 id
     * @param sentenceId 题目 id
     * @param protocol 当次生效协议（目录未命中时为 null）
     * @param model 当次生效模型名快照
     * @param promptTokens 提示词 token（失败未取得时为 null）
     * @param completionTokens 回复 token（失败未取得时为 null）
     * @param durationMs 调用耗时毫秒
     * @param ok 是否拿到非空回复
     */
    private void recordAppealUsage(
            Long userId,
            String sentenceId,
            String protocol,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            long durationMs,
            boolean ok) {
        try {
            llmUsageDao.save(
                    LlmUsage.of(
                            userId,
                            sentenceId,
                            APPEAL_ADJUDICATION_SCENE,
                            protocol,
                            model,
                            promptTokens,
                            completionTokens,
                            durationMs,
                            LlmCostCalculator.estimateMicroCny(
                                    model,
                                    promptTokens,
                                    completionTokens,
                                    budgetSettings.read().pricing()),
                            ok,
                            Instant.now()));
        } catch (RuntimeException exception) {
            log.warn(
                    "LLM 用量落库失败（旁路不阻塞）：scene={}, userId={}, sentenceId={}, error={}",
                    APPEAL_ADJUDICATION_SCENE,
                    userId,
                    sentenceId,
                    exception.getClass().getSimpleName());
        }
    }

    /**
     * 取某提交的首判结果（初裁「本地批注」上下文）；缺失或损坏返回 null，初裁按上下文缺失降级。
     *
     * @param attemptId 提交 id
     * @return 首判结果；未取得为 null
     */
    private GradingResult firstResultOf(String attemptId) {
        return gradingDao
                .findFirstByAttemptId(attemptId)
                .map(
                        grading -> {
                            try {
                                return objectMapper.readValue(
                                        grading.getResult(), GradingResult.class);
                            } catch (JacksonException exception) {
                                return null;
                            }
                        })
                .orElse(null);
    }

    /**
     * 批注定位：key 必须存在于该提交最新判分结果的批注列表，chunkIndex 从批注取。
     *
     * <p>判分缺失、result JSON 损坏或 key 未命中统一按「批注不存在」（40000）拒绝， 不把解析异常当 500 暴露。
     *
     * @param attempt 提交
     * @param annotationKey 批注键
     * @return 命中的批注
     */
    private GradingAnnotation requireAnnotation(Attempt attempt, String annotationKey) {
        AttemptGrading grading = gradingDao.findLatestByAttemptId(attempt.getId()).orElse(null);
        if (grading == null || grading.getResult() == null) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "批注不存在");
        }
        GradingResult result;
        try {
            result = objectMapper.readValue(grading.getResult(), GradingResult.class);
        } catch (JacksonException exception) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "批注不存在");
        }
        if (result.annotations() != null) {
            for (GradingAnnotation annotation : result.annotations()) {
                if (annotationKey.equals(annotation.key())) {
                    return annotation;
                }
            }
        }
        throw new BusinessException(ApiCode.VALIDATION_FAILED, "批注不存在");
    }

    /**
     * 组装响应视图。
     *
     * @param attempt 提交
     * @param detail 题目详情，重放时可能为 tombstone 或已删（reference 退化为 null 字段）
     * @param grading 最新判分行
     * @param result 判分结果
     * @param review 复习卡，可能为 null
     * @param rating 本轮评分，无排程时为 0（视图里以 null 呈现）
     * @param replayed 是否重放
     * @return 响应视图
     */
    private AttemptView buildView(
            Attempt attempt,
            SentenceDetail detail,
            AttemptGrading grading,
            GradingResult result,
            Review review,
            int rating,
            boolean replayed) {
        Integer score = grading.getScore();
        boolean retryRequired =
                score != null && score < 100 && attempt.getKind() == Attempt.AttemptKind.FIRST;
        AttemptView.ReviewView reviewView =
                review == null
                        ? null
                        : new AttemptView.ReviewView(
                                review.getId(),
                                review.getState().name(),
                                review.getFsrsState().name(),
                                review.getDueAt(),
                                review.getStability(),
                                review.getDifficulty(),
                                review.getReps(),
                                review.getLapses(),
                                review.getScheduledDays(),
                                rating == 0 ? null : rating);
        return new AttemptView(
                attempt.getId(),
                attempt.getSentenceId(),
                String.valueOf(attempt.getSentenceVersionId()),
                attempt.getMode().name(),
                attempt.getKind().name(),
                score,
                grading.getMaxScore() == 0 ? 100 : grading.getMaxScore(),
                result,
                new AttemptView.GradingView(
                        grading.getId(),
                        grading.getSequence(),
                        grading.getGrader(),
                        grading.getNormalizerVersion(),
                        grading.getRubricVersion(),
                        grading.getCreatedAt()),
                reviewView,
                detail == null ? null : new AttemptView.ReferenceView(detail.ref(), detail.note()),
                new AttemptView.NextActionView(retryRequired, retryRequired),
                replayed);
    }

    /**
     * 题目校验：存在、可练（已发布未删）、所属库对当前用户可访问、版本一致。
     *
     * <p>可访问性在版本校验之前判定：库可见，或练习史豁免（本人已有该句 复习卡）。不可访问（私有库非本人、匿名访问私有库、库未发布/已软删，且无练习史） 统一回
     * 40300，不区分不可访问原因、不回显句子内容——「原文默认不可见」红线；题库所有者对自己私有库的 题目提交不受影响。40300 而非 40402
     * 的取舍：交卷请求已携带具体题目，不可访问属访问控制失败而非资源缺失， 统一码避免用 40401/40402 的区分反推库的存在性与发布状态。回收句在 practiceable 过滤处即
     * 40402， 练习史豁免不适用于「句被回收」（见 {@link #practiceHistoryExempt}）。
     *
     * @param userId 当前用户 id
     * @param command 提交命令
     * @return 题目详情
     */
    private SentenceDetail requirePracticeableSentence(Long userId, AttemptCommand command) {
        SentenceDetail detail =
                contentQueryService
                        .findSentence(command.sentenceId())
                        .filter(SentenceDetail::practiceable)
                        .orElseThrow(() -> new BusinessException(ApiCode.SENTENCE_NOT_FOUND));
        if (!accessibleForPractice(userId, detail)) {
            throw new BusinessException(ApiCode.ACCESS_DENIED);
        }
        if (!command.sentenceVersionId().equals(detail.sentenceVersionId())) {
            throw new BusinessException(ApiCode.SENTENCE_NOT_FOUND, "题目版本与当前版本不一致");
        }
        return detail;
    }

    /**
     * 按句取可练习题目（答疑入口）：与 {@link #requirePracticeableSentence(Long, AttemptCommand)} 同一口径
     * （practiceable + 库可见或练习史豁免），但不做版本比对——答疑始终针对当前版本。
     *
     * @param userId 当前用户 id
     * @param sentenceId 题目 id
     * @return 题目详情
     */
    @Override
    public SentenceDetail requirePracticeableSentence(Long userId, String sentenceId) {
        SentenceDetail detail =
                contentQueryService
                        .findSentence(sentenceId)
                        .filter(SentenceDetail::practiceable)
                        .orElseThrow(() -> new BusinessException(ApiCode.SENTENCE_NOT_FOUND));
        if (!accessibleForPractice(userId, detail)) {
            throw new BusinessException(ApiCode.ACCESS_DENIED);
        }
        return detail;
    }

    /**
     * 练习端句级可访问性统一口径：库可见，或练习史豁免（本人已有该句复习卡）。
     *
     * <p>五条练习路径统一：交卷（{@link #requirePracticeableSentence}）、MANUAL 指定句、DUE 到期召回、 错题池、getAttempt
     * 回看。其中 DUE/错题池的候选本就来自本人复习卡（ {@code findDue/findLearning} 按 userId 过滤），豁免条件按构造成立，不做逐行复检；
     * getAttempt 在入口做一次判定（见 {@link #getAttempt}）。豁免只针对「库 visibility 变化」，不豁免「句被回收」——回收句由各路径既有的
     * practiceable 过滤排除，本判定不绕过它们。
     *
     * @param userId 当前用户 id
     * @param detail 题目详情（调用方已过 practiceable 过滤或明确需要判定内容可读性）
     * @return 可访问返回 true
     */
    private boolean accessibleForPractice(Long userId, SentenceDetail detail) {
        Long bankId = parseBankIdQuietly(detail.bankId());
        return (bankId != null && contentQueryService.isBankVisibleToUser(bankId, userId))
                || practiceHistoryExempt(userId, detail.id());
    }

    /**
     * 练习史豁免判定：本人已有该句复习卡即视为已获得过该内容的练习授权。 题库事后转为私有时，已有练习史的用户可继续到期复习、错题重练、交卷与回看， 消除「列表里看得到、交卷被 40300
     * 拒」的断链；无卡且库不可见则保持各路径既有拒绝语义。 一句一卡（{@code uk_review_card (user_id, sentence_id)}）保证判定稳定。
     *
     * @param userId 当前用户 id
     * @param sentenceId 题目 id
     * @return 本人已有复习卡返回 true
     */
    private boolean practiceHistoryExempt(Long userId, String sentenceId) {
        return reviewDao.findByUserIdAndSentenceId(userId, sentenceId).isPresent();
    }

    /**
     * 题库 id 字符串安全解析：detail.bankId 理论上恒为数字（实体 Long 的字符串化），脏数据时按「库不可见」静默处理，与 NextQuery
     * 的归一语义一致，不把解析异常放大成 500。
     *
     * @param bankId 题库 id 字符串
     * @return 数值 id；非数字返回 null
     */
    private static Long parseBankIdQuietly(String bankId) {
        try {
            return Long.parseLong(bankId);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * RETRY 链校验：必须携带 retryOfId，且来源提交同用户、同题、同模式。
     *
     * @param userId 用户 id
     * @param command 提交命令
     */
    private void validateRetryChain(Long userId, AttemptCommand command) {
        if (command.kind() != AttemptCommand.Kind.RETRY) {
            return;
        }
        if (command.retryOfId() == null || command.retryOfId().isBlank()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "RETRY 必须携带 retryOfId");
        }
        Attempt parent =
                attemptDao
                        .findById(command.retryOfId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ApiCode.ATTEMPT_NOT_FOUND, "重打来源提交不存在"));
        if (!parent.getUserId().equals(userId)
                || !parent.getSentenceId().equals(command.sentenceId())
                || !parent.getMode().name().equals(command.mode().name())) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "retryOfId 必须是同用户同题同模式的提交");
        }
    }

    /**
     * 按提交类型分流判分：REVEAL 不计分，FREE 整句覆盖，CLOZE 逐槽。
     *
     * <p>CLOZE 判分前装配申诉 overlay（DATA-MODEL §5.10）：本句已 ACCEPTED 的 APPEAL 把用户 主张的写法追加为该槽额外可接受答案（与
     * answers[] 同归一化口径，经 Grader 统一命中判定）。 overlay 只在服务端判分生效，游客（本地引擎）不受影响。
     *
     * @param detail 题目详情
     * @param command 提交命令
     * @return 判分结果
     */
    private GradingResult gradeForCommand(SentenceDetail detail, AttemptCommand command) {
        if (command.kind() == AttemptCommand.Kind.REVEAL) {
            return Grader.gradeForReveal(detail.chunks(), detail.blank());
        }
        if (command.mode() == AttemptCommand.Mode.FREE) {
            String text = command.answer().text() == null ? "" : command.answer().text();
            return Grader.gradeFree(detail.ref(), text);
        }
        List<Integer> blank = detail.blank() == null ? List.of() : detail.blank();
        List<String> answers = new ArrayList<>(blank.size());
        for (Integer chunkIndex : blank) {
            answers.add(slotValue(command.answer(), chunkIndex));
        }
        return Grader.gradeCloze(withAcceptedAppealOverlay(detail), blank, answers);
    }

    /**
     * 申诉 overlay：把本句已 ACCEPTED 的 APPEAL（kind=APPEAL）的 proposed_answer 追加为对应语块
     * 的额外可接受答案；语块列表原样克隆、仅命中的语块替换 answers，题目内容本身不改写（D-015）。
     *
     * <p>DISPUTE 的 ACCEPTED 是「排除批注」语义，不进本口径；未 ACCEPTED（PENDING/REJECTED） 的申诉不参与。空 overlay
     * 时原列表直接返回，避免无谓的逐块重建。
     *
     * @param detail 题目详情
     * @return 带 overlay 的语块列表（无 overlay 时为原列表）
     */
    private List<SentenceChunk> withAcceptedAppealOverlay(SentenceDetail detail) {
        List<Appeal> accepted = appealDao.findAcceptedAppeals(detail.id());
        if (accepted.isEmpty()) {
            return detail.chunks();
        }
        Map<Integer, List<String>> extraByChunk = new LinkedHashMap<>();
        for (Appeal appeal : accepted) {
            if (appeal.getProposedAnswer() == null
                    || appeal.getProposedAnswer().isBlank()
                    || appeal.getChunkIndex() < 0) {
                continue;
            }
            extraByChunk
                    .computeIfAbsent(appeal.getChunkIndex(), key -> new ArrayList<>())
                    .add(appeal.getProposedAnswer());
        }
        if (extraByChunk.isEmpty()) {
            return detail.chunks();
        }
        List<SentenceChunk> merged = new ArrayList<>(detail.chunks().size());
        for (SentenceChunk chunk : detail.chunks()) {
            List<String> extra = extraByChunk.get(chunk.index());
            if (extra == null) {
                merged.add(chunk);
                continue;
            }
            List<String> answers =
                    chunk.answers() == null ? new ArrayList<>() : new ArrayList<>(chunk.answers());
            for (String candidate : extra) {
                // 同一写法重复申诉/重复追加无意义：与既有答案归一化等值时跳过（判定在 Grader 归一口径下等价，保留原文差异无收益）。
                String normalizedCandidate = candidate.strip();
                boolean duplicated =
                        answers.stream()
                                .anyMatch(
                                        answer ->
                                                Norm1Normalizer.normalizeAnswer(answer)
                                                        .equals(
                                                                Norm1Normalizer.normalizeAnswer(
                                                                        normalizedCandidate)));
                if (!duplicated) {
                    answers.add(normalizedCandidate);
                }
            }
            merged.add(
                    new SentenceChunk(
                            chunk.index(),
                            chunk.zh(),
                            List.copyOf(answers),
                            chunk.tag(),
                            chunk.note()));
        }
        return List.copyOf(merged);
    }

    /**
     * 读取指定挖空语块的用户答案。
     *
     * @param answer 答案载荷
     * @param chunkIndex 语块下标
     * @return 用户输入，缺省为空串
     */
    private String slotValue(AnswerPayload answer, Integer chunkIndex) {
        if (answer.slots() == null) {
            return "";
        }
        return answer.slots().stream()
                .filter(slot -> chunkIndex.equals(slot.chunkIndex()))
                .map(AnswerPayload.Slot::value)
                .findFirst()
                .map(value -> value == null ? "" : value)
                .orElse("");
    }

    /**
     * SRS 四分支推进复习卡并写排程日志；RETRY 与「不该排程」的提交原样返回 null。
     *
     * @param attempt 提交
     * @param score 判分；REVEAL 为 null
     * @param rating 四档评分
     * @param command 提交命令
     * @param now 服务端时钟
     * @return 更新后的复习卡；未排程时为 null
     */
    private Review scheduleReview(
            Attempt attempt, Integer score, int rating, AttemptCommand command, Instant now) {
        if (attempt.getKind() == Attempt.AttemptKind.RETRY) {
            // RETRY 不排程：响应带回当前卡投影，但排程字段原样不动。
            return reviewDao
                    .findByUserIdAndSentenceId(attempt.getUserId(), attempt.getSentenceId())
                    .orElse(null);
        }
        // 只有 FIRST 与「没有 FIRST 的 REVEAL」触发排程（DATA-MODEL §8.2）。
        boolean schedulable =
                attempt.getKind() == Attempt.AttemptKind.FIRST
                        || !attemptDao.existsByUserAndSentenceAndModeAndKind(
                                attempt.getUserId(),
                                attempt.getSentenceId(),
                                attempt.getMode(),
                                Attempt.AttemptKind.FIRST);
        if (!schedulable) {
            return null;
        }
        boolean failed = score == null || score < 100;
        Review card =
                reviewDao
                        .findByUserIdAndSentenceId(attempt.getUserId(), attempt.getSentenceId())
                        .orElse(null);
        // 调度消费 ACTIVE 权重：四分支公式形状不变，五个常数来自权重版本（GLOBAL 原型种子
        // 解码后即本地引擎常量 2.4/5.3/3.2/5.1/40.0，golden 逐值不变；启用优化器候选后同公式换参）。
        FsrsScheduler.ActiveSchedule active = fsrsScheduler.activeGlobal();
        long weightsVersionId = active.versionId();
        FsrsScheduleParams params = active.params();

        if (failed) {
            if (card == null) {
                Review created =
                        Review.createFailed(
                                attempt.getUserId(),
                                attempt.getSentenceId(),
                                attempt.getMode(),
                                attempt.getId(),
                                rating,
                                params.failStability(),
                                params.failDifficulty(),
                                now);
                try {
                    // 建卡走独立新事务（REQUIRES_NEW）：并发同句双 FIRST 双方都读到无卡时，
                    // uk_review_card 兜底——输家的 INSERT 冲突留在内层事务里干净回滚，外层会话不残留
                    // 失败动作。不能在外层事务直接 saveAndFlush：IDENTITY 插入失败后实体以无 id 的
                    // SAVING 状态挂在会话里，detach 因 EntityKey 缺失不可用，提交前再次 flush 会复现
                    // 同一条冲突把整个提交打成 500。代价：卡行先于本事务（attempt/grading 稍后）提交，
                    // V10 后无物理外键，极小概率留下引用未落库 attempt 的卡，可自愈（下次提交按既有卡更新）。
                    freshTxTemplate.executeWithoutResult(status -> reviewDao.saveAndFlush(created));
                } catch (DataIntegrityViolationException exception) {
                    // 并发同句双 FIRST（双端/重发，两个不同 attemptId）：对方抢先建卡。与 attempts
                    // 幂等的差异在于输家不能回滚——本事务已落库合法的 attempt/grading，回滚等于丢作答。
                    // 唯一键冲突同时证明赢家事务已提交（InnoDB 对未提交行的重复键插入会等待，
                    // 等到的是对方 commit），独立新事务的新快照必能读到卡：读回后原样复用，
                    // 输家本次不再重复计一次失败（同一作答动因重复 applyFailed 会双计 lapse），
                    // 双方收敛到一张卡；输家的 review_logs 缺行是该设计的已知代价。
                    return freshTxTemplate.execute(
                            status ->
                                    reviewDao
                                            .findByUserIdAndSentenceId(
                                                    attempt.getUserId(), attempt.getSentenceId())
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "并发建卡冲突后未读到复习卡: "
                                                                            + attempt
                                                                                    .getSentenceId())));
                }
                reviewLogDao.save(
                        ReviewLog.forNewCard(
                                created,
                                attempt.getId(),
                                rating,
                                attempt.getDurationMs(),
                                weightsVersionId,
                                now));
                return created;
            }
            CardUpdate update =
                    persistExistingCardUpdate(
                            card,
                            attempt.getUserId(),
                            attempt.getSentenceId(),
                            now,
                            target -> {
                                target.applyFailed(
                                        attempt.getId(),
                                        rating,
                                        params.failStability(),
                                        params.failDifficulty(),
                                        now);
                                target.touchMode(attempt.getMode());
                            });
            reviewLogDao.save(
                    logForExisting(
                            update.snapshot(),
                            update.card(),
                            attempt,
                            rating,
                            weightsVersionId,
                            now));
            return update.card();
        }

        // 全对：新题不入队；学习中推进间隔；已归档只刷新痕迹；重置后的 NEW 卡不动。
        if (card == null || card.getState() == Review.ReviewState.NEW) {
            return null;
        }
        CardUpdate update =
                persistExistingCardUpdate(
                        card,
                        attempt.getUserId(),
                        attempt.getSentenceId(),
                        now,
                        target -> {
                            // 分支在变更时按卡当前状态重判：乐观锁重试重读到新状态时，分支随之收敛。
                            if (target.getState() == Review.ReviewState.LEARNING) {
                                target.applyLearningGraduated(
                                        attempt.getId(),
                                        rating,
                                        params.learningStability(),
                                        params.learningDifficulty(),
                                        params.masteredStability(),
                                        now);
                                target.touchMode(attempt.getMode());
                            } else {
                                target.applyMasteredReview(attempt.getId(), rating, now);
                            }
                        });
        reviewLogDao.save(
                logForExisting(
                        update.snapshot(), update.card(), attempt, rating, weightsVersionId, now));
        return update.card();
    }

    /**
     * 既有卡排程更新的持久化：条件 UPDATE 以更新前的 {@code last_review_at} 为乐观锁令牌，并发双端同时更新同一张学习卡时只有一方落库。
     *
     * <p><strong>选型</strong>：不用 JPA {@code @Version}——那需要新列， 而排程路径上卡必有非空 {@code
     * last_review_at}（建卡与每次排程都写它，NEW 卡在上游被排除）， 是现成的天然令牌。影响行数为 0 时重试一档 （重读卡、以新令牌重放同一排程动因）而非直接
     * 409：双端并发的语义是「两次提交先后生效」， 重试即收敛，不把良性竞态变成用户可见失败； 重试后仍过期（极少见的持续争用）才按 40900 冲突拒绝，客户端重提交即可。
     * 重放会以最新卡状态重算 elapsedDays 并重写 review_logs 的 before 值，日志与卡保持一致。
     *
     * <p>实体必须在条件更新前 detach：托管实体留在会话里，提交期脏检查会再发一条无条件 UPDATE， 绕过令牌守卫（lost update 防线形同虚设）。
     *
     * @param card 读到的既有卡（未变更，令牌来源）
     * @param userId 用户 id（重试重读用）
     * @param sentenceId 题目 ULID（重试重读用）
     * @param now 服务端时钟
     * @param mutation 排程变更（快照之后、条件更新之前应用到卡上；重试时对重读卡重放）
     * @return 落库成功的卡与其变更前快照
     */
    private CardUpdate persistExistingCardUpdate(
            Review card, Long userId, String sentenceId, Instant now, Consumer<Review> mutation) {
        Review target = card;
        for (int round = 0; round < 2; round++) {
            // 快照先于变更：before 值与乐观锁令牌都必须来自变更前的卡。
            Review.Snapshot snapshot = Review.Snapshot.of(target, now);
            mutation.accept(target);
            reviewDao.detach(target);
            if (reviewDao.updateSchedulingState(target, snapshot.lastReviewAt()) == 1) {
                return new CardUpdate(target, snapshot);
            }
            if (round == 0) {
                // 重读必须用 REQUIRES_NEW：外层事务在 REPEATABLE READ 下同事务重读仍见旧快照，
                // 重试必败；只有独立新事务才能读到赢家已提交的行（与建卡竞态同一结论）。
                // 内层只读不写，条件更新仍在外层事务执行，保住本次提交的原子性。
                target =
                        freshTxTemplate.execute(
                                status ->
                                        reviewDao
                                                .findByUserIdAndSentenceId(userId, sentenceId)
                                                .orElseThrow(
                                                        () ->
                                                                new BusinessException(
                                                                        ApiCode.REVIEW_NOT_FOUND)));
            }
        }
        throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "复习状态刚被其他设备更新，请重新提交");
    }

    /** 一次既有卡更新的结果：落库成功的卡与它对应的变更前快照（review_logs 数据源）。 */
    private record CardUpdate(Review card, Review.Snapshot snapshot) {}

    /**
     * 构造既有卡的排程日志（before 取自快照，after 取自更新后的卡）。
     *
     * @param snapshot 更新前快照
     * @param card 更新后的卡
     * @param attempt 提交
     * @param rating 评分
     * @param weightsVersionId 权重版本行
     * @param now 服务端时钟
     * @return 未持久化日志
     */
    private ReviewLog logForExisting(
            Review.Snapshot snapshot,
            Review card,
            Attempt attempt,
            int rating,
            Long weightsVersionId,
            Instant now) {
        return ReviewLog.forExistingCard(
                card,
                snapshot.state(),
                snapshot.dueAt(),
                snapshot.stability(),
                snapshot.difficulty(),
                snapshot.elapsedDays(),
                attempt.getId(),
                rating,
                attempt.getDurationMs(),
                weightsVersionId,
                now);
    }

    /**
     * 四档评分派生（DATA-MODEL §8.2 / engine.ts deriveRating）： REVEAL、无分、&lt;60 → 1；&lt;100 → 2；全对有提示 →
     * 3；全对无提示 ≤60s → 4，否则 3。
     *
     * @param score 判分；REVEAL 为 null
     * @param hintsUsed 提示次数
     * @param durationMs 耗时，可空
     * @param kind 提交类型
     * @return 1–4
     */
    private int deriveRating(
            Integer score, int hintsUsed, Integer durationMs, AttemptCommand.Kind kind) {
        if (kind == AttemptCommand.Kind.REVEAL || score == null || score < 60) {
            return 1;
        }
        if (score < 100) {
            return 2;
        }
        if (hintsUsed > 0) {
            return 3;
        }
        return durationMs != null && durationMs <= EASY_DURATION_MS ? 4 : 3;
    }

    /**
     * 幂等载荷哈希：SHA-256(规范化 JSON{sentenceId,sentenceVersionId,mode,kind,retryOfId,answer,hintsUsed})。
     *
     * @param command 提交命令
     * @return 64 位十六进制
     */
    private String payloadHash(AttemptCommand command) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("sentenceId", command.sentenceId());
        canonical.put("sentenceVersionId", command.sentenceVersionId());
        canonical.put("mode", command.mode().name());
        canonical.put("kind", command.kind().name());
        canonical.put("retryOfId", command.retryOfId());
        canonical.put("answer", command.answer());
        canonical.put("hintsUsed", command.effectiveHintsUsed());
        byte[] bytes = objectMapper.writeValueAsBytes(canonical);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            // JDK 必带 SHA-256，不可能到达；保留受检异常语义。
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /**
     * 版本 id 解析：请求里是字符串化的 BIGINT。
     *
     * @param sentenceVersionId 字符串版本 id
     * @return 数值版本 id
     */
    private long parseVersionId(String sentenceVersionId) {
        try {
            return Long.parseLong(sentenceVersionId);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ApiCode.SENTENCE_NOT_FOUND, "题目版本不合法");
        }
    }

    /**
     * 命令模式 → 实体模式。
     *
     * @param mode 命令模式
     * @return 实体模式
     */
    private Attempt.AttemptMode toEntityMode(AttemptCommand.Mode mode) {
        return Attempt.AttemptMode.valueOf(mode.name());
    }

    /**
     * 命令类型 → 实体类型。
     *
     * @param kind 命令类型
     * @return 实体类型
     */
    private Attempt.AttemptKind toEntityKind(AttemptCommand.Kind kind) {
        return Attempt.AttemptKind.valueOf(kind.name());
    }

    @Override
    public NextPracticeView next(Long userId, NextQuery query) {
        // 显式 bankId 过滤的前置校验（API §6.1 错误表）：库不存在/不可见回 40401，
        // 不吞成「无可练题」空态让客户端误判为该库无题；
        // 库可见但无题仍返回 data=null 空态。
        if (query.bankId() != null) {
            contentQueryService.requireVisibleBank(query.bankId(), userId);
        }
        // 指定句优先（复习页「重练」/下一步训练的显式动作）：命中且可练直接返回，
        // 忽略 bankId/tag/exclude/errors；找不到（已删/不可练）或不可访问（库不可见且无练习史）
        // 退回常规取题，不 404，避免死链把练习页打成空白。有卡即 MANUAL（含重置后的 NEW 卡），
        // 无卡是首练按 NEW。
        if (query.sentenceId() != null) {
            Optional<SentenceDetail> specified =
                    contentQueryService
                            .findSentence(query.sentenceId())
                            .filter(SentenceDetail::practiceable)
                            // 所属库对当前用户不可见（私有库非本人、匿名访问私有库、库未发布/已软删）
                            // 与「找不到」同语义：静默退回常规取题，不泄露句存在性，也不回显内容
                            // （练习史豁免见 accessibleForPractice——本人已有卡仍可
                            // 指定句重练，库转私不断已开始的练习）。
                            .filter(detail -> accessibleForPractice(userId, detail));
            if (specified.isPresent()) {
                Review card =
                        reviewDao
                                .findByUserIdAndSentenceId(userId, query.sentenceId())
                                .orElse(null);
                return new NextPracticeView(
                        specified.get(),
                        card == null ? null : toCardView(card),
                        card == null ? "NEW" : "MANUAL");
            }
        }
        // 今日页「错题」chip（errors=1）：只在错题池里取，口径与错题列表一致（§6.6），
        // bankId/tag/exclude 是显式筛选需要尊重；按 dueAt 升序取最紧急；mode 不过滤——
        // 错题是句子级召回，练习模式由页面当前选择决定。空池返回 null（HTTP 200 空态）。
        if (query.errors()) {
            for (WrongPoolRow row : wrongPool(userId, query.bankId(), query.tag(), false)) {
                if (query.excludes(row.card().getSentenceId())) {
                    continue;
                }
                return new NextPracticeView(row.detail(), toCardView(row.card()), "DUE");
            }
            return null;
        }
        // 到期复习优先：due_at 升序取第一张可练卡的题目；tag 与排除集合与本地引擎
        // pickNext 同口径（显式筛选同样约束到期召回）。bankId 不约束 DUE 为既有服务端行为。
        // 练习史豁免：候选即本人复习卡，可访问性按构造成立——库转私不断
        // 已开始的到期复习；回收句由 practiceable 过滤排除（tombstone 跳过），豁免不适用。
        for (Review card : reviewDao.findDue(userId, Instant.now())) {
            if (query.excludes(card.getSentenceId())) {
                continue;
            }
            Optional<SentenceDetail> detail =
                    contentQueryService.findSentence(card.getSentenceId());
            if (detail.isPresent()
                    && detail.get().practiceable()
                    && matchTag(detail.get(), query.tag())) {
                return new NextPracticeView(detail.get(), toCardView(card), "DUE");
            }
        }
        // 新题：可见性（公开库或本人私有库）与「未入队」（无非 NEW 卡）都已在候选 SQL 下推
        // （数据层为准），20 只是一次返回的窗口上限——被过滤行不占名额，
        // 前 20 句练完不再把召回窗口占满；与本地引擎 selectNew 同语义。
        for (SentenceDetail candidate :
                contentQueryService.findPracticeCandidates(
                        userId, query.bankId(), query.excludeIds(), query.tag(), 20)) {
            Optional<Review> card = reviewDao.findByUserIdAndSentenceId(userId, candidate.id());
            return new NextPracticeView(candidate, card.map(this::toCardView).orElse(null), "NEW");
        }
        return null;
    }

    @Override
    public PageResponse<WrongQuestionView> wrongQuestions(
            Long userId, int page, int size, boolean includeDisputed, Long bankId, String tag) {
        List<WrongPoolRow> pool = wrongPool(userId, bankId, tag, includeDisputed);
        // disputed overlay 一次批量取全池，避免逐行查 appeals（N+1）。
        List<String> poolSentenceIds =
                pool.stream().map(row -> row.card().getSentenceId()).toList();
        Map<String, Set<String>> disputedKeysBySentence =
                poolSentenceIds.isEmpty()
                        ? Map.of()
                        : appealDao.findDisputed(userId, poolSentenceIds).stream()
                                .collect(
                                        Collectors.groupingBy(
                                                Appeal::getSentenceId,
                                                Collectors.mapping(
                                                        Appeal::getAnnotationKey,
                                                        Collectors.toSet())));
        List<WrongQuestionView> items = new ArrayList<>();
        for (WrongPoolRow row : pool) {
            Set<String> disputedKeys =
                    disputedKeysBySentence.getOrDefault(row.card().getSentenceId(), Set.of());
            List<WrongQuestionView.WrongAnnotationView> annotations =
                    row.wrongAnnotations().stream()
                            .map(
                                    annotation ->
                                            new WrongQuestionView.WrongAnnotationView(
                                                    annotation.key(),
                                                    annotation.tag(),
                                                    annotation.message(),
                                                    disputedKeys.contains(annotation.key())))
                            // 被排除的批注默认从视图隐藏（includeDisputed=true 才显示）；
                            // 行级隐藏已在错题池判定，这里只做批注级过滤。
                            .filter(annotation -> includeDisputed || !annotation.disputed())
                            .toList();
            items.add(
                    new WrongQuestionView(
                            row.card().getSentenceId(),
                            row.card().getMode().name(),
                            row.card().getState().name(),
                            row.card().getDueAt(),
                            row.lastScore(),
                            row.latestAttemptId(),
                            gradingDao.countWrongByUserAndSentence(
                                    userId, row.card().getSentenceId()),
                            row.detail().tags() == null ? List.of() : row.detail().tags(),
                            row.detail().zh(),
                            annotations));
        }
        return pageOf(items, page, size);
    }

    /**
     * 错题池：LEARNING 卡 + 首判未全对 + 题库/考点过滤（API §6.6）。
     *
     * <p>错题列表（wrongQuestions）与 errors=1 取题（next）共用同一口径，避免「今日页错题 chip
     * 推的题不在错题列表里」的割裂。行级语义：最近一次提交的**首判**未全对（REVEAL 的 null 分也算 未全对）——读 sequence=1
     * 行是因为模型复核只追加判分行不改错题队列，复核分不参与错题进出池； 批注全被「不认同」且无其他错误的题隐藏（includeDisputed=false 时），看答案无批注的行同样
     * 不进池（与既有错题列表行为一致，这里与本地引擎/mock 的唯一偏差，已在 API §6.1 登记）。 顺序沿用 {@code findLearning} 的 dueAt 升序，同
     * dueAt 保持稳定序。
     *
     * <p>练习史豁免：池候选即本人复习卡，可访问性按构造成立——库转私不断 错题重练；回收句由 practiceable 过滤排除（tombstone 不进池），豁免不适用。
     *
     * @param userId 用户 id
     * @param bankId 题库过滤，可空
     * @param tag 考点过滤，可空
     * @param includeDisputed true 时批注全被排除的行仍入池（错题列表 includeDisputed=true 口径）
     * @return 错题池行，dueAt 升序
     */
    private List<WrongPoolRow> wrongPool(
            Long userId, Long bankId, String tag, boolean includeDisputed) {
        List<Review> learning = reviewDao.findLearning(userId);
        List<String> sentenceIds = learning.stream().map(Review::getSentenceId).toList();
        Map<String, List<Appeal>> disputesBySentence =
                sentenceIds.isEmpty()
                        ? Map.of()
                        : appealDao.findDisputed(userId, sentenceIds).stream()
                                .collect(Collectors.groupingBy(Appeal::getSentenceId));
        List<WrongPoolRow> pool = new ArrayList<>();
        for (Review card : learning) {
            SentenceDetail detail =
                    contentQueryService
                            .findSentence(card.getSentenceId())
                            .filter(SentenceDetail::practiceable)
                            .orElse(null);
            if (detail == null || !matchBank(detail, bankId) || !matchTag(detail, tag)) {
                continue;
            }
            Optional<Attempt> latestAttempt =
                    attemptDao.findLatestByUserAndSentence(userId, card.getSentenceId());
            // 错题池读首判（sequence=1）而不是最新判分行：模型复核会追加更高 sequence 的行，
            // 若错题判定跟着复核分走，复核一次就能把题目移出/移入错题队列，与
            // 「复核不影响复习计划」的口径冲突；复核前首判即唯一判分，行为不变。
            Optional<AttemptGrading> firstGrading =
                    latestAttempt.flatMap(
                            attempt -> gradingDao.findFirstByAttemptId(attempt.getId()));
            if (latestAttempt.isEmpty() || firstGrading.isEmpty()) {
                continue;
            }
            Integer lastScore = firstGrading.get().getScore();
            // 错题口径：首判未全对（REVEAL 的 null 分也算未全对）。
            if (lastScore != null && lastScore >= 100) {
                continue;
            }
            Set<String> disputedKeys =
                    disputesBySentence.getOrDefault(card.getSentenceId(), List.of()).stream()
                            .map(Appeal::getAnnotationKey)
                            .collect(Collectors.toSet());
            List<GradingAnnotation> wrongAnnotations =
                    firstGrading.get().getResult() == null
                            ? List.of()
                            : objectMapper
                                    .readValue(firstGrading.get().getResult(), GradingResult.class)
                                    .annotations()
                                    .stream()
                                    .filter(annotation -> !annotation.correct())
                                    .toList();
            // 批注全部被排除且无其他错误的题不出现在错题列表（API §6.6）；
            // includeDisputed=true 时被排除批注仍可见，故只要求存在错批注。
            boolean hasVisibleAnnotation =
                    wrongAnnotations.stream()
                            .anyMatch(
                                    annotation ->
                                            includeDisputed
                                                    || !disputedKeys.contains(annotation.key()));
            if (!hasVisibleAnnotation) {
                continue;
            }
            pool.add(
                    new WrongPoolRow(
                            card,
                            detail,
                            lastScore,
                            latestAttempt.get().getId(),
                            wrongAnnotations));
        }
        return pool;
    }

    /** 错题池行：复习卡 + 判分快照，错题列表与 errors=1 取题共用。 */
    private record WrongPoolRow(
            Review card,
            SentenceDetail detail,
            Integer lastScore,
            String latestAttemptId,
            List<GradingAnnotation> wrongAnnotations) {}

    @Override
    public PageResponse<ReviewCardView> reviews(
            Long userId, int page, int size, String state, String mode, boolean due) {
        Review.ReviewState stateFilter = parseState(state);
        Instant now = Instant.now();
        List<ReviewCardView> items =
                reviewDao.findAllByUser(userId).stream()
                        .filter(card -> stateFilter == null || card.getState() == stateFilter)
                        .filter(card -> mode == null || card.getMode().name().equals(mode))
                        .filter(card -> !due || !card.getDueAt().isAfter(now))
                        .sorted(Comparator.comparing(Review::getDueAt))
                        .map(this::toCardView)
                        .toList();
        return pageOf(items, page, size);
    }

    @Override
    @Transactional
    public ReviewCardView resetReview(Long userId, Long reviewId, String reason) {
        Review card =
                reviewDao
                        .findById(reviewId)
                        .filter(review -> review.getUserId().equals(userId))
                        .orElseThrow(() -> new BusinessException(ApiCode.REVIEW_NOT_FOUND));
        // 重置不写 review_logs（非 FSRS 事件）；理由进结构化日志供检索。
        log.info("review_reset userId={} reviewId={} reason={}", userId, reviewId, reason);
        card.reset(Instant.now());
        return toCardView(reviewDao.save(card));
    }

    @Override
    public OverloadView overload(Long userId) {
        long dueCount = reviewDao.countDue(userId, Instant.now());
        return new OverloadView(
                dueCount, OVERLOAD_THRESHOLD, PUSH_LIMIT, dueCount > OVERLOAD_THRESHOLD);
    }

    @Override
    @Transactional
    public PostponeView postpone(Long userId, String reason) {
        List<Review> due = reviewDao.findDue(userId, Instant.now());
        long postponed = 0;
        // 推送上限内的最早卡保留今日，其余整体顺延（降载口径；天数对齐原型 3 天，见 POSTPONE_DAYS）。
        for (int index = PUSH_LIMIT; index < due.size(); index++) {
            Review card = due.get(index);
            card.postponeDays(POSTPONE_DAYS);
            reviewDao.save(card);
            postponed++;
        }
        log.info(
                "review_postpone userId={} postponed={} kept={} reason={}",
                userId,
                postponed,
                Math.min(due.size(), PUSH_LIMIT),
                reason);
        return new PostponeView(postponed, Math.min(due.size(), PUSH_LIMIT));
    }

    @Override
    public TodayView today(Long userId) {
        UserSettings settings = userAccountService.getSettings(userId);
        ZoneId zone = ZoneId.of(settings.timezone());
        LocalDate today = LocalDate.now(zone);
        Instant now = Instant.now();
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
        long doneCount = attemptDao.countByUserBetween(userId, dayStart, dayEnd);
        // 全对率：今日有分数判分的提交中 score=100 占比。
        List<Integer> scores =
                attemptDao.findByUserBetween(userId, dayStart, dayEnd).stream()
                        .map(attempt -> gradingDao.findLatestByAttemptId(attempt.getId()))
                        .flatMap(Optional::stream)
                        .map(AttemptGrading::getScore)
                        .filter(Objects::nonNull)
                        .toList();
        long correct = scores.stream().filter(score -> score == 100).count();
        Integer correctRate =
                scores.isEmpty() ? null : (int) Math.round(correct * 100.0 / scores.size());
        return new TodayView(
                today,
                settings.timezone(),
                reviewDao.countDue(userId, now),
                doneCount,
                settings.dailyGoal(),
                correctRate,
                streakDays(userId, zone, today));
    }

    /**
     * 连续练习天数：以用户本地日界回数；今天未练则从昨天起算（不惩罚当天中断）。
     *
     * @param userId 用户 id
     * @param zone 用户时区
     * @param today 用户本地今天
     * @return 连续天数
     */
    private int streakDays(Long userId, ZoneId zone, LocalDate today) {
        LocalDate windowStart = today.minusDays(59);
        Set<LocalDate> days =
                attemptDao
                        .findByUserBetween(
                                userId,
                                windowStart.atStartOfDay(zone).toInstant(),
                                today.plusDays(1).atStartOfDay(zone).toInstant())
                        .stream()
                        .map(attempt -> attempt.getCreatedAt().atZone(zone).toLocalDate())
                        .collect(Collectors.toSet());
        int streak = 0;
        LocalDate cursor = days.contains(today) ? today : today.minusDays(1);
        while (days.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    @Override
    public AccuracyView accuracy(
            Long userId, LocalDate from, LocalDate to, Long bankId, String mode) {
        UserSettings settings = userAccountService.getSettings(userId);
        ZoneId zone = ZoneId.of(settings.timezone());
        LocalDate today = LocalDate.now(zone);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(13) : from;
        if (start.isAfter(end)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "from 不能晚于 to");
        }
        requireRangeWithinLimit(start, end);
        List<Attempt> attempts = filterAttempts(userId, start, end, zone, bankId, mode);
        // 最新判分一次 IN 查询装配，总/分模式/逐日/最近共用，替代逐 attempt 的 N+1。
        Map<String, AttemptGrading> latestGradings = latestGradingsByAttempt(attempts);
        List<Integer> scores = scoresOf(attempts, latestGradings);
        long totalAttempts = attempts.size();
        Double correctRate =
                scores.isEmpty() ? null : round1(correctCount(scores) * 100.0 / scores.size());
        Double averageScore =
                scores.isEmpty()
                        ? null
                        : round1(scores.stream().mapToInt(Integer::intValue).average().orElse(0));
        List<AccuracyView.ModeAccuracyView> byMode = new ArrayList<>();
        for (AttemptCommand.Mode m : AttemptCommand.Mode.values()) {
            List<Attempt> modeAttempts =
                    attempts.stream()
                            .filter(attempt -> attempt.getMode().name().equals(m.name()))
                            .toList();
            if (modeAttempts.isEmpty()) {
                continue;
            }
            List<Integer> modeScores = scoresOf(modeAttempts, latestGradings);
            byMode.add(
                    new AccuracyView.ModeAccuracyView(
                            m.name(),
                            modeAttempts.size(),
                            modeScores.isEmpty()
                                    ? null
                                    : round1(
                                            correctCount(modeScores) * 100.0 / modeScores.size())));
        }
        List<AccuracyView.DailyAccuracyView> daily14 = new ArrayList<>();
        for (LocalDate day = end.minusDays(13); !day.isAfter(end); day = day.plusDays(1)) {
            LocalDate finalDay = day;
            List<Attempt> dayAttempts =
                    attempts.stream()
                            .filter(
                                    attempt ->
                                            attempt.getCreatedAt()
                                                    .atZone(zone)
                                                    .toLocalDate()
                                                    .equals(finalDay))
                            .toList();
            List<Integer> dayScores = scoresOf(dayAttempts, latestGradings);
            daily14.add(
                    new AccuracyView.DailyAccuracyView(
                            day,
                            dayAttempts.size(),
                            dayScores.isEmpty()
                                    ? null
                                    : round1(correctCount(dayScores) * 100.0 / dayScores.size())));
        }
        List<AccuracyView.RecentAttemptView> recent =
                attempts.stream()
                        .sorted(Comparator.comparing(Attempt::getCreatedAt).reversed())
                        .limit(10)
                        .map(
                                attempt ->
                                        new AccuracyView.RecentAttemptView(
                                                attempt.getId(),
                                                attempt.getSentenceId(),
                                                attempt.getMode().name(),
                                                attempt.getKind().name(),
                                                latestScoreOf(attempt.getId(), latestGradings),
                                                attempt.getCreatedAt()))
                        .toList();
        return new AccuracyView(
                start, end, totalAttempts, correctRate, averageScore, byMode, daily14, recent);
    }

    @Override
    public ErrorTagsView errorTags(Long userId, LocalDate from, LocalDate to) {
        UserSettings settings = userAccountService.getSettings(userId);
        ZoneId zone = ZoneId.of(settings.timezone());
        LocalDate today = LocalDate.now(zone);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(13) : from;
        if (start.isAfter(end)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "from 不能晚于 to");
        }
        requireRangeWithinLimit(start, end);
        List<Attempt> attempts = filterAttempts(userId, start, end, zone, null, null);
        Set<String> disputedKeys =
                appealDao
                        .findDisputed(
                                userId,
                                attempts.stream().map(Attempt::getSentenceId).distinct().toList())
                        .stream()
                        .map(appeal -> appeal.getSentenceId() + "|" + appeal.getAnnotationKey())
                        .collect(Collectors.toSet());
        Map<String, AttemptGrading> latestGradings = latestGradingsByAttempt(attempts);
        Map<String, Long> counts = new LinkedHashMap<>();
        long[] total = {0};
        for (Attempt attempt : attempts) {
            AttemptGrading grading = latestGradings.get(attempt.getId());
            if (grading == null) {
                continue;
            }
            GradingResult result = objectMapper.readValue(grading.getResult(), GradingResult.class);
            for (GradingAnnotation annotation : result.annotations()) {
                if (annotation.correct()
                        || disputedKeys.contains(
                                attempt.getSentenceId() + "|" + annotation.key())) {
                    continue;
                }
                counts.merge(annotation.tag(), 1L, Long::sum);
                total[0]++;
            }
        }
        long grandTotal = total[0];
        List<ErrorTagsView.TagCountView> items =
                counts.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .map(
                                entry ->
                                        new ErrorTagsView.TagCountView(
                                                entry.getKey(),
                                                entry.getValue(),
                                                grandTotal == 0
                                                        ? 0.0
                                                        : round1(
                                                                entry.getValue()
                                                                        * 100.0
                                                                        / grandTotal)))
                        .toList();
        return new ErrorTagsView(start, end, items);
    }

    @Override
    @Transactional
    public GuestUpgradeView guestUpgrade(Long userId, GuestUpgradeCommand command) {
        int imported = 0;
        int skipped = 0;
        if (command == null || command.attempts() == null) {
            return new GuestUpgradeView(0, 0, 0);
        }
        // Bean Validation 在控制器层挡 @Size(max=100)；service 层复检防直调绕过。
        if (command.attempts().size() > 100) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "单次最多迁移 100 条");
        }
        for (AttemptCommand item : command.attempts()) {
            Optional<Attempt> existing = attemptDao.findById(item.attemptId());
            if (existing.isPresent()) {
                Attempt stored = existing.get();
                if (stored.getUserId().equals(userId)
                        && stored.getAnswerHash().equals(payloadHash(item))) {
                    skipped++;
                    continue;
                }
                // 异载荷：抛出后整个事务回滚（40903 整体拒绝）。
                throw new BusinessException(ApiCode.ATTEMPT_ID_REUSED);
            }
            SentenceDetail detail = requirePracticeableSentence(userId, item);
            validateRetryChain(userId, item);
            Instant now = Instant.now();
            Attempt attempt =
                    Attempt.create(
                            item.attemptId(),
                            userId,
                            null,
                            item.sentenceId(),
                            parseVersionId(item.sentenceVersionId()),
                            detail.questionVersion() == null ? 0 : detail.questionVersion(),
                            toEntityMode(item.mode()),
                            toEntityKind(item.kind()),
                            item.retryOfId(),
                            objectMapper.writeValueAsString(item.answer()),
                            payloadHash(item),
                            (short) item.effectiveHintsUsed(),
                            item.durationMs(),
                            item.clientCreatedAt(),
                            now);
            try {
                // 立即 flush：让并发同 id 的主键冲突在本方法内被捕获（理由同 submitAttempt）。
                attemptDao.saveAndFlush(attempt);
            } catch (DataIntegrityViolationException exception) {
                // 并发同 id：本事务的 INSERT 已失败，整批必须放弃（失败的 INSERT 动作挂在会话里，
                // 继续处理后续行会在提交前复现冲突，且已写行随后都会被回滚）。标记回滚后在新事务里
                // 读回赢家比对：同载荷按 40900「请勿重复提交」提示重试——重试时预检命中走 skip 路径；
                // 异载荷仍 40903。两种情况都不再把冲突泄漏成 500。
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                Boolean sameWinner =
                        freshTxTemplate.execute(
                                status -> {
                                    Attempt winner =
                                            attemptDao.findById(item.attemptId()).orElse(null);
                                    return winner != null
                                            && winner.getUserId().equals(userId)
                                            && winner.getAnswerHash().equals(payloadHash(item));
                                });
                throw new BusinessException(
                        Boolean.TRUE.equals(sameWinner)
                                ? ApiCode.IDEMPOTENT_CONFLICT
                                : ApiCode.ATTEMPT_ID_REUSED);
            }
            GradingResult result = gradeForCommand(detail, item);
            gradingDao.save(
                    AttemptGrading.createLocal(
                            item.attemptId(),
                            1,
                            result.score(),
                            objectMapper.writeValueAsString(result),
                            now));
            imported++;
            // 只落 attempts/attempt_gradings，不触发 FSRS 排程：迁移的是历史记录而非队列。
        }
        return new GuestUpgradeView(imported, skipped, 0);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ModelReviewView reviewAttempt(Long userId, String attemptId) {
        // 029 事务化（A-02 修复）：整个复核在一个 READ_COMMITTED 事务内。模型外呼在事务里持着
        // attempts 行锁与一条连接（最长约 31.5s）——这是已批准计划的明示取舍：复核低频
        // （5 次/分钟限流），行级锁只串行化同一 attempt 的复核，不碰其他提交的并发；
        // 换来的是幂等闸、sequence 计算、MODEL 行落库三步的原子性，并发复核不再可能落两条
        // MODEL 行并双份消耗 token。READ_COMMITTED 而非默认 RR：后到者在行锁上等到先到者提交后，
        // 幂等闸的重读必须看到先到者已提交的 MODEL 行（RR 的旧快照会漏看，导致二次调模型再撞 uk）。
        Attempt attempt =
                attemptDao
                        .findById(attemptId)
                        .orElseThrow(() -> new BusinessException(ApiCode.ATTEMPT_NOT_FOUND));
        if (!attempt.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.ACCESS_DENIED);
        }
        // 句子内容可读性复用作答详情（getAttempt）口径：库转私且无练习史豁免的提交不再回显
        // 参考译文，复核同样拒绝——复核提示词里含参考译文与语块答案，不能绕过内容边界。
        // 回看容许句子消失（重放落库 JSON 即可），复核必须现场取原句/参考译文拼提示词，
        // 所以句子取不到（已删/已回收）与不可读同判：40300「句子不可读」。
        SentenceDetail detail =
                contentQueryService.findSentence(attempt.getSentenceId()).orElse(null);
        if (detail == null || !accessibleForPractice(userId, detail)) {
            throw new BusinessException(ApiCode.ACCESS_DENIED);
        }
        AttemptGrading first =
                gradingDao
                        .findFirstByAttemptId(attemptId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ApiCode.INTERNAL_ERROR, "判分记录缺失：" + attemptId));
        if (first.getScore() == null) {
            // REVEAL 提交没有判分（score=null）：没有可复核的对象，属客户端请求错误。
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "该提交没有可复核的判分");
        }
        // 并发闸门：锁 attempts 父行后再做幂等检查（归属/可读性等廉价校验放锁外，缩短持锁时间）。
        // 锁在事务提交前持有——先到者落行提交释放锁后，后到者的幂等闸（RC 快照）直接读到既有
        // MODEL 行按幂等回读返回，模型只调一次；attempts append-only 无删除，锁点稳定。
        attemptDao.lockById(attemptId);
        // 幂等闸：已有 MODEL 复核行直接返回既有结果，不再调模型——省 token 防重复计费，
        // POST 非严格幂等在此为有意取舍（复核一人一次，重复点击只是取回同一份结果）。
        Optional<AttemptGrading> existing = findModelReviewRow(attemptId);
        if (existing.isPresent()) {
            return toReviewView(existing.get(), first.getScore());
        }
        GradingResult localResult = parseFirstResult(first);
        LlmClientRegistry.ChatHandle handle = llmClients.chatHandle().orElse(null);
        if (handle == null) {
            // 无可用客户端（目录空且 legacy 未配置/目录未激活/激活条目凭证不齐）=功能不存在，
            // 与答疑同口径返回 40400。
            throw new BusinessException(ApiCode.NOT_FOUND);
        }
        // 预算门（幂等闸与功能存在性之后）：EXCEEDED 时不调模型不落行，按既有降级语义 50000 返回、
        // 文案点明预算原因——复核是增强链路，原判分不受影响；幂等回读在前，已落复核行的重复
        // 请求只是取回既有结果，不受预算影响（不调模型无新消耗）。
        if (budgetGuard.gate() == BudgetGuardService.Status.EXCEEDED) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, MODEL_REVIEW_BUDGET_MESSAGE);
        }
        // 协议快照在调用前取定：本条用量归属「当次实际使用的协议」（与答疑同口径）。
        String protocol =
                llmClients
                        .effectiveConfig()
                        .map(LlmClientRegistry.EffectiveConfig::protocol)
                        .orElse(null);
        // 系统提示从注册表取 ACTIVE 版本（V13）；缺行回退同文常量，复核链路不因注册表空缺中断。
        PromptRegistry.Entry promptEntry =
                promptRegistry.load(ModelReviewPromptBuilder.PROMPT_NAME);
        String systemPrompt =
                promptEntry instanceof PromptRegistry.Present present
                        ? present.content()
                        : ModelReviewPromptBuilder.systemPrompt();
        Long promptVersionId =
                promptEntry instanceof PromptRegistry.Present present ? present.versionId() : null;
        String userPrompt =
                ModelReviewPromptBuilder.userPrompt(detail, parseAnswer(attempt), localResult);
        long startNanos = System.nanoTime();
        ModelReviewPromptBuilder.ModelReviewOutput output =
                callModelWithRetry(
                        userId,
                        attempt.getSentenceId(),
                        handle,
                        protocol,
                        systemPrompt,
                        userPrompt);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        // 复核行的 result JSON 承载首判批注结构（分数与摘要被复核值替换）：回看解析、申诉批注定位、
        // 答疑批注上下文都按「最新判分行」读取，复核后这些链路必须照常工作。
        GradingResult reviewed =
                new GradingResult(
                        localResult.schemaVersion(),
                        output.score(),
                        localResult.maxScore(),
                        localResult.hitCount(),
                        localResult.totalCount(),
                        localResult.annotations(),
                        output.reason());
        int sequence =
                gradingDao
                        .findLatestByAttemptId(attemptId)
                        .map(g -> g.getSequence() + 1)
                        .orElse(first.getSequence() + 1);
        String modelSnapshot = handle.providerId() + ":" + handle.model();
        try {
            AttemptGrading grading =
                    gradingDao.save(
                            AttemptGrading.createModelReview(
                                    attemptId,
                                    sequence,
                                    output.score(),
                                    objectMapper.writeValueAsString(reviewed),
                                    modelSnapshot,
                                    (int) Math.min(latencyMs, Integer.MAX_VALUE),
                                    first.getNormalizerVersion(),
                                    first.getRubricVersion(),
                                    promptVersionId,
                                    Instant.now()));
            log.info(
                    "model_review userId={} attemptId={} sequence={} score={} model={}"
                            + " latencyMs={}",
                    userId,
                    attemptId,
                    sequence,
                    output.score(),
                    modelSnapshot,
                    grading.getLatencyMs());
            return toReviewView(grading, first.getScore());
        } catch (DataIntegrityViolationException exception) {
            // uk_grading_attempt_sequence 兜底（幂等以数据库唯一约束为准）：029 起 attempt 行锁 +
            // RC 快照已让并发复核在锁上串行化、后到者幂等回读，正常路径撞不到该唯一键；
            // 一旦命中即是数据异常（如 sequence 被外部改写）。撞键后本事务的持久化上下文
            // 不再可信（提交阶段可能重放失败的 INSERT），按既有先例（提交链撞幂等键后不
            // 继续本事务）抛业务异常整体回滚，而不是在脏会话上继续读，也不把堆栈外抛。
            log.warn(
                    "model_review sequence 冲突降级 attemptId={} error={}",
                    attemptId,
                    exception.getClass().getSimpleName());
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "复核记录写入冲突：" + attemptId);
        }
    }

    /**
     * 带重试的模型复核调用：输出解析失败或空回复重试 1 次（共最多 {@link #MODEL_REVIEW_MAX_ATTEMPTS} 次）， 仍败按答疑同款 50000
     * 语义降级——不落复核行、不外抛堆栈与供应商信息，主链路（原判分）不受影响。
     *
     * <p>每次调用的终态都旁路记一条 llm_usage（scene=review）：成功与非空但不可解析的回复 ok=true （token 消耗真实发生），空回复与异常
     * ok=false。
     *
     * @param userId 发起用户 id
     * @param sentenceId 题目 id
     * @param handle 当前可用的客户端句柄
     * @param protocol 当次生效协议（目录未命中时为 null）
     * @param systemPrompt 系统提示
     * @param userPrompt 用户消息
     * @return 解析并钳制后的复核输出
     */
    private ModelReviewPromptBuilder.ModelReviewOutput callModelWithRetry(
            Long userId,
            String sentenceId,
            LlmClientRegistry.ChatHandle handle,
            String protocol,
            String systemPrompt,
            String userPrompt) {
        for (int call = 1; call <= MODEL_REVIEW_MAX_ATTEMPTS; call++) {
            long callStartNanos = System.nanoTime();
            try {
                LlmChatClient.Reply reply = handle.client().ask(systemPrompt, userPrompt);
                long durationMs = (System.nanoTime() - callStartNanos) / 1_000_000;
                if (reply.content() == null || reply.content().isBlank()) {
                    // 空回复是可观测的降级路径：ok=false，与答疑同口径；先重试再降级。
                    recordReviewUsage(
                            userId,
                            sentenceId,
                            protocol,
                            handle.model(),
                            reply.promptTokens(),
                            reply.completionTokens(),
                            durationMs,
                            false);
                    log.warn(
                            "LLM 空回复：scene={}, providerId={}, userId={}, sentenceId={},"
                                    + " call={}/{}, durationMs={}, ok=false",
                            MODEL_REVIEW_SCENE,
                            handle.providerId(),
                            userId,
                            sentenceId,
                            call,
                            MODEL_REVIEW_MAX_ATTEMPTS,
                            durationMs);
                    continue;
                }
                recordReviewUsage(
                        userId,
                        sentenceId,
                        protocol,
                        handle.model(),
                        reply.promptTokens(),
                        reply.completionTokens(),
                        durationMs,
                        true);
                Optional<ModelReviewPromptBuilder.ModelReviewOutput> parsed =
                        ModelReviewPromptBuilder.parse(objectMapper, reply.content());
                if (parsed.isPresent()) {
                    return parsed.get();
                }
                // 非空但不可解析（strict JSON 契约被违反）：token 已消耗记 ok=true，内容层失败重试。
                log.warn(
                        "LLM 输出解析失败：scene={}, providerId={}, userId={}, sentenceId={},"
                                + " call={}/{}",
                        MODEL_REVIEW_SCENE,
                        handle.providerId(),
                        userId,
                        sentenceId,
                        call,
                        MODEL_REVIEW_MAX_ATTEMPTS);
            } catch (RuntimeException exception) {
                // LLM 网络错误/超时/供应商异常：ok=false（token 未取得），重试一次后降级。
                recordReviewUsage(
                        userId,
                        sentenceId,
                        protocol,
                        handle.model(),
                        null,
                        null,
                        (System.nanoTime() - callStartNanos) / 1_000_000,
                        false);
                log.warn(
                        "LLM 调用失败：scene={}, providerId={}, userId={}, sentenceId={},"
                                + " call={}/{}, durationMs={}, error={}",
                        MODEL_REVIEW_SCENE,
                        handle.providerId(),
                        userId,
                        sentenceId,
                        call,
                        MODEL_REVIEW_MAX_ATTEMPTS,
                        (System.nanoTime() - callStartNanos) / 1_000_000,
                        exception.getClass().getSimpleName());
            }
        }
        throw new BusinessException(ApiCode.INTERNAL_ERROR, MODEL_REVIEW_DEGRADE_MESSAGE);
    }

    /**
     * 取某提交的 MODEL 复核行（幂等回读与并发竞态回读共用）。
     *
     * @param attemptId 提交 id
     * @return 最新一条 MODEL 复核行；无则空
     */
    private Optional<AttemptGrading> findModelReviewRow(String attemptId) {
        return gradingDao.findByAttemptId(attemptId).stream()
                .filter(grading -> "MODEL".equals(grading.getGrader()))
                .findFirst();
    }

    /**
     * 解析首判 result JSON；损坏视为数据完整性故障，按复核降级处理（不落行、保留原分）， 不把解析异常当未知 500 暴露堆栈。
     *
     * @param first 首判行
     * @return 首判结果
     */
    private GradingResult parseFirstResult(AttemptGrading first) {
        try {
            return objectMapper.readValue(first.getResult(), GradingResult.class);
        } catch (JacksonException exception) {
            log.warn(
                    "首判 result JSON 损坏，复核降级：attemptId={}, error={}",
                    first.getAttemptId(),
                    exception.getClass().getSimpleName());
            throw new BusinessException(ApiCode.INTERNAL_ERROR, MODEL_REVIEW_DEGRADE_MESSAGE);
        }
    }

    /**
     * 解析提交载荷（提示词「我的提交」上下文）；损坏按「未取得」处理，不阻塞复核。
     *
     * @param attempt 提交
     * @return 提交载荷；解析失败为 null
     */
    private AnswerPayload parseAnswer(Attempt attempt) {
        try {
            return objectMapper.readValue(attempt.getAnswer(), AnswerPayload.class);
        } catch (JacksonException exception) {
            return null;
        }
    }

    /**
     * 落一条复核调用终态用量记录（llm_usage append-only）。旁路纪律与答疑一致：写失败吞掉只记告警—— 账本缺一条可接受，把已经拿到的复核结果变成 5xx 不可接受。
     *
     * @param userId 发起用户 id
     * @param sentenceId 题目 id
     * @param protocol 当次生效协议（目录未命中时为 null）
     * @param model 当次生效模型名快照
     * @param promptTokens 提示词 token（失败未取得时为 null）
     * @param completionTokens 回复 token（失败未取得时为 null）
     * @param durationMs 调用耗时毫秒
     * @param ok 是否拿到非空回复
     */
    private void recordReviewUsage(
            Long userId,
            String sentenceId,
            String protocol,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            long durationMs,
            boolean ok) {
        try {
            usageTxTemplate.executeWithoutResult(
                    status ->
                            llmUsageDao.save(
                                    LlmUsage.of(
                                            userId,
                                            sentenceId,
                                            MODEL_REVIEW_SCENE,
                                            protocol,
                                            model,
                                            promptTokens,
                                            completionTokens,
                                            durationMs,
                                            LlmCostCalculator.estimateMicroCny(
                                                    model,
                                                    promptTokens,
                                                    completionTokens,
                                                    budgetSettings.read().pricing()),
                                            ok,
                                            Instant.now())));
        } catch (RuntimeException exception) {
            log.warn(
                    "LLM 用量落库失败（旁路不阻塞）：scene={}, userId={}, sentenceId={}, error={}",
                    MODEL_REVIEW_SCENE,
                    userId,
                    sentenceId,
                    exception.getClass().getSimpleName());
        }
    }

    /**
     * 复核行 → 响应视图。理由取复核行 result JSON 的 summary（落库时已替换为模型理由）， 保证幂等回读与并发竞态回读都返回与首次调用一致的完整结果。
     *
     * @param review MODEL 复核行
     * @param originalScore 首判分
     * @return 复核视图
     */
    private ModelReviewView toReviewView(AttemptGrading review, Integer originalScore) {
        String reason;
        try {
            GradingResult result = objectMapper.readValue(review.getResult(), GradingResult.class);
            reason = result.summary();
        } catch (JacksonException exception) {
            // 复核行 JSON 损坏只在并发/运维事故下出现；理由退化为固定文案，分数仍如实返回。
            reason = "复核结果已记录";
        }
        return new ModelReviewView(
                review.getScore() == null ? 0 : review.getScore(),
                reason,
                review.getModelSnapshot(),
                review.getLatencyMs() == null ? null : review.getLatencyMs().longValue(),
                review.getCreatedAt(),
                review.getSequence(),
                originalScore == null ? 0 : originalScore);
    }

    /**
     * 复习卡实体 → 列表投影。
     *
     * @param card 复习卡
     * @return 视图
     */
    private ReviewCardView toCardView(Review card) {
        return new ReviewCardView(
                card.getId(),
                card.getSentenceId(),
                card.getMode().name(),
                card.getState().name(),
                card.getFsrsState().name(),
                card.getDueAt(),
                card.getStability(),
                card.getDifficulty(),
                card.getReps(),
                card.getLapses(),
                card.getScheduledDays(),
                card.getLastRating(),
                card.getLastReviewAt());
    }

    /**
     * 区间过滤（含题库/模式）：from 当地零点起、to 次日零点止。
     *
     * <p>提交列表只取一次；题库过滤需要题目归属时才补一次批量详情查询。
     *
     * @param userId 用户 id
     * @param start 起始本地日期
     * @param end 截止本地日期
     * @param zone 用户时区
     * @param bankId 题库过滤，可空
     * @param mode 模式过滤，可空
     * @return 过滤后的提交列表
     */
    private List<Attempt> filterAttempts(
            Long userId, LocalDate start, LocalDate end, ZoneId zone, Long bankId, String mode) {
        List<Attempt> attempts =
                attemptDao.findByUserBetween(
                        userId,
                        start.atStartOfDay(zone).toInstant(),
                        end.plusDays(1).atStartOfDay(zone).toInstant());
        if (bankId == null) {
            return attempts.stream()
                    .filter(attempt -> mode == null || attempt.getMode().name().equals(mode))
                    .toList();
        }
        Map<String, SentenceDetail> details =
                contentQueryService
                        .findSentences(
                                attempts.stream()
                                        .map(Attempt::getSentenceId)
                                        .collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(SentenceDetail::id, detail -> detail));
        return attempts.stream()
                .filter(attempt -> mode == null || attempt.getMode().name().equals(mode))
                .filter(
                        attempt -> {
                            SentenceDetail detail = details.get(attempt.getSentenceId());
                            return detail != null && String.valueOf(bankId).equals(detail.bankId());
                        })
                .toList();
    }

    /**
     * 批量取各提交的最新判分（一条 IN 查询，替代逐 attempt 的 N+1）。
     *
     * @param attempts 提交列表
     * @return attemptId → 最新判分；无判分的提交不在映射内
     */
    private Map<String, AttemptGrading> latestGradingsByAttempt(List<Attempt> attempts) {
        if (attempts.isEmpty()) {
            return Map.of();
        }
        return gradingDao
                .findLatestByAttemptIds(attempts.stream().map(Attempt::getId).toList())
                .stream()
                .collect(Collectors.toMap(AttemptGrading::getAttemptId, grading -> grading));
    }

    /**
     * 从批量判分映射里取有分数的判分值（保持 attempts 的重复语义：一条一次）。
     *
     * @param attempts 提交列表
     * @param latestGradings 批量判分映射
     * @return 分数列表（剔除 REVEAL 的 null 分）
     */
    private List<Integer> scoresOf(
            List<Attempt> attempts, Map<String, AttemptGrading> latestGradings) {
        return attempts.stream()
                .map(attempt -> latestGradings.get(attempt.getId()))
                .filter(Objects::nonNull)
                .map(AttemptGrading::getScore)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 单条最新判分分数（recent 视图用，可空）。
     *
     * @param attemptId 提交 id
     * @param latestGradings 批量判分映射
     * @return 最新分数；无判分或 REVEAL 无分为 null
     */
    private Integer latestScoreOf(String attemptId, Map<String, AttemptGrading> latestGradings) {
        AttemptGrading grading = latestGradings.get(attemptId);
        return grading == null ? null : grading.getScore();
    }

    /**
     * from/to 闭区间上限：共 between+1 个本地日，超过 92 天按 40000 拒绝。
     *
     * <p>选显式拒绝而非静默截断：截断会悄悄改变统计口径，客户端无从得知； 区间上限保护的是全量内存聚合（V1 口径）的量级。
     *
     * @param start 起始本地日期
     * @param end 截止本地日期
     */
    private static void requireRangeWithinLimit(LocalDate start, LocalDate end) {
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw new BusinessException(
                    ApiCode.VALIDATION_FAILED, "from 与 to 区间最长 " + MAX_RANGE_DAYS + " 天");
        }
    }

    /**
     * 全对计数。
     *
     * @param scores 分数列表
     * @return score=100 的条数
     */
    private long correctCount(List<Integer> scores) {
        return scores.stream().filter(score -> score == 100).count();
    }

    /**
     * 保留一位小数。
     *
     * @param value 原值
     * @return 舍入结果
     */
    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * 三档状态参数解析；非法值按 40000 拒绝。
     *
     * @param state 状态字面量，可空
     * @return 状态，可空
     */
    private Review.ReviewState parseState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return Review.ReviewState.valueOf(state);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "state 取值不合法");
        }
    }

    /**
     * 题库过滤匹配。
     *
     * @param detail 题目详情
     * @param bankId 题库 id，可空
     * @return 是否匹配
     */
    private boolean matchBank(SentenceDetail detail, Long bankId) {
        return bankId == null || String.valueOf(bankId).equals(detail.bankId());
    }

    /**
     * 考点过滤匹配。
     *
     * @param detail 题目详情
     * @param tag 考点标签，可空
     * @return 是否匹配
     */
    private boolean matchTag(SentenceDetail detail, String tag) {
        return tag == null || detail.tags() != null && detail.tags().contains(tag);
    }

    /**
     * 内存分页。
     *
     * @param items 全量列表
     * @param page 页码，从 1 起
     * @param size 每页大小
     * @param <T> 元素类型
     * @return 分页响应
     */
    private <T> PageResponse<T> pageOf(List<T> items, int page, int size) {
        int fromIndex = Math.max(0, (page - 1) * size);
        int toIndex = Math.min(items.size(), fromIndex + size);
        List<T> slice = fromIndex >= items.size() ? List.of() : items.subList(fromIndex, toIndex);
        return PageResponse.of(new ArrayList<>(slice), page, size, items.size());
    }
}
