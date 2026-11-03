package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.ContentQueryService;
import io.github.codejinxz.yilian.content.service.dto.ImportedSentence;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.vo.AccuracyView;
import io.github.codejinxz.yilian.practice.service.vo.ErrorTagsView;

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

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 统计集成测试（真实 MySQL yilian_test）[AC-6]：accuracy（totalAttempts/correctRate/
 * averageScore/byMode/daily14/recent）与 error-tags（dispute 排除）。
 *
 * <p>Spring 上下文与 {@code PracticeFlowIntegrationTest} 同配置，复用缓存；自清口径一致。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false",
            "yilian.security.jwt.secret=test-only-yilian-jwt-secret-0123456789abcdef"
        })
@ActiveProfiles("local")
class AnalyticsIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 12, 0, 0));

    private static final long USER_ID = 8L;

    private static final String REF_A = "He is paying more and more attention to this matter.";

    private static final String REF_B = "The meeting was held as scheduled.";

    @Autowired private PracticeService practiceService;

    @Autowired private ContentImportService contentImportService;

    @Autowired private ContentQueryService contentQueryService;

    @Autowired private JdbcTemplate jdbcTemplate;

    /** 导入的两题：A 用于错题（tag 代词），B 用于全对（tag 时态）。 */
    private ImportedSentence seededA;

    private ImportedSentence seededB;

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

    /** 清库并播种用户与两题。 */
    @BeforeEach
    void cleanAndSeed() {
        cleanDatabase();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, username, password_hash, role, enabled)"
                        + " VALUES (?, 'it-learner@example.com', 'it-learner', 'seeded', 'USER', TRUE)"
                        + " ON DUPLICATE KEY UPDATE enabled = TRUE",
                USER_ID);
        seedSentences();
    }

    /** 清空 practice 与内容表，防跨套件外键污染。 */
    @AfterEach
    void cleanAfter() {
        cleanDatabase();
    }

    /** [AC-6] accuracy：2 提交 1 全对 → correctRate 50、averageScore 90、byMode/daily14/recent。 */
    @Test
    @DisplayName("[AC-6] accuracy 汇总/分模式/逐日/最近提交")
    void ac6_accuracyAggregates() {
        // A 错（80 分）、B 全对（100 分，快答 EASY）。
        practiceService.submitAttempt(USER_ID, clozeFirst(seededA, wrongSlotA()));
        practiceService.submitAttempt(USER_ID, clozeFirst(seededB, correctSlot(REF_B)));

        AccuracyView accuracy = practiceService.accuracy(USER_ID, null, null, null, null);
        assertThat(accuracy.totalAttempts()).isEqualTo(2L);
        assertThat(accuracy.correctRate()).isEqualTo(50.0);
        assertThat(accuracy.averageScore()).isEqualTo(90.0);
        assertThat(accuracy.byMode()).hasSize(1);
        assertThat(accuracy.byMode().get(0).mode()).isEqualTo("CLOZE");
        assertThat(accuracy.byMode().get(0).attempts()).isEqualTo(2L);
        assertThat(accuracy.byMode().get(0).correctRate()).isEqualTo(50.0);
        // daily14 恒 14 天，最后一天包含两笔提交。
        assertThat(accuracy.daily14()).hasSize(14);
        AccuracyView.DailyAccuracyView latestDay = accuracy.daily14().get(13);
        assertThat(latestDay.attempts()).isEqualTo(2L);
        assertThat(latestDay.correctRate()).isEqualTo(50.0);
        assertThat(accuracy.daily14().get(0).attempts()).isZero();
        // recent 倒序，最新在前。
        assertThat(accuracy.recent()).hasSize(2);
        assertThat(accuracy.recent().get(0).sentenceId()).isEqualTo(seededB.sentenceId());
        assertThat(accuracy.recent().get(0).score()).isEqualTo(100);

        // mode 过滤：FREE 无提交 → 空结果。
        AccuracyView freeOnly = practiceService.accuracy(USER_ID, null, null, null, "FREE");
        assertThat(freeOnly.totalAttempts()).isZero();
        assertThat(freeOnly.correctRate()).isNull();
        // 题库过滤：bank 9001 命中全部。
        assertThat(practiceService.accuracy(USER_ID, null, null, 9001L, null).totalAttempts())
                .isEqualTo(2L);
    }

    /** [AC-6] error-tags：错题批注 tag 计数；DISPUTE 后排除。 */
    @Test
    @DisplayName("[AC-6] error-tags 聚合与 dispute 排除")
    void ac6_errorTagsExcludeDisputed() {
        var submitted = practiceService.submitAttempt(USER_ID, clozeFirst(seededA, wrongSlotA()));
        practiceService.submitAttempt(USER_ID, clozeFirst(seededB, correctSlot(REF_B)));

        ErrorTagsView tags = practiceService.errorTags(USER_ID, null, null);
        assertThat(tags.items()).hasSize(1);
        assertThat(tags.items().get(0).tag()).isEqualTo("代词");
        assertThat(tags.items().get(0).count()).isEqualTo(1L);
        assertThat(tags.items().get(0).ratio()).isEqualTo(100.0);

        // DISPUTE 该批注 → 统计排除。
        jdbcTemplate.update(
                "INSERT INTO appeals (attempt_id, annotation_key, sentence_id, chunk_index,"
                        + " user_id, kind, status, reason, created_at, updated_at)"
                        + " VALUES (?, 'slot:0', ?, 0, ?, 'DISPUTE', 'ACCEPTED', '我认为也对', NOW(6), NOW(6))",
                submitted.attemptId(),
                seededA.sentenceId(),
                USER_ID);
        ErrorTagsView afterDispute = practiceService.errorTags(USER_ID, null, null);
        assertThat(afterDispute.items()).isEmpty();
    }

    /** [AC-5] from/to 闭区间上限 92 天：超长 40000 拒绝、恰好 92 个本地日放行（015 修复轮）。 */
    @Test
    @DisplayName("[AC-5] accuracy/error-tags 区间上限 92 天")
    void ac5_analyticsRangeCappedAt92Days() {
        LocalDate end = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        // 93 个本地日（between=92）→ 40000，两个端点都拦。
        assertThatThrownBy(
                        () -> practiceService.accuracy(USER_ID, end.minusDays(92), end, null, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> practiceService.errorTags(USER_ID, end.minusDays(92), end))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        // 92 个本地日（between=91）→ 放行（区间内无提交，返回空聚合视图）。
        AccuracyView withinRange =
                practiceService.accuracy(USER_ID, end.minusDays(91), end, null, null);
        assertThat(withinRange.from()).isEqualTo(end.minusDays(91));
        assertThat(withinRange.totalAttempts()).isZero();
        assertThat(practiceService.errorTags(USER_ID, end.minusDays(91), end).items()).isEmpty();
    }

    /** 清空 practice 五表与内容表；append-only 触发器先摘后重建。 */
    private void cleanDatabase() {
        dropTriggers();
        jdbcTemplate.update("DELETE FROM review_logs");
        jdbcTemplate.update("DELETE FROM reviews");
        jdbcTemplate.update("DELETE FROM appeals");
        jdbcTemplate.update("DELETE FROM attempt_gradings");
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

    /** 摘除 append-only 与 sentence_versions 触发器。 */
    private void dropTriggers() {
        String[][] triggers = {
            {"trg_attempts_no_update", "attempts", "UPDATE", "attempts is append-only"},
            {"trg_attempts_no_delete", "attempts", "DELETE", "attempts is append-only"},
            {
                "trg_attempt_gradings_no_update",
                "attempt_gradings",
                "UPDATE",
                "attempt_gradings is append-only"
            },
            {
                "trg_attempt_gradings_no_delete",
                "attempt_gradings",
                "DELETE",
                "attempt_gradings is append-only"
            },
            {"trg_review_logs_no_update", "review_logs", "UPDATE", "review_logs is append-only"},
            {"trg_review_logs_no_delete", "review_logs", "DELETE", "review_logs is append-only"},
            {
                "trg_sentence_versions_no_update",
                "sentence_versions",
                "UPDATE",
                "sentence_versions is immutable"
            },
            {
                "trg_sentence_versions_no_delete",
                "sentence_versions",
                "DELETE",
                "sentence_versions is immutable"
            }
        };
        for (String[] trigger : triggers) {
            jdbcTemplate.update("DROP TRIGGER IF EXISTS " + trigger[0]);
        }
    }

    /** 重建 append-only 与 sentence_versions 触发器。 */
    private void createTriggers() {
        String[][] triggers = {
            {"trg_attempts_no_update", "attempts", "UPDATE", "attempts is append-only"},
            {"trg_attempts_no_delete", "attempts", "DELETE", "attempts is append-only"},
            {
                "trg_attempt_gradings_no_update",
                "attempt_gradings",
                "UPDATE",
                "attempt_gradings is append-only"
            },
            {
                "trg_attempt_gradings_no_delete",
                "attempt_gradings",
                "DELETE",
                "attempt_gradings is append-only"
            },
            {"trg_review_logs_no_update", "review_logs", "UPDATE", "review_logs is append-only"},
            {"trg_review_logs_no_delete", "review_logs", "DELETE", "review_logs is append-only"},
            {
                "trg_sentence_versions_no_update",
                "sentence_versions",
                "UPDATE",
                "sentence_versions is immutable"
            },
            {
                "trg_sentence_versions_no_delete",
                "sentence_versions",
                "DELETE",
                "sentence_versions is immutable"
            }
        };
        for (String[] trigger : triggers) {
            jdbcTemplate.update(
                    "CREATE TRIGGER "
                            + trigger[0]
                            + " BEFORE "
                            + trigger[2]
                            + " ON "
                            + trigger[1]
                            + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '"
                            + trigger[3]
                            + "'");
        }
    }

    /** 播种两题（A：代词/错题样例；B：时态/全对样例）。 */
    private void seedSentences() {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9001, NULL, '统计集成题库', NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL,"
                        + " NULL, ?, ?, NULL, NULL)",
                T0,
                T0,
                T0);
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, 9001, 'JSON', 'it.json', 'imports/it.json', ?, 1024,"
                        + " 'COMMITTING', ?, 'admin', ?)",
                "batch-it-0066-0000-0000-000000000066",
                "a".repeat(64),
                USER_ID,
                T0);
        List<SentenceContent> rows =
                List.of(
                        new SentenceContent(
                                "s_a001",
                                "他越来越重视这件事情。",
                                REF_A,
                                "这是统计集成测试第一题的解析说明，长度满足要求。",
                                List.of(new SentenceChunk(0, "他", List.of(REF_A), "代词", null)),
                                List.of(0),
                                List.of("代词"),
                                null,
                                null,
                                null),
                        new SentenceContent(
                                "s_b001",
                                "会议如期顺利举行了。",
                                REF_B,
                                "这是统计集成测试第二题的解析说明，长度满足要求。",
                                List.of(new SentenceChunk(0, "会", List.of(REF_B), "时态", null)),
                                List.of(0),
                                List.of("时态"),
                                null,
                                null,
                                null));
        List<ImportedSentence> imported =
                contentImportService.importSentences(
                        9001L,
                        "batch-it-0066-0000-0000-000000000066",
                        rows,
                        USER_ID,
                        T0.toInstant());
        seededA = imported.get(0);
        seededB = imported.get(1);
    }

    /**
     * 构造 CLOZE FIRST 提交。
     *
     * @param target 目标题目
     * @param slot 槽值
     * @return 提交命令
     */
    private AttemptCommand clozeFirst(ImportedSentence target, AnswerPayload.Slot slot) {
        return new AttemptCommand(
                UUID.randomUUID().toString(),
                target.sentenceId(),
                String.valueOf(target.sentenceVersionId()),
                AttemptCommand.Mode.CLOZE,
                AttemptCommand.Kind.FIRST,
                null,
                new AnswerPayload(1, "CLOZE", List.of(slot), null),
                0,
                1000,
                null);
    }

    /**
     * 正确槽值。
     *
     * @param ref 参考译文
     * @return 槽
     */
    private AnswerPayload.Slot correctSlot(String ref) {
        return new AnswerPayload.Slot(0, ref);
    }

    /**
     * 错误槽值（8/10 词命中 → 80 分）。
     *
     * @return 槽
     */
    private AnswerPayload.Slot wrongSlotA() {
        return new AnswerPayload.Slot(0, "He is paying less and less attention to this matter.");
    }
}
