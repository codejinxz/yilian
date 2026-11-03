package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.practice.optimizer.FsrsOptimizer;
import io.github.codejinxz.yilian.practice.service.FsrsAdminService;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.vo.AttemptView;
import io.github.codejinxz.yilian.practice.service.vo.FsrsParameterVersionView;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FSRS 管理链路集成测试（真实 MySQL yilian_test）[AC-3/AC-5]：优化器从真实 review_logs 产出
 * CANDIDATE、启用状态机（CANDIDATE→ACTIVE、旧 ACTIVE→RETIRED）、调度消费启用后的权重 （同一四分支公式换参数），以及样本不足/非法状态的服务端拒绝。
 *
 * <p>自清模式与 {@code PracticeFlowIntegrationTest} 同口径；fsrs_parameter_versions 不在常规清空 范围（V9
 * 种子要存活），本测试用 @AfterEach 恢复种子 ACTIVE 并删除用例创建的候选行。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class FsrsAdminIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 12, 0, 0));

    private static final long USER_ID = 9L;

    private static final long ADMIN_ID = 2L;

    private static final String REF = "He is paying more and more attention to this matter.";

    /** 已知权重候选：启用后失败分支稳定度应等于 w[0]、难度等于 w[4]（FSRS-6 初值语义解码）。 */
    private static final String CANDIDATE_WEIGHTS =
            "[0.5, 1.2, 2.0, 10.0, 6.0, 0.5, 2.0, 0.007, 1.5, 0.1, 1.0, 1.8, 0.1,"
                    + " 0.3, 2.2, 0.2, 3.0, 0.7, 0.3, 0.1, 0.2]";

    @Autowired private FsrsAdminService fsrsAdminService;

    @Autowired private PracticeService practiceService;

    @Autowired private ContentImportService contentImportService;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private ObjectMapper objectMapper;

    /** 当前用例导入的第一题。 */
    private String sentenceId;

    private String sentenceVersionId;

    private Long seedVersionId;

    static {
        TestDatabases.ensureDatabase();
    }

    @BeforeEach
    void seed() {
        cleanPracticeTables();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, username, password_hash, role, enabled)"
                        + " VALUES (?, 'it-fsrs-admin@example.com', 'it-fsrs-admin', 'seeded',"
                        + " 'ADMIN', TRUE)"
                        + " ON DUPLICATE KEY UPDATE enabled = TRUE",
                ADMIN_ID);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, username, password_hash, role, enabled)"
                        + " VALUES (?, 'it-fsrs-learner@example.com', 'it-fsrs-learner', 'seeded',"
                        + " 'USER', TRUE)"
                        + " ON DUPLICATE KEY UPDATE enabled = TRUE",
                USER_ID);
        seedSentence();
        seedVersionId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM fsrs_parameter_versions"
                                + " WHERE owner_type = 'GLOBAL' AND status = 'ACTIVE' LIMIT 1",
                        Long.class);
    }

    @AfterEach
    void restore() {
        // 先清练习数据（review_logs 可能引用测试创建的权重行），再恢复种子 ACTIVE、删测试候选行。
        cleanPracticeTables();
        jdbcTemplate.update(
                "UPDATE fsrs_parameter_versions SET status = 'RETIRED'"
                        + " WHERE owner_type = 'GLOBAL' AND status = 'ACTIVE' AND id <> ?",
                seedVersionId);
        jdbcTemplate.update(
                "UPDATE fsrs_parameter_versions SET status = 'ACTIVE' WHERE id = ?", seedVersionId);
        jdbcTemplate.update(
                "DELETE FROM fsrs_parameter_versions WHERE owner_type = 'GLOBAL' AND id <> ?",
                seedVersionId);
    }

    /** [AC-3] optimize：≥50 条真实 review_logs 训练出 21 维 CANDIDATE，metrics 携带训练摘要。 */
    @Test
    @DisplayName("[AC-3] optimize 从真实日志产出 CANDIDATE + 训练摘要")
    void ac3_optimizeProducesCandidateFromLogs() {
        seedReviewLogs(60);
        FsrsParameterVersionView candidate = fsrsAdminService.optimize(USER_ID, ADMIN_ID);

        assertThat(candidate.status()).isEqualTo("CANDIDATE");
        assertThat(candidate.algorithm()).isEqualTo("FSRS-6");
        assertThat(candidate.ownerType()).isEqualTo("GLOBAL");
        assertThat(candidate.weights()).hasSize(21);
        assertThat(candidate.sampleCount()).isEqualTo(60);
        assertThat(candidate.metrics()).isNotNull();
        assertThat(candidate.metrics().get("optimizer")).isEqualTo("java-sgd-m2.1");
        assertThat(((Number) candidate.metrics().get("sampleCount")).intValue()).isEqualTo(60);
        assertThat(candidate.metrics().get("logLoss")).isInstanceOf(Number.class);
        // D-1 回归防线：训练必须真的发生——损失事件 = 样本数 − 卡数（60 条日志同一张卡 → 59）；
        // 若误用日志行主键分组，每条日志自成一组全部沦为「首评」，lossEvents=0、零梯度、权重不动。
        assertThat(((Number) candidate.metrics().get("lossEvents")).intValue())
                .as("损失事件数（首评除外）")
                .isEqualTo(59);
        // 训练后损失必须低于初始损失（FSRS-6 默认权重起点）。
        assertThat(((Number) candidate.metrics().get("logLoss")).doubleValue())
                .isLessThan(((Number) candidate.metrics().get("initialLoss")).doubleValue());
        // 权重必须离开默认点：至少 5 维相对 FSRS-6 默认移动超过 0.05（真数据上必不同，
        // 防「metrics 好看但权重原样落库」的伪训练回归）。
        int movedDims = 0;
        for (int i = 0; i < 21; i++) {
            if (Math.abs(candidate.weights().get(i) - FsrsOptimizer.FSRS6_DEFAULT_WEIGHTS[i])
                    > 0.05) {
                movedDims++;
            }
        }
        assertThat(movedDims).as("相对默认权重移动的维度数").isGreaterThanOrEqualTo(5);
        // 落库行与视图一致，且不触碰 V9 种子状态。
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT status, sample_count, weights, created_by FROM"
                                + " fsrs_parameter_versions WHERE id = ?",
                        candidate.id());
        assertThat(row.get("status")).isEqualTo("CANDIDATE");
        assertThat(((Number) row.get("sample_count")).intValue()).isEqualTo(60);
        assertThat(((Number) row.get("created_by")).longValue()).isEqualTo(ADMIN_ID);
        assertThat(objectMapper.readTree((String) row.get("weights")).size()).isEqualTo(21);
        Long activeCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM fsrs_parameter_versions"
                                + " WHERE owner_type = 'GLOBAL' AND status = 'ACTIVE'",
                        Long.class);
        assertThat(activeCount).isEqualTo(1L);
    }

    /** [AC-3] 恰好 50 条（边界）：放行训练，样本数如实记 50、损失事件 49（同一张卡首评除外）。 */
    @Test
    @DisplayName("[AC-3] 恰好 50 条样本边界通过")
    void ac3_optimizeAcceptsExactlyFiftySamples() {
        seedReviewLogs(50);
        FsrsParameterVersionView candidate = fsrsAdminService.optimize(USER_ID, ADMIN_ID);
        assertThat(candidate.status()).isEqualTo("CANDIDATE");
        assertThat(candidate.sampleCount()).isEqualTo(50);
        assertThat(((Number) candidate.metrics().get("lossEvents")).intValue()).isEqualTo(49);
    }

    /** [AC-3] 版本列表：V9 种子（对象形 weights）必须能解码返回而不是 500（D-2 回归防线）。 */
    @Test
    @DisplayName("[AC-3] listParameters 兼容 V9 种子对象形 weights")
    void ac3_listParametersDecodesPrototypeSeed() {
        List<FsrsParameterVersionView> versions = fsrsAdminService.listParameters();
        FsrsParameterVersionView seed =
                versions.stream()
                        .filter(version -> version.id().equals(seedVersionId))
                        .findFirst()
                        .orElseThrow();
        assertThat(seed.status()).isEqualTo("ACTIVE");
        // 种子是 18 维对象形 {"w":[…]}：返回 w 数组原值，不伪造补齐到 21 维。
        assertThat(seed.weights())
                .containsExactly(
                        0.4, 1.2, 2.4, 5.3, 3.2, 0.8, 0.6, 2.0, 1.5, 0.3, 1.0, 2.0, 40.0, 0.6, 3.0,
                        5.1, 0.9, 0.4);
        assertThat(seed.metrics()).isNull();
    }

    /** [AC-3] 样本不足 50：40000 拒绝，不写任何候选行（「没有真实数据前不生成候选权重」）。 */
    @Test
    @DisplayName("[AC-3] 样本不足 40000 拒绝且不落行")
    void ac3_optimizeRejectsInsufficientSamples() {
        seedReviewLogs(30);
        assertThatThrownBy(() -> fsrsAdminService.optimize(USER_ID, ADMIN_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        Long candidateCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM fsrs_parameter_versions WHERE status = 'CANDIDATE'",
                        Long.class);
        assertThat(candidateCount).isZero();
    }

    /** [AC-3] 启用状态机：CANDIDATE→ACTIVE、旧种子→RETIRED；非候选 40000；未知版本 40400。 */
    @Test
    @DisplayName("[AC-3] 启用状态机 + 非候选/未知版本拒绝")
    void ac3_enableStateMachine() {
        Long candidateId = insertCandidate();
        FsrsParameterVersionView enabled = fsrsAdminService.enable(candidateId, ADMIN_ID);
        assertThat(enabled.status()).isEqualTo("ACTIVE");
        String seedStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM fsrs_parameter_versions WHERE id = ?",
                        String.class,
                        seedVersionId);
        assertThat(seedStatus).isEqualTo("RETIRED");

        // 已 ACTIVE 的版本再启用 → 40000；未知 id → 40400。
        assertThatThrownBy(() -> fsrsAdminService.enable(candidateId, ADMIN_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> fsrsAdminService.enable(999_999L, ADMIN_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.apiCode()).isEqualTo(ApiCode.NOT_FOUND));
    }

    /** [AC-5] 调度消费：启用已知权重候选后，失败排程按 FSRS-6 初值语义解码参数计算（同公式换参数）。 */
    @Test
    @DisplayName("[AC-5] 启用候选后排程消费新权重")
    void ac5_enabledCandidateDrivesScheduling() {
        Long candidateId = insertCandidate();
        fsrsAdminService.enable(candidateId, ADMIN_ID);

        // 失败 FIRST：四分支公式的失败分支按新权重计算——stability=w[0]=0.5、difficulty=w[4]=6.0。
        AttemptView view =
                practiceService.submitAttempt(
                        USER_ID,
                        new AttemptCommand(
                                UUID.randomUUID().toString(),
                                sentenceId,
                                sentenceVersionId,
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
        assertThat(view.review().stability()).isEqualTo(0.5);
        assertThat(view.review().difficulty()).isEqualTo(6.0);
        // 排程日志绑定启用后的权重版本。
        Long weightsVersionId =
                jdbcTemplate.queryForObject(
                        "SELECT weights_version_id FROM review_logs WHERE attempt_id = ?",
                        Long.class,
                        view.attemptId());
        assertThat(weightsVersionId).isEqualTo(candidateId);
    }

    /** 幂等播种题库与一道真实导入的题目（供 submitAttempt 走全链路）。 */
    private void seedSentence() {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (9101, NULL, 'FSRS 集成题库', NULL, 'PUBLIC', 'PUBLISHED', 0, ?,"
                        + " NULL, NULL, ?, ?, NULL, NULL)",
                T0,
                T0,
                T0);
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, 9101, 'JSON', 'fsrs-it.json', 'imports/fsrs-it.json', ?, 1024,"
                        + " 'COMMITTING', ?, 'admin', ?)",
                "batch-it-fsrs-0000-0000-000000000001",
                "b".repeat(64),
                ADMIN_ID,
                T0);
        List<SentenceContent> rows =
                List.of(
                        new SentenceContent(
                                "s_fsrs1",
                                "他越来越重视这件事情。",
                                REF,
                                "这是 FSRS 集成测试的解析说明，长度满足最小要求。",
                                List.of(new SentenceChunk(0, "他", List.of(REF), "代词", null)),
                                List.of(0),
                                List.of("代词"),
                                null,
                                null,
                                null));
        var imported =
                contentImportService.importSentences(
                        9101L,
                        "batch-it-fsrs-0000-0000-000000000001",
                        rows,
                        ADMIN_ID,
                        T0.toInstant());
        sentenceId = imported.getFirst().sentenceId();
        sentenceVersionId = String.valueOf(imported.getFirst().sentenceVersionId());
    }

    /**
     * 直接播种排程日志（优化器训练集）；append-only 触发器只禁 UPDATE/DELETE，INSERT 不受阻。
     *
     * @param rows 行数（≥50 才可训练）
     */
    private void seedReviewLogs(int rows) {
        jdbcTemplate.update(
                "INSERT INTO reviews (user_id, sentence_id, mode, state, fsrs_state, due_at,"
                        + " stability, difficulty, reps, lapses, elapsed_days, scheduled_days,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, 'CLOZE', 'LEARNING', 'LEARNING',"
                        + " DATE_ADD(NOW(6), INTERVAL 1 DAY), 2.4, 5.3, 1, 1, 0, 1, NOW(6), NOW(6))",
                USER_ID,
                sentenceId);
        Long reviewId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM reviews WHERE user_id = ? AND sentence_id = ?",
                        Long.class,
                        USER_ID,
                        sentenceId);
        Long weightsVersionId = seedVersionId;
        for (int i = 0; i < rows; i++) {
            jdbcTemplate.update(
                    "INSERT INTO review_logs (review_id, user_id, sentence_id, mode, attempt_id,"
                            + " rating, state_before, state_after, due_after, stability_after,"
                            + " difficulty_after, elapsed_days, scheduled_days, review_at,"
                            + " weights_version_id, created_at)"
                            + " VALUES (?, ?, ?, 'CLOZE', NULL, ?, 'REVIEW', 'REVIEW',"
                            + " DATE_ADD(NOW(6), INTERVAL 3 DAY), 3.2, 5.1, ?, 3,"
                            + " DATE_SUB(NOW(6), INTERVAL ? DAY), ?, NOW(6))",
                    reviewId,
                    USER_ID,
                    sentenceId,
                    // rating 1–4 轮转：标签混合让 BCE 有真实梯度。
                    (i % 4) + 1,
                    i % 5,
                    rows - i,
                    weightsVersionId);
        }
    }

    /** 直插一条已知权重的候选行（启用后排程参数可精确断言）。 */
    private Long insertCandidate() {
        jdbcTemplate.update(
                "INSERT INTO fsrs_parameter_versions (owner_type, user_id, algorithm, weights,"
                        + " sample_count, status, metrics, optimized_at, created_by, reason, created_at)"
                        + " VALUES ('GLOBAL', NULL, 'FSRS-6', ?, 60, 'CANDIDATE', NULL, NOW(6), ?,"
                        + " 'it-known-weights', NOW(6))",
                CANDIDATE_WEIGHTS,
                ADMIN_ID);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM fsrs_parameter_versions WHERE reason = 'it-known-weights'",
                Long.class);
    }

    /** 清空 practice 五表与内容表（摘触发器后删，再重建）。 */
    private void cleanPracticeTables() {
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

    /** 摘除 append-only 触发器。 */
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

    /** 重建 append-only 触发器（与 V8/V7 定义一致）。 */
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
}
