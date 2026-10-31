package io.github.codejinxz.yilian.practice.grading;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 判分器 golden 测试：样例与前端 {@code grading.ts} 的 {@code gradeCloze}/{@code gradeFree} 及 {@code engine.ts}
 * 的 {@code revealGrading} 输出逐值一致（含分数、批注与摘要文案）。
 */
class GraderTest {

    /** 语块样例：与 API §6.2 示例同构（两个挖空，各有两个可接受译法）。 */
    private static final List<SentenceChunk> CHUNKS =
            List.of(
                    new SentenceChunk(
                            1, "越来越", List.of("more and more", "increasingly"), "副词结构", null),
                    new SentenceChunk(
                            2,
                            "重视",
                            List.of("pay attention to", "attach importance to"),
                            "搭配",
                            null));

    private static final List<Integer> BLANK = List.of(1, 2);

    /** [AC-2] 部分命中：整块命中 + 搭配错词，score=83、还差 1 处、minimalFix 给规范答案。 */
    @Test
    @DisplayName("gradeCloze 部分命中：score=83，批注 slot:2 搭配错误")
    void gradeClozePartialHit() {
        GradingResult result =
                Grader.gradeCloze(CHUNKS, BLANK, List.of("more and more", "pay attentions to"));

        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.score()).isEqualTo(83);
        assertThat(result.maxScore()).isEqualTo(100);
        assertThat(result.hitCount()).isEqualTo(5);
        assertThat(result.totalCount()).isEqualTo(6);
        assertThat(result.summary()).isEqualTo("还差 1 处");

        GradingAnnotation hit = result.annotations().get(0);
        assertThat(hit.key()).isEqualTo("slot:1");
        assertThat(hit.tag()).isEqualTo("副词结构");
        assertThat(hit.correct()).isTrue();
        assertThat(hit.message()).isNull();
        assertThat(hit.minimalFix()).isNull();

