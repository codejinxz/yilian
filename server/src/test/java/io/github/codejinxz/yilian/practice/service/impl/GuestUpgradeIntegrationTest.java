package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.ImportedSentence;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.dto.GuestUpgradeCommand;
import io.github.codejinxz.yilian.practice.service.vo.GuestUpgradeView;

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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 游客升级集成测试（真实 MySQL yilian_test）[AC-7]：≤100 条校验、同载荷 skip、 异载荷 40903 整体回滚、retryOfId 链可迁移、迁移不触发 FSRS
 * 排程。
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
class GuestUpgradeIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 12, 0, 0));

    private static final long USER_ID = 8L;

    private static final String REF_A = "He is paying more and more attention to this matter.";

    private static final String REF_B = "The meeting was held as scheduled.";

    @Autowired private PracticeService practiceService;

    @Autowired private ContentImportService contentImportService;

    @Autowired private JdbcTemplate jdbcTemplate;

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

    /** [AC-7] 批量迁入：imported=3（含 retryOfId 链），不排程；重放全 skip。 */
    @Test
    @DisplayName("[AC-7] 批量迁入 + retry 链 + 不排程 + 幂等重放")
    void ac7_importBatchWithRetryChainAndIdempotentReplay() {
        AttemptCommand first = clozeFirst(seededA, wrongSlotA(), null);
        AttemptCommand second = clozeFirst(seededB, wrongSlotB(), null);
        AttemptCommand retry =
                new AttemptCommand(
                        UUID.randomUUID().toString(),
                        seededB.sentenceId(),
                        String.valueOf(seededB.sentenceVersionId()),
                        AttemptCommand.Mode.CLOZE,
                        AttemptCommand.Kind.RETRY,
                        second.attemptId(),
                        new AnswerPayload(1, "CLOZE", List.of(correctSlot(REF_B)), null),
                        0,
                        1000,
                        null);

        GuestUpgradeView result =
                practiceService.guestUpgrade(
                        USER_ID, new GuestUpgradeCommand(List.of(first, second, retry)));
        assertThat(result.imported()).isEqualTo(3);
        assertThat(result.skipped()).isZero();
        assertThat(result.conflicts()).isZero();
        assertThat(count("attempts")).isEqualTo(3L);
        assertThat(count("attempt_gradings")).isEqualTo(3L);
        // 迁移不触发 FSRS：无卡、无排程日志。
        assertThat(count("reviews")).isZero();
        assertThat(count("review_logs")).isZero();

        // 同载荷重放：全部 skip，无新行。
        GuestUpgradeView replay =
                practiceService.guestUpgrade(
                        USER_ID, new GuestUpgradeCommand(List.of(first, second, retry)));
        assertThat(replay.imported()).isZero();
        assertThat(replay.skipped()).isEqualTo(3);
        assertThat(count("attempts")).isEqualTo(3L);
    }

    /** [AC-7] 同 id 异载荷：40903 且整体回滚（先迁入的行也不落库）。 */
    @Test
    @DisplayName("[AC-7] 异载荷 40903 整体回滚")
    void ac7_conflictingPayloadRollsBackWholeBatch() {
        AttemptCommand first = clozeFirst(seededA, wrongSlotA(), null);
        AttemptCommand second = clozeFirst(seededB, wrongSlotB(), null);
        practiceService.guestUpgrade(USER_ID, new GuestUpgradeCommand(List.of(first, second)));
        assertThat(count("attempts")).isEqualTo(2L);

        // 同 attemptId、不同答案 → 冲突；同批先到的「新行」必须回滚。
        AttemptCommand conflicting =
                new AttemptCommand(
                        first.attemptId(),
                        seededA.sentenceId(),
                        String.valueOf(seededA.sentenceVersionId()),
                        AttemptCommand.Mode.CLOZE,
                        AttemptCommand.Kind.FIRST,
                        null,
                        new AnswerPayload(1, "CLOZE", List.of(correctSlot(REF_A)), null),
                        0,
                        1000,
                        null);
        AttemptCommand fresh = clozeFirst(seededA, correctSlot(REF_A), null);
        assertThatThrownBy(
                        () ->
                                practiceService.guestUpgrade(
                                        USER_ID,
                                        new GuestUpgradeCommand(List.of(fresh, conflicting))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.ATTEMPT_ID_REUSED));
        assertThat(count("attempts")).isEqualTo(2L);
    }

    /** [AC-7] 超过 100 条整批拒绝。 */
    @Test
    @DisplayName("[AC-7] 超过 100 条 40000")
    void ac7_rejectOverHundredItems() {
        List<AttemptCommand> items = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            items.add(clozeFirst(seededA, wrongSlotA(), null));
        }
        assertThatThrownBy(
                        () -> practiceService.guestUpgrade(USER_ID, new GuestUpgradeCommand(items)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThat(count("attempts")).isZero();
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
        for (String[] trigger : triggerDefs()) {
            jdbcTemplate.update("DROP TRIGGER IF EXISTS " + trigger[0]);
        }
    }

    /** 重建 append-only 与 sentence_versions 触发器。 */
    private void createTriggers() {
        for (String[] trigger : triggerDefs()) {
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

    /**
     * 触发器定义：名称/表/事件/文案。
     *
     * @return 定义列表
     */
    private String[][] triggerDefs() {
        return new String[][] {
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
    }

    /** 播种两题。 */
    private void seedSentences() {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9001, NULL, '升级集成题库', NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL,"
                        + " NULL, ?, ?, NULL, NULL)",
                T0,
                T0,
                T0);
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, 9001, 'JSON', 'it.json', 'imports/it.json', ?, 1024,"
                        + " 'COMMITTING', ?, 'admin', ?)",
                "batch-it-0099-0000-0000-000000000099",
                "a".repeat(64),
                USER_ID,
                T0);
        List<SentenceContent> rows =
                List.of(
                        new SentenceContent(
                                "s_a001",
                                "他越来越重视这件事情。",
                                REF_A,
                                "这是升级集成测试第一题的解析说明，长度满足要求。",
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
                                "这是升级集成测试第二题的解析说明，长度满足要求。",
                                List.of(new SentenceChunk(0, "会", List.of(REF_B), "时态", null)),
                                List.of(0),
                                List.of("时态"),
                                null,
                                null,
                                null));
        List<ImportedSentence> imported =
                contentImportService.importSentences(
                        9001L,
                        "batch-it-0099-0000-0000-000000000099",
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
     * @param retryOfId 重打来源，可空
     * @return 提交命令
     */
    private AttemptCommand clozeFirst(
            ImportedSentence target, AnswerPayload.Slot slot, String retryOfId) {
        return new AttemptCommand(
                UUID.randomUUID().toString(),
                target.sentenceId(),
                String.valueOf(target.sentenceVersionId()),
                AttemptCommand.Mode.CLOZE,
                AttemptCommand.Kind.FIRST,
                retryOfId,
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
     * 表行数（白名单内调用）。
     *
     * @param table 表名
     * @return 行数
     */
    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    /**
     * A 题错误槽值（80 分）。
     *
     * @return 槽
     */
    private AnswerPayload.Slot wrongSlotA() {
        return new AnswerPayload.Slot(0, "He is paying less and less attention to this matter.");
    }

    /**
     * B 题错误槽值（部分命中）。
     *
     * @return 槽
     */
    private AnswerPayload.Slot wrongSlotB() {
        return new AnswerPayload.Slot(0, "The meeting was held as scheduled yesterday.");
    }
}
