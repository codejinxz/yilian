package io.github.codejinxz.yilian.practice.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;

import io.github.codejinxz.yilian.common.api.BusinessException;

import io.github.codejinxz.yilian.content.service.ContentQueryService;

import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;

import io.github.codejinxz.yilian.practice.dao.AttemptDao;

import io.github.codejinxz.yilian.practice.dao.AttemptGradingDao;

import io.github.codejinxz.yilian.practice.dao.FsrsParameterVersionDao;

import io.github.codejinxz.yilian.practice.dao.ReviewDao;

import io.github.codejinxz.yilian.practice.dao.ReviewLogDao;

import io.github.codejinxz.yilian.practice.entity.Attempt;

import io.github.codejinxz.yilian.practice.entity.AttemptGrading;

import io.github.codejinxz.yilian.practice.entity.Review;

import io.github.codejinxz.yilian.practice.entity.ReviewLog;

import io.github.codejinxz.yilian.practice.grading.Grader;

import io.github.codejinxz.yilian.practice.grading.GradingResult;

import io.github.codejinxz.yilian.practice.service.PracticeService;

import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload;

import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;

import io.github.codejinxz.yilian.practice.service.vo.AttemptView;

import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;

import java.security.NoSuchAlgorithmException;

import java.time.Instant;

import java.util.ArrayList;

import java.util.HexFormat;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.Optional;

/**
 * 练习用例实现：判分/幂等/SRS 排程的唯一写入口。
 *
 * <p>SRS 四分支与本地引擎（web/src/lib/local-engine/engine.ts 的 scheduleReview）逐值对齐：
 * RETRY 不排程；失败（REVEAL 或 score&lt;100）回炉 LEARNING 明天到期；学习中全对按
 * interval=min(7, scheduled+2) 推进、≥5 归档；归档全对只刷新复习痕迹；新题全对不入队。
 * 每次排程写一行 review_logs（before/after），uk_review_log_attempt 保证一次排程。
 */
@Service
public class PracticeServiceImpl implements PracticeService {

    /** deriveRating：全对且未用提示、耗时 ≤60s 记 EASY 的阈值（毫秒）。 */
    private static final long EASY_DURATION_MS = 60_000L;

    private final AttemptDao attemptDao;

    private final AttemptGradingDao gradingDao;

    private final ReviewDao reviewDao;

    private final ReviewLogDao reviewLogDao;

    private final FsrsParameterVersionDao fsrsParameterVersionDao;

    private final ContentQueryService contentQueryService;

    private final ObjectMapper objectMapper;

    /**
     * 只依赖 Dao 接口与跨模块 service 接口，不触碰实体映射之外的具体实现。
     *
     * @param attemptDao 提交 Dao
     * @param gradingDao 判分 Dao
     * @param reviewDao 复习卡 Dao
     * @param reviewLogDao 排程日志 Dao
     * @param fsrsParameterVersionDao 权重版本 Dao
     * @param contentQueryService 题目内容只读查询（content 模块）
     * @param objectMapper JSON 序列化（答案载荷与判分结果落库）
     */
    public PracticeServiceImpl(
            AttemptDao attemptDao,
            AttemptGradingDao gradingDao,
            ReviewDao reviewDao,
            ReviewLogDao reviewLogDao,
            FsrsParameterVersionDao fsrsParameterVersionDao,
            ContentQueryService contentQueryService,
            ObjectMapper objectMapper) {
        this.attemptDao = attemptDao;
        this.gradingDao = gradingDao;
        this.reviewDao = reviewDao;
        this.reviewLogDao = reviewLogDao;
        this.fsrsParameterVersionDao = fsrsParameterVersionDao;
        this.contentQueryService = contentQueryService;
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
            if (!stored.getUserId().equals(userId) || !stored.getAnswerHash().equals(payloadHash(command))) {
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
            if (winner != null && winner.getUserId().equals(userId)
                    && winner.getAnswerHash().equals(answerHash)) {
                return buildReplayView(winner);
            }
            throw new BusinessException(ApiCode.ATTEMPT_ID_REUSED);
        }

        GradingResult result = gradeForCommand(detail, command);
        int sequence =
                gradingDao.findLatestByAttemptId(command.attemptId()).map(g -> g.getSequence() + 1).orElse(1);
        AttemptGrading grading =
                gradingDao.save(
                        AttemptGrading.createLocal(
                                command.attemptId(),
                                sequence,
                                result.score(),
                                objectMapper.writeValueAsString(result),
                                now));

        int rating = deriveRating(result.score(), command.effectiveHintsUsed(), command.durationMs(), command.kind());
        Review review =
                scheduleReview(attempt, result.score(), rating, command, now);

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
                                                ApiCode.INTERNAL_ERROR, "判分记录缺失：" + attempt.getId()));
        GradingResult result = objectMapper.readValue(grading.getResult(), GradingResult.class);
        SentenceDetail detail =
                contentQueryService.findSentence(attempt.getSentenceId()).orElse(null);
        Review review = reviewDao.findByUserIdAndSentenceId(attempt.getUserId(), attempt.getSentenceId()).orElse(null);
        Integer rating =
                reviewLogDao
                        .findByAttemptId(attempt.getId())
                        .map(log -> (int) log.getRating())
                        .or(() -> Optional.ofNullable(review == null ? null : review.getLastRating()))
                        .orElse(null);
        int displayRating = rating == null ? 0 : rating;
        return buildView(attempt, detail, grading, result, review, displayRating, true);
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
        boolean retryRequired = score != null && score < 100 && attempt.getKind() == Attempt.AttemptKind.FIRST;
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
                detail == null
                        ? null
                        : new AttemptView.ReferenceView(detail.ref(), detail.note()),
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
                        .orElseThrow(() -> new BusinessException(ApiCode.ATTEMPT_NOT_FOUND, "重打来源提交不存在"));
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
                                                ApiCode.INTERNAL_ERROR, "缺少 GLOBAL ACTIVE 的 FSRS 权重版本"));

        if (failed) {
            if (card == null) {
                Review created =
                        Review.createFailed(
                                attempt.getUserId(), attempt.getSentenceId(), attempt.getMode(),
                                attempt.getId(), rating, now);
                reviewDao.save(created);
                reviewLogDao.save(
                        ReviewLog.forNewCard(
                                created, attempt.getId(), rating, attempt.getDurationMs(),
                                weightsVersionId, now));
                return created;
            }
            Review.Snapshot snapshot = Review.Snapshot.of(card, now);
            card.applyFailed(attempt.getId(), rating, now);
            card.touchMode(attempt.getMode());
            reviewDao.save(card);
            reviewLogDao.save(logForExisting(snapshot, card, attempt, rating, weightsVersionId, now));
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
     * 四档评分派生（DATA-MODEL §8.2 / engine.ts deriveRating）：
     * REVEAL、无分、&lt;60 → 1；&lt;100 → 2；全对有提示 → 3；全对无提示 ≤60s → 4，否则 3。
     *
     * @param score 判分；REVEAL 为 null
     * @param hintsUsed 提示次数
     * @param durationMs 耗时，可空
     * @param kind 提交类型
     * @return 1–4
     */
    private int deriveRating(Integer score, int hintsUsed, Integer durationMs, AttemptCommand.Kind kind) {
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
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
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
}