        GradingAnnotation miss = result.annotations().get(1);
        assertThat(miss.key()).isEqualTo("slot:2");
        assertThat(miss.tag()).isEqualTo("搭配");
        assertThat(miss.expected()).containsExactly("pay attention to", "attach importance to");
        assertThat(miss.received()).isEqualTo("pay attentions to");
        assertThat(miss.correct()).isFalse();
        assertThat(miss.message()).isEqualTo("搭配错误");
        assertThat(miss.minimalFix()).isEqualTo("pay attention to");
    }

    /** [AC-2] 全对：全角与句末标点经 norm-1 后仍整块命中（引号包裹不参与命中，与前端一致）。 */
    @Test
    @DisplayName("gradeCloze 全对：归一化后命中，score=100 全部命中")
    void gradeClozeFullHitAfterNormalization() {
        GradingResult result =
                Grader.gradeCloze(
                        CHUNKS, BLANK, List.of("Ｍore and more！", "attach importance to。"));

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.hitCount()).isEqualTo(6);
        assertThat(result.totalCount()).isEqualTo(6);
        assertThat(result.summary()).isEqualTo("全部命中");
        assertThat(result.annotations())
                .allSatisfy(
                        annotation -> {
                            assertThat(annotation.correct()).isTrue();
                            assertThat(annotation.message()).isNull();
                            assertThat(annotation.minimalFix()).isNull();
                        });
    }

    /** 未全对 + 空槽：逐词位置比对计部分分，空槽批注「未填写」。 */
    @Test
    @DisplayName("gradeCloze 空槽与错词：score=33，未填写批注")
    void gradeClozeEmptySlotAndWrongWord() {
        GradingResult result = Grader.gradeCloze(CHUNKS, BLANK, List.of("more and less", ""));

        assertThat(result.score()).isEqualTo(33);
        assertThat(result.hitCount()).isEqualTo(2);
        assertThat(result.totalCount()).isEqualTo(6);
        assertThat(result.annotations().get(0).correct()).isFalse();
        assertThat(result.annotations().get(0).message()).isEqualTo("副词结构错误");
        assertThat(result.annotations().get(1).message()).isEqualTo("未填写");
        assertThat(result.summary()).isEqualTo("还差 2 处");
    }

    /** 空答案：blank 为空时不产生批注、分数为 0。 */
    @Test
    @DisplayName("gradeCloze 空挖空：score=0，没有需要判定的语块")
    void gradeClozeNoBlank() {
        GradingResult result = Grader.gradeCloze(CHUNKS, List.of(), List.of());

        assertThat(result.score()).isZero();
        assertThat(result.hitCount()).isZero();
        assertThat(result.totalCount()).isZero();
        assertThat(result.annotations()).isEmpty();
        assertThat(result.summary()).isEqualTo("没有需要判定的语块");
    }

    /** 语块缺失：批注「语块不存在」，不参与计分。 */
    @Test
    @DisplayName("gradeCloze 语块缺失：未分类 + 语块不存在")
    void gradeClozeMissingChunk() {
        GradingResult result = Grader.gradeCloze(CHUNKS, List.of(9), List.of("anything"));

        assertThat(result.score()).isZero();
        assertThat(result.totalCount()).isZero();
        assertThat(result.annotations()).hasSize(1);
        assertThat(result.annotations().get(0).tag()).isEqualTo("未分类");
        assertThat(result.annotations().get(0).message()).isEqualTo("语块不存在");
        assertThat(result.annotations().get(0).minimalFix()).isNull();
    }

    /** [AC-2] 整句完全一致：score=100，无批注。 */
    @Test
    @DisplayName("gradeFree 完全一致：score=100 全部命中")
    void gradeFreeExactMatch() {
        GradingResult result =
                Grader.gradeFree(
                        "He is paying more and more attention.",
                        "He is paying more and more attention.");

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.hitCount()).isEqualTo(7);
        assertThat(result.totalCount()).isEqualTo(7);
        assertThat(result.annotations()).isEmpty();
        assertThat(result.summary()).isEqualTo("全部命中");
    }

    /** [AC-2] 整句部分覆盖：多重集覆盖率 4/7 → 57，批注 free:0。 */
    @Test
    @DisplayName("gradeFree 部分覆盖：score=57，要点覆盖 57%")
    void gradeFreePartialCoverage() {
        GradingResult result =
                Grader.gradeFree(
                        "He is paying more and more attention.",
                        "He is paying increasingly attention.");

        assertThat(result.score()).isEqualTo(57);
        assertThat(result.hitCount()).isEqualTo(4);
        assertThat(result.totalCount()).isEqualTo(7);
        assertThat(result.summary()).isEqualTo("要点覆盖 57%");
        assertThat(result.annotations()).hasSize(1);
        GradingAnnotation annotation = result.annotations().get(0);
        assertThat(annotation.key()).isEqualTo("free:0");
        assertThat(annotation.tag()).isEqualTo("整句");
        assertThat(annotation.expected()).containsExactly("He is paying more and more attention.");
        assertThat(annotation.received()).isEqualTo("He is paying increasingly attention.");
        assertThat(annotation.message()).isEqualTo("与参考译文有差异");
        assertThat(annotation.minimalFix()).isEqualTo("He is paying more and more attention.");
    }

    /** [AC-2] 空答案：FREE 不作答给 0 分、无批注、摘要「未作答」。 */
    @Test
    @DisplayName("gradeFree 空答案：score=0 未作答")
    void gradeFreeEmptyAnswer() {
        GradingResult result = Grader.gradeFree("He is paying more and more attention.", "");

        assertThat(result.score()).isZero();
        assertThat(result.hitCount()).isZero();
        assertThat(result.annotations()).isEmpty();
        assertThat(result.summary()).isEqualTo("未作答");
    }

    /** 重复词的多重集口径：received 两个 more 只消耗 expected 中的两个 more，不会重复计数。 */
    @Test
    @DisplayName("gradeFree 多重集口径：重复词不超量命中")
    void gradeFreeMultisetSemantics() {
        GradingResult result = Grader.gradeFree("more and more", "more more more");

        assertThat(result.hitCount()).isEqualTo(2);
        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(result.score()).isEqualTo(67);
    }

    /** [AC-2] 看答案：不计分（score=null），totalCount 按规范答案词数占位。 */
    @Test
    @DisplayName("gradeForReveal：score=null，占位 totalCount=6")
    void gradeForRevealPlaceholder() {
        GradingResult result = Grader.gradeForReveal(CHUNKS, BLANK);

        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.score()).isNull();
        assertThat(result.maxScore()).isEqualTo(100);
        assertThat(result.hitCount()).isZero();
        assertThat(result.totalCount()).isEqualTo(6);
        assertThat(result.annotations()).isEmpty();
        assertThat(result.summary()).isEqualTo("已看答案，记为学习中");
    }
}
