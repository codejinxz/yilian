package io.github.codejinxz.yilian.practice.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.practice.llm.PromptRegistry;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

/**
 * V13 迁移落库形状与 PromptRegistry 读取链路集成测试（真实 MySQL，Flyway V1→V13 自动跑）[AC-1]。
 *
 * <p>锁定：prompt_versions 表形状（列/唯一键/label CHECK）、三条首版种子（ask/review/appeal，
 * version=1、label=ACTIVE）幂等、唯一键拦截重复版本、 Dao 按 name 取 ACTIVE、注册表缓存命中与 evictAll 失效。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class V13PromptVersionsSchemaIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private PromptVersionDao promptVersionDao;

    @Autowired private PromptRegistry promptRegistry;

    static {
        TestDatabases.ensureDatabase();
    }

    /** [AC-1] 种子形状：三条首版 ACTIVE，内容非空；表名/列形状由 Flyway 应用与 ddl-auto=validate 保证。 */
    @Test
    @DisplayName("[AC-1] V13 三条首版种子 ACTIVE 且内容非空")
    void ac1_seedRowsPresentAndActive() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT name, version, label, content FROM prompt_versions"
                                + " ORDER BY name, version");
        assertThat(rows)
                .extracting(row -> row.get("name"))
                .containsExactly("appeal", "ask", "review");
        for (Map<String, Object> row : rows) {
            assertThat(row.get("version")).isEqualTo(1);
            assertThat(row.get("label")).isEqualTo("ACTIVE");
            assertThat((String) row.get("content")).isNotBlank();
        }
    }

    /** [AC-1] 唯一键 uk_prompt_name_version：同 (name, version) 重复插入被数据库拦截。 */
    @Test
    @DisplayName("[AC-1] 同名同版本唯一键拦截")
    void ac1_uniqueNameVersionEnforced() {
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO prompt_versions (name, version, content, label)"
                                                + " VALUES ('ask', 1, '重复版本', 'ACTIVE')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** [AC-1] label CHECK 约束：非法标签拒绝（MySQL 3819 经 Spring 翻译为 DataAccessException 族）。 */
    @Test
    @DisplayName("[AC-1] label CHECK 拒绝非法取值")
    void ac1_labelCheckConstraint() {
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO prompt_versions (name, version, content, label)"
                                                + " VALUES ('it-check', 1, 'x', 'DRAFT')"))
                .isInstanceOfSatisfying(
                        DataAccessException.class,
                        exception ->
                                assertThat(exception).hasStackTraceContaining("chk_prompt_label"));
        // 清理本用例可能成功写入的合法行（幂等：name 为测试专用）。
        jdbcTemplate.update("DELETE FROM prompt_versions WHERE name = 'it-check'");
    }

    /** [AC-1] Dao 与注册表：按 name 取 ACTIVE 版本；id 稳定（版本快照不可覆盖）。 */
    @Test
    @DisplayName("[AC-1] Dao 按场景名取 ACTIVE 版本")
    void ac1_daoResolvesActiveVersionByName() {
        for (String name : List.of("ask", "review", "appeal")) {
            var version = promptVersionDao.findActiveByName(name);
            assertThat(version).as("场景 %s 应有 ACTIVE 首版", name).isPresent();
            assertThat(version.get().getVersion()).isEqualTo(1);
            assertThat(version.get().getLabel().name()).isEqualTo("ACTIVE");
        }
        // 未登记场景为空（负路径）。
        assertThat(promptVersionDao.findActiveByName("nonexistent")).isEmpty();
    }

    /** [AC-1] 注册表缓存：Present 带 versionId/content；evictAll 后仍可重建（缓存可再生的红线）。 */
    @Test
    @DisplayName("[AC-1] 注册表缓存条目与整体失效")
    void ac1_registryCacheAndEvict() {
        PromptRegistry.Entry first = promptRegistry.load("ask");
        assertThat(first).isInstanceOf(PromptRegistry.Present.class);
        Long versionId = ((PromptRegistry.Present) first).versionId();
        assertThat(versionId).isNotNull();
        assertThat(((PromptRegistry.Present) first).content()).isNotBlank();

        // 第二次读取命中缓存：内容一致（缓存不改变取值）。
        PromptRegistry.Entry second = promptRegistry.load("ask");
        assertThat(((PromptRegistry.Present) second).versionId()).isEqualTo(versionId);

        // 失效后重建：仍解析到同一行（缓存可重建）。
        promptRegistry.evictAll();
        PromptRegistry.Entry rebuilt = promptRegistry.load("ask");
        assertThat(rebuilt).isInstanceOf(PromptRegistry.Present.class);
        assertThat(((PromptRegistry.Present) rebuilt).versionId()).isEqualTo(versionId);

        // 未登记场景：负条目（Absent），不抛异常——主链路据此回退兜底常量。
        assertThat(promptRegistry.load("nonexistent")).isInstanceOf(PromptRegistry.Absent.class);
    }

    /** [AC-1] 兜底同文约束：三条种子内容与代码兜底常量逐字一致（注册表缺行时主链路降级不变形）。 */
    @Test
    @DisplayName("[AC-1] 种子内容与代码兜底常量同文")
    void ac1_seedContentMatchesCodeFallbacks() {
        assertThat(contentOf("ask"))
                .isEqualTo(
                        io.github.codejinxz.yilian.practice.service.impl.TutorServiceImpl
                                .ASK_SYSTEM_PROMPT_FALLBACK);
        assertThat(contentOf("review"))
                .isEqualTo(
                        io.github.codejinxz.yilian.practice.grading.ModelReviewPromptBuilder
                                .systemPrompt());
        assertThat(contentOf("appeal"))
                .isEqualTo(
                        io.github.codejinxz.yilian.practice.grading.AppealAdjudicationPromptBuilder
                                .systemPrompt());
    }

    private String contentOf(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM prompt_versions WHERE name = ? AND version = 1",
                String.class,
                name);
    }
}
