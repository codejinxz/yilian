package io.github.codejinxz.yilian.practice.golden;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

/**
 * 黄金集资产完整性与 golden 跳过门禁证明（AC-5③，CI 常跑）。
 *
 * <p>两件事：①资产本身的结构验收（四层文件在、条数达 AC-4 下限、id 唯一、期望形状自洽）—— 黄金集是「必跑」门禁的输入，资产损坏或缩水要在此红； ②跳过逻辑的行为证明
 * ——{@link GoldenLlmSetTest} 的门禁（golden.enabled 属性）缺省关闭、显式开启才放行， 且类上必须带 {@code @Tag("golden")}（CI
 * 不跑该 tag 的结构性前提）。
 */
class GoldenAssetsIntegrityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** [AC-4] 四层资产在库且条数达下限；id 全局唯一、固定五字段齐备。 */
    @Test
    @DisplayName("[AC-4] 四层资产条数与形状完整性")
    void assetsPresentWithMinimumCountsAndUniqueIds() {
        record Spec(String filename, String layer, int min) {}
        List<Spec> specs =
                List.of(
                        new Spec(
                                GoldenCaseFiles.GRADING_FILE,
                                "grading",
                                GoldenCaseFiles.GRADING_MIN_CASES),
                        new Spec(
                                GoldenCaseFiles.REVIEW_FILE,
                                "review",
                                GoldenCaseFiles.REVIEW_MIN_CASES),
                        new Spec(
                                GoldenCaseFiles.APPEAL_FILE,
                                "appeal",
                                GoldenCaseFiles.APPEAL_MIN_CASES),
                        new Spec(GoldenCaseFiles.ASK_FILE, "ask", GoldenCaseFiles.ASK_MIN_CASES));

        Set<String> seenIds = new java.util.HashSet<>();
        for (Spec spec : specs) {
            GoldenCaseFiles.GoldenFile file =
                    GoldenCaseFiles.load(mapper, spec.filename(), spec.layer());
            assertThat(file.cases())
                    .as("%s 条数应 ≥%d", spec.filename(), spec.min())
                    .hasSizeGreaterThanOrEqualTo(spec.min());
            for (GoldenCaseFiles.GoldenCase testCase : file.cases()) {
                assertThat(testCase.id()).as("%s 用例 id 必填", spec.filename()).isNotBlank();
                assertThat(seenIds.add(testCase.id()))
                        .as("用例 id 全局唯一，重复：%s", testCase.id())
                        .isTrue();
                assertThat(testCase.layer()).isEqualTo(spec.layer());
                assertThat(testCase.description())
                        .as("%s description 必填", testCase.id())
                        .isNotBlank();
                assertThat(testCase.rationale())
                        .as("%s rationale（人工复核说明）必填", testCase.id())
                        .isNotBlank();
                assertThat(testCase.input()).as("%s input 必填", testCase.id()).isNotNull();
                assertThat(testCase.expectation())
                        .as("%s expectation 必填", testCase.id())
                        .isNotNull();
            }
        }
    }

    /** [AC-4] grading 层期望形状自洽：mode 合法、score 域正确、CLOZE 批注数=挖空数。 */
    @Test
    @DisplayName("[AC-4] grading 层期望形状自洽")
    void gradingExpectationsAreSelfConsistent() {
        GoldenCaseFiles.GoldenFile file =
                GoldenCaseFiles.load(mapper, GoldenCaseFiles.GRADING_FILE, "grading");
        for (GoldenCaseFiles.GoldenCase testCase : file.cases()) {
            assertThat(testCase.mode())
                    .as("%s mode 必须为 CLOZE/FREE/REVEAL", testCase.id())
                    .isIn("CLOZE", "FREE", "REVEAL");
            JsonNode expectation = testCase.expectation();
            JsonNode score = expectation.get("score");
            if ("REVEAL".equals(testCase.mode())) {
                assertThat(score.isNull()).as("%s REVEAL 的 score 应为 null", testCase.id()).isTrue();
            } else {
                assertThat(score.isNull()).as("%s score 必填", testCase.id()).isFalse();
                assertThat(score.intValue())
                        .as("%s score 应在 0-100", testCase.id())
                        .isBetween(0, 100);
            }
            assertThat(expectation.get("summary").stringValue())
                    .as("%s summary 必填", testCase.id())
                    .isNotBlank();
            JsonNode annotations = expectation.get("annotations");
            assertThat(annotations.isArray()).as("%s annotations 必为数组", testCase.id()).isTrue();
            if ("CLOZE".equals(testCase.mode())) {
                int blankSize = testCase.input().get("blank").size();
                assertThat(annotations.size())
                        .as("%s CLOZE 批注数应等于挖空数", testCase.id())
                        .isEqualTo(blankSize);
            } else if ("FREE".equals(testCase.mode())) {
                assertThat(annotations.size())
                        .as("%s FREE 批注数应为 0 或 1", testCase.id())
                        .isLessThanOrEqualTo(1);
            }
        }
    }

    /** [AC-4] review/appeal 层期望形状自洽：direction 边界合法、verdicts 在枚举内、reasonMaxChars 与实现常量一致。 */
    @Test
    @DisplayName("[AC-4] review/appeal 层期望形状自洽")
    void llmLayerExpectationsAreSelfConsistent() {
        GoldenCaseFiles.GoldenFile review =
                GoldenCaseFiles.load(mapper, GoldenCaseFiles.REVIEW_FILE, "review");
        for (GoldenCaseFiles.GoldenCase testCase : review.cases()) {
            JsonNode expectation = testCase.expectation();
            assertThat(expectation.get("requiredKeys").toString())
                    .as("%s requiredKeys 应含 score/reason", testCase.id())
                    .contains("score")
                    .contains("reason");
            String direction = expectation.path("direction").asText(null);
            if (direction != null) {
                assertThat(direction).as("%s direction 合法值", testCase.id()).isIn("high", "low");
            }
            JsonNode min = expectation.get("minScore");
            JsonNode max = expectation.get("maxScore");
            if (min != null && !min.isNull()) {
                assertThat(min.intValue()).as("%s minScore ≥0", testCase.id()).isBetween(0, 100);
            }
            if (max != null && !max.isNull()) {
                assertThat(max.intValue()).as("%s maxScore ≤100", testCase.id()).isBetween(0, 100);
            }
            if (min != null && !min.isNull() && max != null && !max.isNull()) {
                assertThat(min.intValue())
                        .as("%s minScore 应 ≤ maxScore", testCase.id())
                        .isLessThanOrEqualTo(max.intValue());
            }
            assertThat(expectation.get("reasonMaxChars").intValue())
                    .as("%s reasonMaxChars 与实现常量一致", testCase.id())
                    .isEqualTo(200);
        }

        GoldenCaseFiles.GoldenFile appeal =
                GoldenCaseFiles.load(mapper, GoldenCaseFiles.APPEAL_FILE, "appeal");
        for (GoldenCaseFiles.GoldenCase testCase : appeal.cases()) {
            JsonNode expectation = testCase.expectation();
            assertThat(expectation.get("requiredKeys").toString())
                    .as("%s requiredKeys 应含 verdict/reason", testCase.id())
                    .contains("verdict")
                    .contains("reason");
            for (JsonNode verdict : expectation.get("verdicts")) {
                assertThat(verdict.stringValue())
                        .as("%s verdict 必须在契约枚举内", testCase.id())
                        .isIn("SUPPORT", "OPPOSE", "UNCERTAIN");
            }
            assertThat(expectation.get("reasonMaxChars").intValue())
                    .as("%s reasonMaxChars 与实现常量一致", testCase.id())
                    .isEqualTo(300);
        }
    }

    /** [AC-4] ask 层期望形状自洽：exclusions 非空（参考译文整句红线）、noScoreHint 恒开。 */
    @Test
    @DisplayName("[AC-4] ask 层期望形状自洽")
    void askExpectationsAreSelfConsistent() {
        GoldenCaseFiles.GoldenFile ask =
                GoldenCaseFiles.load(mapper, GoldenCaseFiles.ASK_FILE, "ask");
        for (GoldenCaseFiles.GoldenCase testCase : ask.cases()) {
            JsonNode expectation = testCase.expectation();
            assertThat(expectation.get("exclusions").isArray())
                    .as("%s exclusions 必为数组", testCase.id())
                    .isTrue();
            assertThat(expectation.get("exclusions").size())
                    .as("%s 必须排除参考译文整句", testCase.id())
                    .isGreaterThanOrEqualTo(1);
            assertThat(expectation.get("noScoreHint").booleanValue())
                    .as("%s noScoreHint 恒开", testCase.id())
                    .isTrue();
        }
    }

    /** [AC-5] 跳过门禁证明：golden.enabled 缺省关闭（CI 不跑 LLM 层的前提）。 */
    @Test
    @DisplayName("[AC-5] golden.enabled 缺省关闭")
    void goldenGateIsDisabledByDefault() {
        assertThat(System.getProperty("golden.enabled"))
                .as("CI/常规构建不设 golden.enabled，LLM 层必须默认跳过")
                .isNull();
        assertThat(GoldenLlmSetTest.isGoldenEnabled()).as("门禁方法在缺省属性下应返回 false").isFalse();
    }

    /** [AC-5] 跳过门禁证明：显式 -Dgolden.enabled=true 时门禁放行（用后复位，不污染同 JVM 其他用例）。 */
    @Test
    @DisplayName("[AC-5] golden.enabled=true 放行门禁")
    void goldenGateOpensWithExplicitProperty() {
        System.setProperty("golden.enabled", "true");
        try {
            assertThat(GoldenLlmSetTest.isGoldenEnabled()).isTrue();
        } finally {
            System.clearProperty("golden.enabled");
        }
        assertThat(GoldenLlmSetTest.isGoldenEnabled()).as("复位后门禁回到关闭").isFalse();
    }

    /** [AC-5] 跳过门禁证明：GoldenLlmSetTest 必须带 @Tag("golden")（CI 排除该 tag 的结构性前提）。 */
    @Test
    @DisplayName("[AC-5] GoldenLlmSetTest 带 @Tag(golden)")
    void goldenLlmSetTestCarriesGoldenTag() {
        assertThat(GoldenLlmSetTest.class.getAnnotation(Tag.class))
                .as("LLM 层必须标 @Tag(\"golden\") 供 CI 排除")
                .isNotNull();
        assertThat(GoldenLlmSetTest.class.getAnnotation(Tag.class).value()).isEqualTo("golden");
    }
}
