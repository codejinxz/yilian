package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.practice.grading.AppealAdjudicationPromptBuilder;
import io.github.codejinxz.yilian.practice.grading.ModelReviewPromptBuilder;
import io.github.codejinxz.yilian.practice.llm.PromptRegistry;
import io.github.codejinxz.yilian.practice.service.PromptAdminService;
import io.github.codejinxz.yilian.practice.service.vo.PromptVersionView;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

/**
 * Prompt 注册表写路径与激活换轨集成测试（真实 MySQL）[AC-1]/[AC-2]。
 *
 * <p>AC-1：登记分配「现存最大 +1」且 label=CANDIDATE（不自动激活）；校验拒绝（name 白名单、content 空白/超长）零落库；列表检索过滤与 011
 * 分页口径；激活的 40400（不存在）口径。 AC-2：激活后注册表（真实 PromptRegistryImpl + Caffeine L1）即时取新版本（prompt_version_id
 * 换轨的语义基础）；evictAll 接线后重复读不回旧值；旧 ACTIVE 行降 CANDIDATE（重启旧版本=回滚语义是合法治理动作）；对已 ACTIVE 行重复激活幂等返回。
 *
 * <p>真实注册表（无桩）：走 CacheConfig 的 Caffeine 缓存全链路——「激活前缓存旧值、激活后换新值」 只有在 evictAll 真正接线时才成立，桩注册表测不出这条。
 * 本类独立 Spring 上下文（properties 单独成键），缓存从空开始。
 *
 * <p>跨套件纪律：V13 schema 套件断言 prompt_versions 恰含三条种子行——本类每个用例前后都把表恢复为 V13 种子状态（DELETE 全表 + 按三个兜底常量逐字回插
 * v1 ACTIVE；常量与 V13 种子同文由 V13 套件的 同文断言保证），避免删种子行让后跑的套件看到空表。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.health.redis.enabled=false"})
@ActiveProfiles("test")
class PromptRegistryWritePathIntegrationTest {

    @Autowired private PromptAdminService promptAdminService;

    @Autowired private PromptRegistry promptRegistry;

    @Autowired private JdbcTemplate jdbcTemplate;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 用例前把表恢复为 V13 种子状态（跨套件纪律见类注释）。 */
    @BeforeEach
    void resetToSeedState() {
        restoreSeedRows();
    }

    /** 用例后把表恢复为 V13 种子状态（后跑的套件如 V13 schema 断言种子行恰在）。 */
    @AfterEach
    void restoreSeedAfter() {
        restoreSeedRows();
    }

    /** [AC-1] 登记：版本号=现存最大+1、label=CANDIDATE、行内容与请求逐字一致；同 name 连续登记版本号递增。 */
    @Test
    @DisplayName("[AC-1] 登记分配递增版本号且为 CANDIDATE")
    void ac1_registerAssignsIncrementingVersionAndCandidateLabel() {
        // 种子 v1 在库（V13 恢复）：首条登记应取 v2。
        PromptVersionView first = promptAdminService.register("review", "复核提示词 v2 正文", 1L);
        assertThat(first.name()).isEqualTo("review");
        assertThat(first.version()).isEqualTo(2);
        assertThat(first.label()).isEqualTo("CANDIDATE");
        assertThat(first.content()).isEqualTo("复核提示词 v2 正文");
        assertThat(first.createdAt()).isNotNull();

        PromptVersionView second = promptAdminService.register("review", "复核提示词 v3 正文", 1L);
        assertThat(second.version()).isEqualTo(3);
        assertThat(second.id()).isNotEqualTo(first.id());

        // 不同场景独立计数：ask 种子 v1 在库 → 首条登记为 v2。
        PromptVersionView askFirst = promptAdminService.register("ask", "答疑提示词 v2 正文", 1L);
        assertThat(askFirst.version()).isEqualTo(2);
    }

    /** [AC-1] 登记校验：name 白名单、content 空白、content 超长（码点口径）按 40000 拒绝且零落库。 */
    @Test
    @DisplayName("[AC-1] 登记校验拒绝且零落库")
    void ac1_registerValidationRejects() {
        assertThatThrownBy(() -> promptAdminService.register("cloze", "任务路由模板（不在白名单）", 1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.apiCode()).isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> promptAdminService.register("review", "   ", 1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.apiCode()).isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> promptAdminService.register("review", "字".repeat(20001), 1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.apiCode()).isEqualTo(ApiCode.VALIDATION_FAILED));
        Integer rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM prompt_versions WHERE name = 'review'",
                        Integer.class);
        assertThat(rows).as("校验失败不落库（仅存种子 v1）").isEqualTo(1);
    }

    /**
     * [AC-2] 激活换轨：缓存旧值 → 登记新版本 → 缓存仍旧值 → 激活 → 注册表即时取新版本、重复读不回旧值；旧 ACTIVE 行降 CANDIDATE；重启旧版本可再换回。
     */
    @Test
    @DisplayName("[AC-2] 激活后注册表即时换轨且旧值不再可见")
    void ac2_activationSwapsRegistryEntryAndDemotesPreviousActive() {
        // 旧值即种子 v1（读端真相：内容与兜底常量同文，V13 套件锁定）。
        long v1Id = seedRowId("review");
        String v1Content = ModelReviewPromptBuilder.systemPrompt();
        PromptRegistry.Entry beforeRegister = promptRegistry.load("review");
        assertThat(beforeRegister)
                .as("激活前注册表取 ACTIVE v1")
                .isInstanceOfSatisfying(
                        PromptRegistry.Present.class,
                        present -> {
                            assertThat(present.content()).isEqualTo(v1Content);
                            assertThat(present.versionId()).isEqualTo(v1Id);
                        });

        PromptVersionView candidate = promptAdminService.register("review", "复核提示词 v2 正文", 1L);
        // 登记不自动激活：注册表仍取 v1（CANDIDATE 对注册表不可见，缓存与库一致）。
        PromptRegistry.Entry afterRegister = promptRegistry.load("review");
        assertThat(afterRegister)
                .isInstanceOfSatisfying(
                        PromptRegistry.Present.class,
                        present -> assertThat(present.versionId()).isEqualTo(v1Id));

        PromptVersionView activated = promptAdminService.activate(candidate.id(), 1L);
        assertThat(activated.label()).isEqualTo("ACTIVE");
        assertThat(activated.version()).isEqualTo(2);

        // 激活即时换轨：注册表取新版本（prompt_version_id 换轨的语义基础），evictAll 接线后重复读不回旧值。
        for (int read = 0; read < 3; read++) {
            PromptRegistry.Entry entry = promptRegistry.load("review");
            assertThat(entry)
                    .as("第 %d 次读取应取 v2", read + 1)
                    .isInstanceOfSatisfying(
                            PromptRegistry.Present.class,
                            present -> {
                                assertThat(present.versionId()).isEqualTo(candidate.id());
                                assertThat(present.content()).isEqualTo("复核提示词 v2 正文");
                            });
        }

        // 行真相：v1 降 CANDIDATE（可再激活的合法治理语义）、v2 是唯一 ACTIVE。
        assertThat(loadPromptRow(v1Id).get("label")).isEqualTo("CANDIDATE");
        assertThat(loadPromptRow(candidate.id()).get("label")).isEqualTo("ACTIVE");

        // 再激活旧版本（历史版本重启=回滚语义）：注册表回 v1，v2 降级。
        promptAdminService.activate(v1Id, 1L);
        PromptRegistry.Entry rolledBack = promptRegistry.load("review");
        assertThat(rolledBack)
                .isInstanceOfSatisfying(
                        PromptRegistry.Present.class,
                        present -> {
                            assertThat(present.versionId()).isEqualTo(v1Id);
                            assertThat(present.content()).isEqualTo(v1Content);
                        });
        assertThat(loadPromptRow(candidate.id()).get("label")).isEqualTo("CANDIDATE");
    }

    /** [AC-2] 激活幂等与错误口径：CANDIDATE 激活后重复激活幂等返回现值；不存在 40400。 */
    @Test
    @DisplayName("[AC-2] 重复激活幂等、不存在 40400")
    void ac2_repeatedActivationIdempotentAndMissingRow40400() {
        PromptVersionView candidate = promptAdminService.register("appeal", "初裁提示词 v2 正文", 1L);
        PromptVersionView first = promptAdminService.activate(candidate.id(), 1L);
        assertThat(first.label()).isEqualTo("ACTIVE");
        assertThat(first.id()).isEqualTo(candidate.id());
        // 对 ACTIVE 行重复激活（双击/重试）：幂等返回现值，不重复降级也不改写历史。
        PromptVersionView second = promptAdminService.activate(candidate.id(), 1L);
        assertThat(second.label()).isEqualTo("ACTIVE");
        assertThat(second.id()).isEqualTo(candidate.id());

        assertThatThrownBy(() -> promptAdminService.activate(4_000_000L, 1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.apiCode()).isEqualTo(ApiCode.NOT_FOUND));
    }

    /** [AC-1] 列表检索：name/label 过滤、version 倒序、011 分页口径（size 越界归 100、非法分页 40000）。 */
    @Test
    @DisplayName("[AC-1] 列表检索过滤与分页口径")
    void ac1_listFiltersAndPagination() {
        promptAdminService.register("review", "v2 正文", 1L);
        promptAdminService.register("review", "v3 正文", 1L);

        PageResponse<PromptVersionView> reviewAll = promptAdminService.list("review", null, 1, 20);
        assertThat(reviewAll.total()).isEqualTo(3);
        assertThat(reviewAll.items())
                .as("同场景 version 倒序（种子 v1 + 登记 v2/v3）")
                .extracting(PromptVersionView::version)
                .containsExactly(3, 2, 1);

        PageResponse<PromptVersionView> candidates =
                promptAdminService.list("review", "CANDIDATE", 1, 20);
        assertThat(candidates.total()).isEqualTo(2);

        PageResponse<PromptVersionView> askOnly = promptAdminService.list("ask", "ACTIVE", 1, 20);
        assertThat(askOnly.total()).isEqualTo(1);
        assertThat(askOnly.items().get(0).name()).isEqualTo("ask");

        // 分页口径：越界归 100（size 字段=页大小上限；items=种子 3 行 + 登记 2 行=5 行全量）、非法页码 40000、非法 label 40000。
        PageResponse<PromptVersionView> oversized = promptAdminService.list(null, null, 1, 500);
        assertThat(oversized.size()).isEqualTo(100);
        assertThat(oversized.items()).hasSize(5);
        assertThatThrownBy(() -> promptAdminService.list(null, null, 0, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.apiCode()).isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> promptAdminService.list(null, "RETIRED", 1, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.apiCode()).isEqualTo(ApiCode.VALIDATION_FAILED));
    }

    /**
     * 把 prompt_versions 恢复为 V13 种子状态：清全表后按三个兜底常量逐字回插 v1 ACTIVE （常量与 V13 种子同文由 V13 schema
     * 套件的同文断言保证）。
     */
    private void restoreSeedRows() {
        jdbcTemplate.update("DELETE FROM prompt_versions");
        insertSeedRow("ask", TutorServiceImpl.ASK_SYSTEM_PROMPT_FALLBACK);
        insertSeedRow("review", ModelReviewPromptBuilder.systemPrompt());
        insertSeedRow("appeal", AppealAdjudicationPromptBuilder.systemPrompt());
    }

    /**
     * 回插一条种子行。
     *
     * @param name 场景名
     * @param content 系统提示全文（与 V13 种子同文）
     */
    private void insertSeedRow(String name, String content) {
        jdbcTemplate.update(
                "INSERT INTO prompt_versions (name, version, content, label) VALUES (?, 1, ?,"
                        + " 'ACTIVE')",
                name,
                content);
    }

    /**
     * 按场景名取种子行 id（version=1）。
     *
     * @param name 场景名
     * @return 种子行 id
     */
    private long seedRowId(String name) {
        List<Long> ids =
                jdbcTemplate.queryForList(
                        "SELECT id FROM prompt_versions WHERE name = ? AND version = 1",
                        Long.class,
                        name);
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    /**
     * 按 id 读版本行（行真相断言用）。
     *
     * @param id 版本行 id
     * @return 行数据
     */
    private Map<String, Object> loadPromptRow(long id) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT id, name, version, label, content FROM prompt_versions WHERE id = ?",
                        id);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
