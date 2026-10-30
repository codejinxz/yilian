package io.github.codejinxz.yilian.practice.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.content.service.ContentQueryService;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;
import io.github.codejinxz.yilian.practice.dao.AppealDao;
import io.github.codejinxz.yilian.practice.dao.AttemptDao;
import io.github.codejinxz.yilian.practice.dao.AttemptGradingDao;
import io.github.codejinxz.yilian.practice.dao.FsrsParameterVersionDao;
import io.github.codejinxz.yilian.practice.dao.ReviewDao;
import io.github.codejinxz.yilian.practice.dao.ReviewLogDao;
import io.github.codejinxz.yilian.practice.entity.Appeal;
import io.github.codejinxz.yilian.practice.entity.Attempt;
import io.github.codejinxz.yilian.practice.entity.AttemptGrading;
import io.github.codejinxz.yilian.practice.entity.Review;
import io.github.codejinxz.yilian.practice.entity.ReviewLog;
import io.github.codejinxz.yilian.practice.grading.Grader;
import io.github.codejinxz.yilian.practice.grading.GradingAnnotation;
import io.github.codejinxz.yilian.practice.grading.GradingResult;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.dto.GuestUpgradeCommand;
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
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.dto.UserSettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** 降载阈值：到期数超过该值进入降载（GAP P0「积压熔断」，本期常量）。 */
    private static final int OVERLOAD_THRESHOLD = 20;

    /** 降载推送上限：降载时一次只推最早到期的 5 道。 */
    private static final int PUSH_LIMIT = 5;

    private final AttemptDao attemptDao;

    private final AttemptGradingDao gradingDao;

    private final ReviewDao reviewDao;

    private final ReviewLogDao reviewLogDao;

    private final AppealDao appealDao;

    private final FsrsParameterVersionDao fsrsParameterVersionDao;

    private final ContentQueryService contentQueryService;

    private final UserAccountService userAccountService;

    private final ObjectMapper objectMapper;

    /**
     * 只依赖 Dao 接口与跨模块 service 接口，不触碰实体映射之外的具体实现。
     *
     * @param attemptDao 提交 Dao
     * @param gradingDao 判分 Dao
     * @param reviewDao 复习卡 Dao
     * @param reviewLogDao 排程日志 Dao
     * @param appealDao 申诉/不认同 Dao（disputed overlay）
     * @param fsrsParameterVersionDao 权重版本 Dao
     * @param contentQueryService 题目内容只读查询（content 模块）
     * @param userAccountService 用户设置查询（system 模块）
     * @param objectMapper JSON 序列化（答案载荷与判分结果落库）
     */
    public PracticeServiceImpl(
            AttemptDao attemptDao,
            AttemptGradingDao gradingDao,
            ReviewDao reviewDao,
            ReviewLogDao reviewLogDao,
            AppealDao appealDao,
            FsrsParameterVersionDao fsrsParameterVersionDao,
            ContentQueryService contentQueryService,
            UserAccountService userAccountService,
            ObjectMapper objectMapper) {
        this.attemptDao = attemptDao;
        this.gradingDao = gradingDao;
        this.reviewDao = reviewDao;
        this.reviewLogDao = reviewLogDao;
        this.appealDao = appealDao;
        this.fsrsParameterVersionDao = fsrsParameterVersionDao;
        this.contentQueryService = contentQueryService;
        this.userAccountService = userAccountService;
        this.objectMapper = objectMapper;
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

        // 校验链：题目存在且可练 → 版本一致 → RETRY 链合法。
        SentenceDetail detail = requirePracticeableSentence(command);
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
            attemptDao.save(attempt);
        } catch (DataIntegrityViolationException exception) {
            // 并发同 id：PK 兜底（幂等以数据库唯一约束为准）。重读比对，读不到按复用拒绝。
            Attempt winner = attemptDao.findById(command.attemptId()).orElse(null);
            if (winner != null
                    && winner.getUserId().equals(userId)
                    && winner.getAnswerHash().equals(answerHash)) {
                return buildReplayView(winner);
            }
            throw new BusinessException(ApiCode.ATTEMPT_ID_REUSED);
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

    @Override
    @Transactional(readOnly = true)
    public AttemptView getAttempt(Long userId, String attemptId) {
        Attempt attempt =
                attemptDao
                        .findById(attemptId)
                        .filter(a -> a.getUserId().equals(userId))
                        .orElseThrow(() -> new BusinessException(ApiCode.ATTEMPT_NOT_FOUND));
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
    @Transactional
    public AppealView submitAppeal(
            Long userId,
            String attemptId,
            String annotationKey,
            String proposedAnswer,
            String reason) {
        return saveAppeal(
                userId, attemptId, annotationKey, Appeal.AppealKind.APPEAL, proposedAnswer, reason);
    }

    @Override
    @Transactional
    public AppealView submitDispute(
            Long userId, String attemptId, String annotationKey, String reason) {
        return saveAppeal(
                userId, attemptId, annotationKey, Appeal.AppealKind.DISPUTE, null, reason);
    }

    /**
     * 申诉/不认同共用落库链路：归属校验 → 批注定位 → uk 预检 → 写 appeals。
     *
     * <p>uk_appeal_annotation 为 (attempt_id, annotation_key, kind)：APPEAL 与 DISPUTE 各自 可存一条；先查后插给友好
     * 40900，并发兜底靠数据库唯一约束。
     *
     * @param userId 当前用户 id
     * @param attemptId 提交 id
     * @param annotationKey 批注键（slot:N / free:0）
     * @param kind 记录类型
     * @param proposedAnswer 建议答案，DISPUTE 恒为 null
     * @param reason 理由，可空
     * @return 回执视图
     */
    private AppealView saveAppeal(
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
                "appeal_submitted userId={} attemptId={} key={} kind={} status={}",
                userId,
                attemptId,
                annotationKey,
                kind.name(),
                kind == Appeal.AppealKind.DISPUTE ? "ACCEPTED" : "PENDING");
        return new AppealView(
                appeal.getId(),
                kind == Appeal.AppealKind.DISPUTE ? "ACCEPTED" : "PENDING",
                kind.name(),
                annotationKey);
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
     * 题目校验：存在、可练（已发布未删）、版本一致，否则 40402。
     *
     * @param command 提交命令
     * @return 题目详情
     */
    private SentenceDetail requirePracticeableSentence(AttemptCommand command) {
        SentenceDetail detail =
                contentQueryService
                        .findSentence(command.sentenceId())
                        .filter(SentenceDetail::practiceable)
                        .orElseThrow(() -> new BusinessException(ApiCode.SENTENCE_NOT_FOUND));
        if (!command.sentenceVersionId().equals(detail.sentenceVersionId())) {
            throw new BusinessException(ApiCode.SENTENCE_NOT_FOUND, "题目版本与当前版本不一致");
        }
        return detail;
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
        return Grader.gradeCloze(detail.chunks(), blank, answers);
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
        Long weightsVersionId =
                fsrsParameterVersionDao
                        .findActiveGlobalId()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ApiCode.INTERNAL_ERROR,
                                                "缺少 GLOBAL ACTIVE 的 FSRS 权重版本"));

        if (failed) {
            if (card == null) {
                Review created =
                        Review.createFailed(
                                attempt.getUserId(),
                                attempt.getSentenceId(),
                                attempt.getMode(),
                                attempt.getId(),
                                rating,
                                now);
                reviewDao.save(created);
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
            Review.Snapshot snapshot = Review.Snapshot.of(card, now);
            card.applyFailed(attempt.getId(), rating, now);
            card.touchMode(attempt.getMode());
            reviewDao.save(card);
            reviewLogDao.save(
                    logForExisting(snapshot, card, attempt, rating, weightsVersionId, now));
            return card;
        }

        // 全对：新题不入队；学习中推进间隔；已归档只刷新痕迹；重置后的 NEW 卡不动。
        if (card == null || card.getState() == Review.ReviewState.NEW) {
            return null;
        }
        Review.Snapshot snapshot = Review.Snapshot.of(card, now);
        if (card.getState() == Review.ReviewState.LEARNING) {
            card.applyLearningGraduated(attempt.getId(), rating, now);
            card.touchMode(attempt.getMode());
        } else {
            card.applyMasteredReview(attempt.getId(), rating, now);
        }
        reviewDao.save(card);
        reviewLogDao.save(logForExisting(snapshot, card, attempt, rating, weightsVersionId, now));
        return card;
    }

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
    public NextPracticeView next(Long userId, Long bankId, String excludeSentenceId) {
        // 到期复习优先：due_at 升序取第一张可练卡的题目。
        for (Review card : reviewDao.findDue(userId, Instant.now())) {
            Optional<SentenceDetail> detail =
                    contentQueryService.findSentence(card.getSentenceId());
            if (detail.isPresent() && detail.get().practiceable()) {
                return new NextPracticeView(detail.get(), toCardView(card), "DUE");
            }
        }
        // 新题：跳过已入队（非 NEW）的句子，与本地引擎 selectNew 同语义。
        for (SentenceDetail candidate :
                contentQueryService.findPracticeCandidates(bankId, excludeSentenceId, 20)) {
            Optional<Review> card = reviewDao.findByUserIdAndSentenceId(userId, candidate.id());
            if (card.isPresent() && card.get().getState() != Review.ReviewState.NEW) {
                continue;
            }
            return new NextPracticeView(candidate, card.map(this::toCardView).orElse(null), "NEW");
        }
        return null;
    }

    @Override
    public PageResponse<WrongQuestionView> wrongQuestions(
            Long userId, int page, int size, boolean includeDisputed, Long bankId, String tag) {
        List<Review> learning = reviewDao.findLearning(userId);
        List<String> sentenceIds = learning.stream().map(Review::getSentenceId).toList();
        Map<String, List<Appeal>> disputesBySentence =
                sentenceIds.isEmpty()
                        ? Map.of()
                        : appealDao.findDisputed(userId, sentenceIds).stream()
                                .collect(Collectors.groupingBy(Appeal::getSentenceId));
        List<WrongQuestionView> items = new ArrayList<>();
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
            Optional<AttemptGrading> latestGrading =
                    latestAttempt.flatMap(
                            attempt -> gradingDao.findLatestByAttemptId(attempt.getId()));
            if (latestAttempt.isEmpty() || latestGrading.isEmpty()) {
                continue;
            }
            Integer lastScore = latestGrading.get().getScore();
            // 错题口径：最近判分未全对（REVEAL 的 null 分也算未全对）。
            if (lastScore != null && lastScore >= 100) {
                continue;
            }
            Set<String> disputedKeys =
                    disputesBySentence.getOrDefault(card.getSentenceId(), List.of()).stream()
                            .map(Appeal::getAnnotationKey)
                            .collect(Collectors.toSet());
            List<GradingAnnotation> wrongAnnotations =
                    latestGrading.get().getResult() == null
                            ? List.of()
                            : objectMapper
                                    .readValue(latestGrading.get().getResult(), GradingResult.class)
                                    .annotations()
                                    .stream()
                                    .filter(annotation -> !annotation.correct())
                                    .toList();
            List<WrongQuestionView.WrongAnnotationView> annotations =
                    wrongAnnotations.stream()
                            .map(
                                    annotation ->
                                            new WrongQuestionView.WrongAnnotationView(
                                                    annotation.key(),
                                                    annotation.tag(),
                                                    annotation.message(),
                                                    disputedKeys.contains(annotation.key())))
                            .filter(annotation -> includeDisputed || !annotation.disputed())
                            .toList();
            // 批注全部被排除且无其他错误的题不出现在错题列表（API §6.6）。
            if (annotations.isEmpty()) {
                continue;
            }
            items.add(
                    new WrongQuestionView(
                            card.getSentenceId(),
                            card.getMode().name(),
                            card.getState().name(),
                            card.getDueAt(),
                            lastScore,
                            latestAttempt.get().getId(),
                            gradingDao.countWrongByUserAndSentence(userId, card.getSentenceId()),
                            detail.tags() == null ? List.of() : detail.tags(),
                            detail.zh(),
                            annotations));
        }
        return pageOf(items, page, size);
    }

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
        // 推送上限内的最早卡保留今日，其余整体顺延一天（GAP P0 降载口径）。
        for (int index = PUSH_LIMIT; index < due.size(); index++) {
            Review card = due.get(index);
            card.postponeOneDay();
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
        List<Attempt> attempts = filterAttempts(userId, start, end, zone, bankId, mode);
        List<Integer> scores = latestScores(attempts);
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
            List<Integer> modeScores = latestScores(modeAttempts);
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
            List<Integer> dayScores = latestScores(dayAttempts);
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
                                                latestScores(List.of(attempt)).stream()
                                                        .findFirst()
                                                        .orElse(null),
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
        List<Attempt> attempts = filterAttempts(userId, start, end, zone, null, null);
        Set<String> disputedKeys =
                appealDao
                        .findDisputed(
                                userId,
                                attempts.stream().map(Attempt::getSentenceId).distinct().toList())
                        .stream()
                        .map(appeal -> appeal.getSentenceId() + "|" + appeal.getAnnotationKey())
                        .collect(Collectors.toSet());
        Map<String, Long> counts = new LinkedHashMap<>();
        long[] total = {0};
        for (Attempt attempt : attempts) {
            Optional<AttemptGrading> grading = gradingDao.findLatestByAttemptId(attempt.getId());
            if (grading.isEmpty()) {
                continue;
            }
            GradingResult result =
                    objectMapper.readValue(grading.get().getResult(), GradingResult.class);
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
            SentenceDetail detail = requirePracticeableSentence(item);
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
                attemptDao.save(attempt);
            } catch (DataIntegrityViolationException exception) {
                Attempt winner = attemptDao.findById(item.attemptId()).orElse(null);
                if (winner != null
                        && winner.getUserId().equals(userId)
                        && winner.getAnswerHash().equals(payloadHash(item))) {
                    skipped++;
                    continue;
                }
                throw new BusinessException(ApiCode.ATTEMPT_ID_REUSED);
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
        Map<String, SentenceDetail> details =
                contentQueryService
                        .findSentences(
                                attemptDao
                                        .findByUserBetween(
                                                userId,
                                                start.atStartOfDay(zone).toInstant(),
                                                end.plusDays(1).atStartOfDay(zone).toInstant())
                                        .stream()
                                        .map(Attempt::getSentenceId)
                                        .collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(SentenceDetail::id, detail -> detail));
        return attemptDao
                .findByUserBetween(
                        userId,
                        start.atStartOfDay(zone).toInstant(),
                        end.plusDays(1).atStartOfDay(zone).toInstant())
                .stream()
                .filter(attempt -> mode == null || attempt.getMode().name().equals(mode))
                .filter(
                        attempt -> {
                            if (bankId == null) {
                                return true;
                            }
                            SentenceDetail detail = details.get(attempt.getSentenceId());
                            return detail != null && String.valueOf(bankId).equals(detail.bankId());
                        })
                .toList();
    }

    /**
     * 逐条取最新判分分数（只保留有分数的）。
     *
     * @param attempts 提交列表
     * @return 分数列表
     */
    private List<Integer> latestScores(List<Attempt> attempts) {
        return attempts.stream()
                .map(attempt -> gradingDao.findLatestByAttemptId(attempt.getId()))
                .flatMap(Optional::stream)
                .map(AttemptGrading::getScore)
                .filter(Objects::nonNull)
                .toList();
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
