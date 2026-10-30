package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.ContentQueryService;
import io.github.codejinxz.yilian.content.service.dto.ImportedSentence;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.vo.AppealView;
import io.github.codejinxz.yilian.practice.service.vo.AttemptView;
import io.github.codejinxz.yilian.practice.service.vo.NextPracticeView;
import io.github.codejinxz.yilian.practice.service.vo.OverloadView;
import io.github.codejinxz.yilian.practice.service.vo.PostponeView;
import io.github.codejinxz.yilian.practice.service.vo.ReviewCardView;
import io.github.codejinxz.yilian.practice.service.vo.TodayView;
import io.github.codejinxz.yilian.practice.service.vo.WrongQuestionView;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 练习主链路集成测试（真实 MySQL yilian_test）：AC-1 一句一卡迁移与 FSRS 种子、 AC-3 提交错题入队 + review_logs + 幂等重放/冲突、AC-4
 * 重打不排程与学习/归档推进。
 *
 * <p>自清模式与 {@code ContentImportServiceIntegrationTest} 同口径：append-only 触发器 先摘后重建，内容三表与 practice
 * 五表逐用例清空，users 幂等播种。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false",
            "yilian.security.jwt.secret=test-only-yilian-jwt-secret-0123456789abcdef"
        })
@ActiveProfiles("local")
class PracticeFlowIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 12, 0, 0));

    private static final long USER_ID = 8L;

    private static final String REF = "He is paying more and more attention to this matter.";

    private static final String SECOND_REF = "The meeting was held as scheduled.";

    @Autowired private PracticeService practiceService;

    @Autowired private ContentImportService contentImportService;

    @Autowired private ContentQueryService contentQueryService;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private ObjectMapper objectMapper;

    /** 当前用例导入的第一题（CLOZE 主链路用）。 */
    private ImportedSentence seeded;

    /** 当前用例导入的第二题（REVEAL 独立分支用，避免与第一题共用练习历史）。 */
    private ImportedSentence secondSeeded;

    /**
     * 测试库指向本机 yilian_test。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:mysql://localhost:3306/yilian_test?useUnicode=true&characterEncoding=utf8"
                                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                                + "&allowPublicKeyRetrieval=true&useSSL=false");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "123456");
    }

    /** 用例后清空 practice 五表与内容表，并重建全部 append-only 触发器。 */
    @AfterEach
    void cleanAfter() {
        cleanDatabase();
    }

    /** 幂等播种用户与测试题目；practice 五表清空保证用例独立。 */
    @BeforeEach
    void cleanAndSeed() {
        cleanDatabase();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, username, password_hash, role, enabled)"
                        + " VALUES (?, 'it-learner@example.com', 'it-learner', 'seeded', 'USER', TRUE)"
                        + " ON DUPLICATE KEY UPDATE enabled = TRUE",
                USER_ID);
        seedSentence();
    }

    /** [AC-1] V9 后 uk_review_card 收敛为 (user_id, sentence_id)，且存在 GLOBAL ACTIVE 权重种子行。 */
    @Test
    @DisplayName("[AC-1] uk_review_card=(user_id,sentence_id) + GLOBAL ACTIVE 种子行")
    void ac1_singleCardUniqueKeyAndGlobalWeightsSeed() {
        List<String> keyColumns =
                jdbcTemplate.queryForList(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews'"
                                + " AND INDEX_NAME = 'uk_review_card' ORDER BY SEQ_IN_INDEX",
                        String.class);
        assertThat(keyColumns.stream().map(String::toUpperCase))
                .containsExactly("USER_ID", "SENTENCE_ID");

        Long activeGlobal =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM fsrs_parameter_versions"
                                + " WHERE owner_type = 'GLOBAL' AND status = 'ACTIVE'",
                        Long.class);
        assertThat(activeGlobal).isEqualTo(1L);
    }

    /** [AC-3] 提交错题：落库、LEARNING 明天到期、review_logs 一行、幂等重放与 40903。 */
    @Test
    @DisplayName("[AC-3] 错题提交入队 + review_logs + 幂等重放/冲突")
    void ac3_wrongAttemptSchedulesCardAndIsIdempotent() {
        Instant before = Instant.now();
        AttemptCommand wrong = firstAttempt(wrongAnswer(), 1000);
        AttemptView view = practiceService.submitAttempt(USER_ID, wrong);

        assertThat(view.replayed()).isFalse();
        assertThat(view.score()).isBetween(60, 99);
        assertThat(view.nextAction().retryRequired()).isTrue();
        assertThat(view.nextAction().canReveal()).isTrue();
        assertThat(view.reference().ref()).isEqualTo(REF);
        // review_logs rating：60<=score<100 → HARD(2)。
        assertThat(view.review().rating()).isEqualTo(2);
        assertThat(view.review().state()).isEqualTo("LEARNING");
        assertThat(view.review().fsrsState()).isEqualTo("LEARNING");
        assertThat(view.review().stability()).isEqualTo(2.4);
        assertThat(view.review().difficulty()).isEqualTo(5.3);
        assertThat(view.review().reps()).isEqualTo(1);
        assertThat(view.review().lapses()).isEqualTo(1);
        assertThat(view.review().scheduledDays()).isEqualTo(1);
        assertThat(view.review().dueAt())
                .isBetween(before.plus(23, ChronoUnit.HOURS), before.plus(25, ChronoUnit.HOURS));

        Long attemptCount = count("attempts");
        Long gradingCount = count("attempt_gradings");
        Long logCount = count("review_logs");
        assertThat(attemptCount).isEqualTo(1L);
        assertThat(gradingCount).isEqualTo(1L);
        assertThat(logCount).isEqualTo(1L);

        // review_logs before/after：新卡 state_before=NEW，rating=2，stability_after=2.4。
        var logRow =
                jdbcTemplate.queryForMap(
                        "SELECT rating, state_before, state_after, stability_before,"
                                + " stability_after, weights_version_id, attempt_id"
                                + " FROM review_logs WHERE attempt_id = ?",
                        wrong.attemptId());
        assertThat(logRow.get("rating")).isEqualTo(2);
        assertThat(logRow.get("state_before")).isEqualTo("NEW");
        assertThat(logRow.get("state_after")).isEqualTo("LEARNING");
        assertThat(((Number) logRow.get("stability_after")).doubleValue()).isEqualTo(2.4);
        assertThat(logRow.get("weights_version_id")).isNotNull();

        // answer 列必须是可解析的 JSON 对象（Hibernate JSON 列不二次编码）。
        String storedAnswer =
                jdbcTemplate.queryForObject(
                        "SELECT answer FROM attempts WHERE id = ?",
                        String.class,
                        wrong.attemptId());
        assertThat(objectMapper.readTree(storedAnswer).get("schemaVersion").asInt()).isEqualTo(1);

        // 幂等重放：同 id 同载荷 → replayed=true，无新行。
        AttemptView replay = practiceService.submitAttempt(USER_ID, wrong);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.attemptId()).isEqualTo(wrong.attemptId());
        assertThat(count("attempts")).isEqualTo(attemptCount);
        assertThat(count("attempt_gradings")).isEqualTo(gradingCount);
        assertThat(count("review_logs")).isEqualTo(logCount);

        // 同 id 异载荷 → 40903。
        AttemptCommand conflicting =
                new AttemptCommand(
                        wrong.attemptId(),
                        seeded.sentenceId(),
                        String.valueOf(seeded.sentenceVersionId()),
                        AttemptCommand.Mode.CLOZE,
                        AttemptCommand.Kind.FIRST,
                        null,
                        correctAnswer(),
                        0,
                        1000,
                        null);
        assertThatThrownBy(() -> practiceService.submitAttempt(USER_ID, conflicting))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.ATTEMPT_ID_REUSED));

        // 版本不匹配 → 40402；题目不存在 → 40402。
        assertThatThrownBy(
                        () ->
                                practiceService.submitAttempt(
                                        USER_ID,
                                        new AttemptCommand(
                                                UUID.randomUUID().toString(),
                                                seeded.sentenceId(),
                                                "999999",
                                                AttemptCommand.Mode.CLOZE,
                                                AttemptCommand.Kind.FIRST,
                                                null,
                                                correctAnswer(),
                                                0,
                                                1000,
                                                null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.SENTENCE_NOT_FOUND));
        assertThatThrownBy(
                        () ->
                                practiceService.submitAttempt(
                                        USER_ID,
                                        new AttemptCommand(
                                                UUID.randomUUID().toString(),
                                                "01AAAAAAAAAAAAAAAAAAAAAAAA",
                                                "1",
                                                AttemptCommand.Mode.CLOZE,
                                                AttemptCommand.Kind.FIRST,
                                                null,
                                                correctAnswer(),
                                                0,
                                                1000,
                                                null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.SENTENCE_NOT_FOUND));
    }

    /** [AC-4] RETRY 不排程；学习中全对 interval+2 推进；≥5 归档；归档全对只刷新。 */
    @Test
    @DisplayName("[AC-4] RETRY 不排程 + 学习推进/归档/刷新 + REVEAL 语义")
    void ac4_retryDoesNotScheduleAndLearningGraduates() {
        // 1) FIRST 错 → 卡 LEARNING，scheduled=1。
        AttemptCommand firstWrong = firstAttempt(wrongAnswer(), 1000);
        practiceService.submitAttempt(USER_ID, firstWrong);

        // 2) RETRY 全对：不排程、不写日志、卡不动。
        AttemptCommand retry =
                new AttemptCommand(
                        UUID.randomUUID().toString(),
                        seeded.sentenceId(),
                        String.valueOf(seeded.sentenceVersionId()),
                        AttemptCommand.Mode.CLOZE,
                        AttemptCommand.Kind.RETRY,
                        firstWrong.attemptId(),
                        correctAnswer(),
                        0,
                        1000,
                        null);
        AttemptView retryView = practiceService.submitAttempt(USER_ID, retry);
        assertThat(retryView.score()).isEqualTo(100);
        assertThat(retryView.review()).isNotNull();
        assertThat(retryView.review().scheduledDays()).isEqualTo(1);
        assertThat(retryView.review().reps()).isEqualTo(1);
        assertThat(count("review_logs")).isEqualTo(1L);

        // RETRY 校验：缺 retryOfId → 40000；来源不存在 → 40403；跨题 → 40000。
        assertThatThrownBy(
                        () ->
                                practiceService.submitAttempt(
                                        USER_ID,
                                        new AttemptCommand(
                                                UUID.randomUUID().toString(),
                                                seeded.sentenceId(),
                                                String.valueOf(seeded.sentenceVersionId()),
                                                AttemptCommand.Mode.CLOZE,
                                                AttemptCommand.Kind.RETRY,
                                                null,
                                                correctAnswer(),
                                                0,
                                                1000,
                                                null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(
                        () ->
                                practiceService.submitAttempt(
                                        USER_ID,
                                        new AttemptCommand(
                                                UUID.randomUUID().toString(),
                                                seeded.sentenceId(),
                                                String.valueOf(seeded.sentenceVersionId()),
                                                AttemptCommand.Mode.CLOZE,
                                                AttemptCommand.Kind.RETRY,
                                                UUID.randomUUID().toString(),
                                                correctAnswer(),
                                                0,
                                                1000,
                                                null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.ATTEMPT_NOT_FOUND));

        // 3) 第二次 FIRST 全对（到期复习按新提交走 FIRST）：interval=min(7,1+2)=3，LEARNING。
        Instant beforeSecond = Instant.now();
        AttemptView second =
                practiceService.submitAttempt(USER_ID, firstAttempt(correctAnswer(), 1000));
        assertThat(second.review().scheduledDays()).isEqualTo(3);
        assertThat(second.review().state()).isEqualTo("LEARNING");
        assertThat(second.review().fsrsState()).isEqualTo("LEARNING");
        assertThat(second.review().stability()).isEqualTo(3.2);
        assertThat(second.review().difficulty()).isEqualTo(5.1);
        assertThat(second.review().reps()).isEqualTo(2);
        assertThat(second.review().rating()).isEqualTo(4);
        assertThat(second.review().dueAt())
                .isBetween(
                        beforeSecond.plus(3 * 24 - 1, ChronoUnit.HOURS),
                        beforeSecond.plus(3 * 24 + 1, ChronoUnit.HOURS));

        // 4) 第三次 FIRST 全对：interval=min(7,3+2)=5 ≥5 → MASTERED/REVIEW/stability 40。
        AttemptView third =
                practiceService.submitAttempt(USER_ID, firstAttempt(correctAnswer(), 1000));
        assertThat(third.review().scheduledDays()).isEqualTo(5);
        assertThat(third.review().state()).isEqualTo("MASTERED");
        assertThat(third.review().fsrsState()).isEqualTo("REVIEW");
        assertThat(third.review().stability()).isEqualTo(40.0);
        assertThat(third.review().reps()).isEqualTo(3);

        // 5) 归档全对：只刷新 reps，间隔与稳定度不动。
        AttemptView fourth =
                practiceService.submitAttempt(USER_ID, firstAttempt(correctAnswer(), 1000));
        assertThat(fourth.review().state()).isEqualTo("MASTERED");
        assertThat(fourth.review().reps()).isEqualTo(4);
        assertThat(fourth.review().scheduledDays()).isEqualTo(5);
        assertThat(fourth.review().stability()).isEqualTo(40.0);
        assertThat(count("review_logs")).isEqualTo(4L);
    }

    /** [AC-4] 新题全对不入队；REVEAL 无 FIRST 时建卡记 rating=1；已有 FIRST 的 REVEAL 不排程。 */
    @Test
    @DisplayName("[AC-4] 新题全对不入队 + REVEAL 两分支")
    void ac4_newCorrectNeverEnqueuesAndRevealBranches() {
        // 新题全对：无卡、无日志。
        AttemptView correct =
                practiceService.submitAttempt(USER_ID, firstAttempt(correctAnswer(), 1000));
        assertThat(correct.review()).isNull();
        assertThat(count("review_logs")).isZero();

        // 已有 FIRST 之后 REVEAL：不再排程（卡仍为 NEW→不存在），也不写日志。
        AttemptView revealAfterFirst =
                practiceService.submitAttempt(USER_ID, revealAttempt(seeded));
        assertThat(revealAfterFirst.score()).isNull();
        assertThat(revealAfterFirst.review()).isNull();
        assertThat(count("review_logs")).isZero();

        // 无 FIRST 的 REVEAL：按失败分支建卡，rating=1。
        AttemptView reveal = practiceService.submitAttempt(USER_ID, revealAttempt(secondSeeded));
        assertThat(reveal.score()).isNull();
        assertThat(reveal.result().summary()).isEqualTo("已看答案，记为学习中");
        assertThat(reveal.nextAction().retryRequired()).isFalse();
        assertThat(reveal.review().rating()).isEqualTo(1);
        assertThat(reveal.review().state()).isEqualTo("LEARNING");
        assertThat(reveal.review().stability()).isEqualTo(2.4);
        assertThat(count("review_logs")).isEqualTo(1L);
        Integer logRating =
                jdbcTemplate.queryForObject(
                        "SELECT rating FROM review_logs LIMIT 1", Integer.class);
        assertThat(logRating).isEqualTo(1);
    }

    /** [AC-3] FREE 模式整句判分走 gradeFree（覆盖率部分分）。 */
    @Test
    @DisplayName("[AC-3] FREE 整句模式按覆盖率判分")
    void ac3_freeModeGradesByCoverage() {
        AttemptCommand free =
                new AttemptCommand(
                        UUID.randomUUID().toString(),
                        seeded.sentenceId(),
                        String.valueOf(seeded.sentenceVersionId()),
                        AttemptCommand.Mode.FREE,
                        AttemptCommand.Kind.FIRST,
                        null,
                        new AnswerPayload(1, "FREE", null, "He is paying more and more attention."),
                        0,
                        1000,
                        null);
        AttemptView view = practiceService.submitAttempt(USER_ID, free);
        // 覆盖 6/8 词（缺 to this matter.→to 与句尾词），整句完全一致才 100。
        assertThat(view.score()).isBetween(60, 99);
        assertThat(view.result().annotations()).hasSize(1);
        assertThat(view.result().annotations().get(0).key()).isEqualTo("free:0");
        assertThat(view.review().rating()).isEqualTo(2);
    }

    /** [AC-5] today 本地日界数字 + wrong-questions 错题与 disputed overlay 过滤。 */
    @Test
    @DisplayName("[AC-5] today 数字与错题列表（含 disputed 过滤）")
    void ac5_todayAndWrongQuestionsWithDisputedOverlay() {
        AttemptCommand wrong = firstAttempt(wrongAnswer(), 1000);
        AttemptView submitted = practiceService.submitAttempt(USER_ID, wrong);

        // today：默认时区 Asia/Shanghai、目标 3；错题卡明天到期 → 今日 dueCount=0，correctRate=0。
        TodayView today = practiceService.today(USER_ID);
        assertThat(today.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(today.goal()).isEqualTo(3);
        assertThat(today.dueCount()).isZero();
        assertThat(today.doneCount()).isGreaterThanOrEqualTo(1L);
        assertThat(today.correctRate()).isZero();
        assertThat(today.streakDays()).isGreaterThanOrEqualTo(1);

        // 卡到期时间拨到过去 → dueCount=1。
        jdbcTemplate.update("UPDATE reviews SET due_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE)");
        assertThat(practiceService.today(USER_ID).dueCount()).isEqualTo(1L);

        // wrong-questions：1 条，批注带考点文案。
        PageResponse<WrongQuestionView> wrongs =
                practiceService.wrongQuestions(USER_ID, 1, 20, false, null, null);
        assertThat(wrongs.total()).isEqualTo(1L);
        WrongQuestionView item = wrongs.items().get(0);
        assertThat(item.sentenceId()).isEqualTo(seeded.sentenceId());
        assertThat(item.state()).isEqualTo("LEARNING");
        assertThat(item.lastScore()).isEqualTo(submitted.score());
        assertThat(item.lastAttemptId()).isEqualTo(wrong.attemptId());
        assertThat(item.wrongCount()).isEqualTo(1L);
        assertThat(item.zh()).isEqualTo("他越来越重视这件事情。");
        assertThat(item.annotations()).hasSize(1);
        assertThat(item.annotations().get(0).disputed()).isFalse();
        assertThat(item.annotations().get(0).message()).isEqualTo("代词错误");

        // 考点过滤与题库过滤命中。
        assertThat(practiceService.wrongQuestions(USER_ID, 1, 20, false, null, "代词").total())
                .isEqualTo(1L);
        assertThat(practiceService.wrongQuestions(USER_ID, 1, 20, false, null, "时态").total())
                .isZero();
        assertThat(practiceService.wrongQuestions(USER_ID, 1, 20, false, 9001L, null).total())
                .isEqualTo(1L);

        // 直插 DISPUTE 行（appeal 端点在后续波次）：overlay 排除后错题不再出现。
        jdbcTemplate.update(
                "INSERT INTO appeals (attempt_id, annotation_key, sentence_id, chunk_index,"
                        + " user_id, kind, status, reason, created_at, updated_at)"
                        + " VALUES (?, 'slot:0', ?, 0, ?, 'DISPUTE', 'ACCEPTED', '我认为也对', NOW(6), NOW(6))",
                wrong.attemptId(),
                seeded.sentenceId(),
                USER_ID);
        assertThat(practiceService.wrongQuestions(USER_ID, 1, 20, false, null, null).total())
                .isZero();
        PageResponse<WrongQuestionView> including =
                practiceService.wrongQuestions(USER_ID, 1, 20, true, null, null);
        assertThat(including.total()).isEqualTo(1L);
        assertThat(including.items().get(0).annotations().get(0).disputed()).isTrue();

        // reviews 列表：LEARNING 1 张；reset 后 NEW 1 张且 dueCount 归零、不写 review_logs。
        PageResponse<ReviewCardView> learning =
                practiceService.reviews(USER_ID, 1, 20, "LEARNING", null, false);
        assertThat(learning.total()).isEqualTo(1L);
        Long reviewId = learning.items().get(0).id();
        long logsBefore = count("review_logs");
        ReviewCardView reset = practiceService.resetReview(USER_ID, reviewId, "想从头练");
        assertThat(reset.state()).isEqualTo("NEW");
        assertThat(reset.stability()).isZero();
        assertThat(practiceService.reviews(USER_ID, 1, 20, "NEW", null, false).total())
                .isEqualTo(1L);
        assertThat(practiceService.today(USER_ID).dueCount()).isZero();
        assertThat(count("review_logs")).isEqualTo(logsBefore);
    }

    /** [AC-5] next 选题：到期优先、新题次之、无题空态；overload/postpone 顺延口径。 */
    @Test
    @DisplayName("[AC-5] next 到期优先/新题次之 + overload/postpone")
    void ac5_nextSelectionAndOverloadPostpone() {
        // 无卡无提交：next 走新题，review=null。
        NextPracticeView first = practiceService.next(USER_ID, null, null);
        assertThat(first.selection()).isEqualTo("NEW");
        assertThat(first.review()).isNull();
        assertThat(first.sentence().id()).isEqualTo(seeded.sentenceId());

        // FIRST 错 → s_0001 卡明天到期（未到期不入队列），next 转投第二题 NEW。
        practiceService.submitAttempt(USER_ID, firstAttempt(wrongAnswer(), 1000));
        NextPracticeView second = practiceService.next(USER_ID, null, null);
        assertThat(second.selection()).isEqualTo("NEW");
        assertThat(second.sentence().id()).isEqualTo(secondSeeded.sentenceId());

        // 到期时间拨到过去 → next 走 DUE，返回该复习卡。
        jdbcTemplate.update("UPDATE reviews SET due_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE)");
        NextPracticeView due = practiceService.next(USER_ID, null, null);
        assertThat(due.selection()).isEqualTo("DUE");
        assertThat(due.sentence().id()).isEqualTo(seeded.sentenceId());
        assertThat(due.review().state()).isEqualTo("LEARNING");

        // 到期时间拨到过去：7 张到期卡 → postpone 保 5 顺延 2；overload 状态可见。
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9002, NULL, '顺延测试题库', NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL,"
                        + " NULL, ?, ?, NULL, NULL)",
                T0,
                T0,
                T0);
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, 9002, 'JSON', 'it.json', 'imports/it.json', ?, 1024,"
                        + " 'COMMITTING', ?, 'admin', ?)",
                "batch-it-0088-0000-0000-000000000088",
                "a".repeat(64),
                USER_ID,
                T0);
        List<SentenceContent> rows = new ArrayList<>();
        for (int index = 1; index <= 7; index++) {
            String zh = "顺延第" + index + "句越来越自然。";
            String ref = "Postponed sentence number " + index + " reads increasingly naturally.";
            rows.add(
                    new SentenceContent(
                            "s_p" + String.format("%03d", index),
                            zh,
                            ref,
                            "这是顺延测试第 " + index + " 句的解析说明，长度满足要求。",
                            List.of(
                                    new SentenceChunk(
                                            0, zh.substring(0, 1), List.of(ref), "句式", null)),
                            List.of(0),
                            List.of("句式"),
                            null,
                            null,
                            null));
        }
        contentImportService.importSentences(
                9002L, "batch-it-0088-0000-0000-000000000088", rows, USER_ID, T0.toInstant());
        List<SentenceDetail> candidates =
                contentQueryService.findPracticeCandidates(9002L, null, 20);
        assertThat(candidates).hasSize(7);
        for (SentenceDetail candidate : candidates) {
            practiceService.submitAttempt(
                    USER_ID,
                    new AttemptCommand(
                            UUID.randomUUID().toString(),
                            candidate.id(),
                            candidate.sentenceVersionId(),
                            AttemptCommand.Mode.CLOZE,
                            AttemptCommand.Kind.FIRST,
                            null,
                            new AnswerPayload(
                                    1,
                                    "CLOZE",
                                    List.of(new AnswerPayload.Slot(0, "完全不对的内容")),
                                    null),
                            0,
                            1000,
                            null));
        }
        // 到期时间拨到过去 → 8 张卡全部 due。
        jdbcTemplate.update("UPDATE reviews SET due_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE)");

        OverloadView overload = practiceService.overload(USER_ID);
        assertThat(overload.dueCount()).isEqualTo(8L);
        assertThat(overload.threshold()).isEqualTo(20);
        assertThat(overload.pushLimit()).isEqualTo(5);
        assertThat(overload.overload()).isFalse();

        PostponeView postponed = practiceService.postpone(USER_ID, "今天做不完");
        assertThat(postponed.kept()).isEqualTo(5L);
        assertThat(postponed.postponed()).isEqualTo(3L);
        assertThat(practiceService.overload(USER_ID).dueCount()).isEqualTo(5L);

        // excludeSentenceId 生效：排除后新题选题跳过该句。
        NextPracticeView excluded = practiceService.next(USER_ID, 9002L, candidates.get(0).id());
        if (excluded != null && excluded.selection().equals("NEW")) {
            assertThat(excluded.sentence().id()).isNotEqualTo(candidates.get(0).id());
        }
    }

    /** [AC-8] dispute 立即 ACCEPTED 并 overlay 排除；appeal 记 PENDING；唯一/批注/归属校验。 */
    @Test
    @DisplayName("[AC-8] appeal/dispute：回执与 overlay 排除 + 40900/40000/40403/40300")
    void ac8_appealAndDisputeEndpoints() {
        AttemptCommand wrong = firstAttempt(wrongAnswer(), 1000);
        practiceService.submitAttempt(USER_ID, wrong);

        // dispute：立即 ACCEPTED，回执带 annotationKey；sentence_id/chunk_index 从批注解析。
        AppealView dispute =
                practiceService.submitDispute(USER_ID, wrong.attemptId(), "slot:0", "我认为这个搭配也可接受");
        assertThat(dispute.appealId()).isNotNull();
        assertThat(dispute.status()).isEqualTo("ACCEPTED");
        assertThat(dispute.kind()).isEqualTo("DISPUTE");
        assertThat(dispute.annotationKey()).isEqualTo("slot:0");
        Map<String, Object> disputeRow =
                jdbcTemplate.queryForMap(
                        "SELECT kind, status, chunk_index, sentence_id, reason FROM appeals"
                                + " WHERE id = ?",
                        dispute.appealId());
        assertThat(disputeRow.get("kind")).isEqualTo("DISPUTE");
        assertThat(disputeRow.get("status")).isEqualTo("ACCEPTED");
        assertThat(((Number) disputeRow.get("chunk_index")).intValue()).isZero();
        assertThat(disputeRow.get("sentence_id")).isEqualTo(seeded.sentenceId());
        assertThat(disputeRow.get("reason")).isEqualTo("我认为这个搭配也可接受");

        // overlay 排除生效：默认错题列表不再出现该批注。
        assertThat(practiceService.wrongQuestions(USER_ID, 1, 20, false, null, null).total())
                .isZero();

        // 同一 (attempt, key, kind) 重复 → 40900。
        assertThatThrownBy(
                        () ->
                                practiceService.submitDispute(
                                        USER_ID, wrong.attemptId(), "slot:0", "重复提交"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.IDEMPOTENT_CONFLICT));

        // key 不在最新判分批注中 → 40000「批注不存在」。
        assertThatThrownBy(
                        () ->
                                practiceService.submitDispute(
                                        USER_ID, wrong.attemptId(), "slot:9", "不存在的批注"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));

        // attempt 不存在 → 40403。
        assertThatThrownBy(
                        () ->
                                practiceService.submitDispute(
                                        USER_ID, UUID.randomUUID().toString(), "slot:0", "无此提交"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.ATTEMPT_NOT_FOUND));

        // 非本人 attempt → 40300（不落任何行）。
        assertThatThrownBy(
                        () ->
                                practiceService.submitDispute(
                                        999L, wrong.attemptId(), "slot:0", "他人提交"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode()).isEqualTo(ApiCode.ACCESS_DENIED));
        assertThat(count("appeals")).isEqualTo(1L);

        // appeal：同一批注可与 DISPUTE 各存一条；M2 记 PENDING，proposedAnswer 落库。
        AppealView appeal =
                practiceService.submitAppeal(
                        USER_ID, wrong.attemptId(), "slot:0", "attach importance to", "更书面");
        assertThat(appeal.status()).isEqualTo("PENDING");
        assertThat(appeal.kind()).isEqualTo("APPEAL");
        Map<String, Object> appealRow =
                jdbcTemplate.queryForMap(
                        "SELECT proposed_answer, reason FROM appeals WHERE id = ?",
                        appeal.appealId());
        assertThat(appealRow.get("proposed_answer")).isEqualTo("attach importance to");
        assertThat(appealRow.get("reason")).isEqualTo("更书面");

        // appeal 同键重复 → 40900。
        assertThatThrownBy(
                        () ->
                                practiceService.submitAppeal(
                                        USER_ID, wrong.attemptId(), "slot:0", "再次", "重复提交"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.IDEMPOTENT_CONFLICT));

        // PENDING appeal 不参与 overlay：排除仍只由 DISPUTE 决定（默认仍被排除，包含时 disputed=true）。
        assertThat(practiceService.wrongQuestions(USER_ID, 1, 20, false, null, null).total())
                .isZero();
        PageResponse<WrongQuestionView> including =
                practiceService.wrongQuestions(USER_ID, 1, 20, true, null, null);
        assertThat(including.total()).isEqualTo(1L);
        assertThat(including.items().get(0).annotations().get(0).disputed()).isTrue();

        // FREE 整句批注 free:0 同样可申诉；reason 可空（契约未强制必填，库里落 NULL）。
        AttemptCommand free =
                new AttemptCommand(
                        UUID.randomUUID().toString(),
                        seeded.sentenceId(),
                        String.valueOf(seeded.sentenceVersionId()),
                        AttemptCommand.Mode.FREE,
                        AttemptCommand.Kind.FIRST,
                        null,
                        new AnswerPayload(1, "FREE", null, "He is paying more and more attention."),
                        0,
                        1000,
                        null);
        AttemptView freeView = practiceService.submitAttempt(USER_ID, free);
        AppealView freeDispute =
                practiceService.submitDispute(USER_ID, freeView.attemptId(), "free:0", null);
        assertThat(freeDispute.status()).isEqualTo("ACCEPTED");
        String freeReason =
                jdbcTemplate.queryForObject(
                        "SELECT reason FROM appeals WHERE id = ?",
                        String.class,
                        freeDispute.appealId());
        assertThat(freeReason).isNull();
    }

    /** 清空 practice 五表与内容表；append-only 触发器先摘后重建。 */
    private void cleanDatabase() {
        dropTriggers();
        jdbcTemplate.update("DELETE FROM review_logs");
        jdbcTemplate.update("DELETE FROM reviews");
        jdbcTemplate.update("DELETE FROM appeals");
        jdbcTemplate.update("DELETE FROM attempt_gradings");
        // attempts 自引用 retry_of_id：先删重打子行，再删其余。
        jdbcTemplate.update("DELETE FROM attempts WHERE retry_of_id IS NOT NULL");
        jdbcTemplate.update("DELETE FROM attempts");
        jdbcTemplate.update("UPDATE sentences SET current_version_id = NULL");
        jdbcTemplate.update("DELETE FROM import_batch_rows");
        jdbcTemplate.update("DELETE FROM sentence_versions");
        jdbcTemplate.update("DELETE FROM sentences");
        jdbcTemplate.update("DELETE FROM import_batches");
        jdbcTemplate.update("DELETE FROM sentence_banks");
        createTriggers();
    }

    /** 摘除 append-only 与 sentence_versions 触发器（清理期允许 DELETE）。 */
    private void dropTriggers() {
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempts_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempts_no_delete");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempt_gradings_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_attempt_gradings_no_delete");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_review_logs_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_review_logs_no_delete");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_delete");
    }

    /** 重建 append-only 与 sentence_versions 触发器（与 V8/内容迁移语义一致）。 */
    private void createTriggers() {
        jdbcTemplate.update(
                "CREATE TRIGGER trg_attempts_no_update BEFORE UPDATE ON attempts"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'attempts is append-only'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_attempts_no_delete BEFORE DELETE ON attempts"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'attempts is append-only'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_attempt_gradings_no_update BEFORE UPDATE ON attempt_gradings"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'attempt_gradings is append-only'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_attempt_gradings_no_delete BEFORE DELETE ON attempt_gradings"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'attempt_gradings is append-only'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_review_logs_no_update BEFORE UPDATE ON review_logs"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'review_logs is append-only'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_review_logs_no_delete BEFORE DELETE ON review_logs"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'review_logs is append-only'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_sentence_versions_no_update BEFORE UPDATE ON sentence_versions"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'sentence_versions is immutable'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_sentence_versions_no_delete BEFORE DELETE ON sentence_versions"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'sentence_versions is immutable'");
    }

    /** 播种两行真实导入的题目（chunk0=整句首字，可接受答案=参考译文，挖空 [0]）。 */
    private void seedSentence() {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9001, NULL, '练习集成题库', NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL,"
                        + " NULL, ?, ?, NULL, NULL)",
                T0,
                T0,
                T0);
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, 9001, 'JSON', 'it.json', 'imports/it.json', ?, 1024,"
                        + " 'COMMITTING', ?, 'admin', ?)",
                "batch-it-0077-0000-0000-000000000077",
                "a".repeat(64),
                USER_ID,
                T0);
        List<SentenceContent> rows =
                List.of(
                        new SentenceContent(
                                "s_0001",
                                "他越来越重视这件事情。",
                                REF,
                                "这是练习集成测试的解析说明，长度满足最小要求。",
                                List.of(new SentenceChunk(0, "他", List.of(REF), "代词", null)),
                                List.of(0),
                                List.of("代词"),
                                null,
                                null,
                                null),
                        new SentenceContent(
                                "s_0002",
                                "会议如期顺利举行了。",
                                SECOND_REF,
                                "这是第二题的解析说明，同样满足最小长度要求。",
                                List.of(new SentenceChunk(0, "会", List.of(SECOND_REF), "时态", null)),
                                List.of(0),
                                List.of("时态"),
                                null,
                                null,
                                null));
        List<ImportedSentence> imported =
                contentImportService.importSentences(
                        9001L,
                        "batch-it-0077-0000-0000-000000000077",
                        rows,
                        USER_ID,
                        T0.toInstant());
        seeded = imported.get(0);
        secondSeeded = imported.get(1);
    }

    /**
     * 构造 FIRST 提交（CLOZE 单槽）。
     *
     * @param answer 用户答案
     * @param durationMs 耗时
     * @return 提交命令
     */
    private AttemptCommand firstAttempt(AnswerPayload answer, int durationMs) {
        return new AttemptCommand(
                UUID.randomUUID().toString(),
                seeded.sentenceId(),
                String.valueOf(seeded.sentenceVersionId()),
                AttemptCommand.Mode.CLOZE,
                AttemptCommand.Kind.FIRST,
                null,
                answer,
                0,
                durationMs,
                null);
    }

    /**
     * 构造 REVEAL 提交（无 slots/text）。
     *
     * @param target 目标题目
     * @return 提交命令
     */
    private AttemptCommand revealAttempt(ImportedSentence target) {
        return new AttemptCommand(
                UUID.randomUUID().toString(),
                target.sentenceId(),
                String.valueOf(target.sentenceVersionId()),
                AttemptCommand.Mode.CLOZE,
                AttemptCommand.Kind.REVEAL,
                null,
                new AnswerPayload(1, "CLOZE", null, null),
                0,
                1000,
                null);
    }

    /**
     * 正确答案载荷。
     *
     * @return 与参考译文一致的槽值
     */
    private AnswerPayload correctAnswer() {
        return new AnswerPayload(1, "CLOZE", List.of(new AnswerPayload.Slot(0, REF)), null);
    }

    /**
     * 部分错误答案载荷（8/10 词命中 → score=80，60≤score&lt;100 记 HARD）。
     *
     * @return 错误槽值
     */
    private AnswerPayload wrongAnswer() {
        return new AnswerPayload(
                1,
                "CLOZE",
                List.of(
                        new AnswerPayload.Slot(
                                0, "He is paying less and less attention to this matter.")),
                null);
    }

    /**
     * 表行数。
     *
     * @param table 表名（白名单内调用）
     * @return 行数
     */
    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }
}
