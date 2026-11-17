package io.github.codejinxz.yilian.practice.golden;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.practice.grading.Grader;
import io.github.codejinxz.yilian.practice.grading.GradingAnnotation;
import io.github.codejinxz.yilian.practice.grading.GradingResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.Stream;

/**
 * 黄金集 grading 层回归（AC-5①）：确定性本地判分对拍主链（{@link Grader} + norm-1）， CI 常跑。
 *
 * <p>期望值语义（AC-4 预披露）：score/hitCount/totalCount/summary 与逐槽批注（key/tag/correct/
 * message/minimalFix）逐字段精确对拍——判分是纯函数，锁定精确值才有回归价值； 每条期望值都在资产 rationale 字段中带人工推导依据，不抄实现输出。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenGradingSetTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<GoldenCaseFiles.GoldenCase> cases() {
        return GoldenCaseFiles.load(mapper, GoldenCaseFiles.GRADING_FILE, "grading").cases();
    }

    /** 参数源：每条用例一个具名参数（失败输出直接定位到 id）。 */
    Stream<Named<GoldenCaseFiles.GoldenCase>> gradingCases() {
        return cases().stream()
                .map(testCase -> Named.of(testCase.id() + " " + testCase.description(), testCase));
    }

    /**
     * [AC-5] grading 层逐条对拍：按 mode 分派到 gradeCloze/gradeFree/gradeForReveal， 期望值逐字段精确断言。
     *
     * @param testCase 黄金集用例
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("gradingCases")
    @DisplayName("[AC-5] grading 层黄金集对拍判分主链")
    void gradingGoldenSetMatchesMainChain(GoldenCaseFiles.GoldenCase testCase) {
        GradingResult actual = grade(testCase);
        GradingExpectation expected =
                mapper.convertValue(testCase.expectation(), GradingExpectation.class);

        assertThat(actual.score()).as("[%s] score", testCase.id()).isEqualTo(expected.score());
        assertThat(actual.hitCount())
                .as("[%s] hitCount", testCase.id())
                .isEqualTo(expected.hitCount());
        assertThat(actual.totalCount())
                .as("[%s] totalCount", testCase.id())
                .isEqualTo(expected.totalCount());
        assertThat(actual.summary())
                .as("[%s] summary", testCase.id())
                .isEqualTo(expected.summary());

        List<GradingExpectation.Annotation> expectedAnnotations =
                expected.annotations() == null ? List.of() : expected.annotations();
        assertThat(actual.annotations())
                .as("[%s] annotations 数量", testCase.id())
                .hasSameSizeAs(expectedAnnotations);
        for (int i = 0; i < expectedAnnotations.size(); i++) {
            GradingAnnotation actualAnnotation = actual.annotations().get(i);
            GradingExpectation.Annotation expectedAnnotation = expectedAnnotations.get(i);
            assertThat(actualAnnotation.key())
                    .as("[%s] annotations[%d].key", testCase.id(), i)
                    .isEqualTo(expectedAnnotation.key());
            assertThat(actualAnnotation.chunkIndex())
                    .as("[%s] annotations[%d].chunkIndex", testCase.id(), i)
                    .isEqualTo(expectedAnnotation.chunkIndex());
            assertThat(actualAnnotation.tag())
                    .as("[%s] annotations[%d].tag", testCase.id(), i)
                    .isEqualTo(expectedAnnotation.tag());
            assertThat(actualAnnotation.correct())
                    .as("[%s] annotations[%d].correct", testCase.id(), i)
                    .isEqualTo(expectedAnnotation.correct());
            assertThat(actualAnnotation.message())
                    .as("[%s] annotations[%d].message", testCase.id(), i)
                    .isEqualTo(expectedAnnotation.message());
            assertThat(actualAnnotation.minimalFix())
                    .as("[%s] annotations[%d].minimalFix", testCase.id(), i)
                    .isEqualTo(expectedAnnotation.minimalFix());
        }
    }

    /**
     * 按用例 mode 调判分主链。
     *
     * @param testCase 黄金集用例
     * @return 判分结果
     */
    private GradingResult grade(GoldenCaseFiles.GoldenCase testCase) {
        GradingInput input = mapper.convertValue(testCase.input(), GradingInput.class);
        return switch (testCase.mode()) {
            case "CLOZE" -> Grader.gradeCloze(input.chunks(), input.blank(), input.answers());
            case "FREE" -> Grader.gradeFree(input.ref(), input.text());
            case "REVEAL" -> Grader.gradeForReveal(input.chunks(), input.blank());
            default ->
                    throw new IllegalStateException(
                            "未知判分 mode：" + testCase.mode() + "（用例 " + testCase.id() + "）");
        };
    }

    /** grading 输入形状：CLOZE 用 chunks/blank/answers，FREE 用 ref/text，REVEAL 用 chunks/blank。 */
    record GradingInput(
            List<SentenceChunk> chunks,
            List<Integer> blank,
            List<String> answers,
            String ref,
            String text) {}

    /** grading 期望形状：分数与逐槽批注（精确对拍）。 */
    record GradingExpectation(
            Integer score,
            Integer hitCount,
            Integer totalCount,
            String summary,
            List<Annotation> annotations) {

        /** 单条批注期望。 */
        record Annotation(
                String key,
                Integer chunkIndex,
                String tag,
                List<String> expected,
                Boolean correct,
                String message,
                String minimalFix) {}
    }
}
