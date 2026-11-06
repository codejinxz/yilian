package io.github.codejinxz.yilian.practice.grading;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;
import io.github.codejinxz.yilian.practice.grading.AppealAdjudicationPromptBuilder.AppealAdjudicationOutput;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * {@link AppealAdjudicationPromptBuilder} 单测：提示词上下文（题目中文/语块参考答案/被申诉批注/申诉主张，
 * 不喂整句参考译文与其他用户数据）、用户内容标记剥离（防定界伪造）、strict JSON 解析（非法 JSON/非法 verdict/缺字段/超长理由/代码围栏容错）。
 */
class AppealAdjudicationPromptBuilderTest {

    private static final String ZH = "他越来越重视这件事情。";

    private final ObjectMapper mapper = new ObjectMapper();

    /** 提示词含题目中文、语块参考答案、被申诉批注与申诉主张；不含整句参考译文（口径区别于复核）。 */
    @Test
    @DisplayName("提示词含原句/语块答案/被申诉批注/申诉主张且不含整句参考译文")
    void promptContainsAppealContextWithoutFullRef() {
        String prompt =
                AppealAdjudicationPromptBuilder.userPrompt(
                        detail(), result(), annotation(), "attach importance to", "这个搭配更书面");

        assertThat(prompt)
                .contains("【中文原句】他越来越重视这件事情。")
                .contains("语块0「他」参考写法：He / He is")
                .contains("【被申诉批注】")
                .contains("语块[搭配] 用户输入「attach importance on」")
                .contains("【申诉主张】")
                .contains("认为可接受的答案：attach importance to")
                .contains("申诉理由：这个搭配更书面")
                // 初裁围绕被申诉批注，不喂整句参考译文（与复核链路的口径差异）。
                .doesNotContain("【参考译文】");
    }

    /** 系统提示锁定三值 verdict 的 strict JSON 契约与「AI 意见不改判分」的边界。 */
    @Test
    @DisplayName("系统提示声明 strict JSON 契约与 AI 意见边界")
    void systemPromptLocksJsonContract() {
        String system = AppealAdjudicationPromptBuilder.systemPrompt();

        assertThat(system)
                .contains("SUPPORT")
                .contains("OPPOSE")
                .contains("UNCERTAIN")
                .contains("300 字")
                .contains("只输出一个 JSON 对象")
                .contains("不直接改判分");
    }

    /** 合法输出解析：verdict 校验、理由提取。 */
    @Test
    @DisplayName("解析合法 verdict 与理由")
    void parsesValidOutput() {
        Optional<AppealAdjudicationOutput> parsed =
                AppealAdjudicationPromptBuilder.parse(
                        mapper, "{\"verdict\": \"SUPPORT\", \"reason\": \"语义等价\"}");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().verdict()).isEqualTo("SUPPORT");
        assertThat(parsed.get().reason()).isEqualTo("语义等价");
    }

    /** 契约外输入统一拒绝：非 JSON、非法 verdict、缺字段、空理由、verdict 大小写归一后仍非法。 */
    @Test
    @DisplayName("非法输出解析为空（重试由调用方处理）")
    void rejectsContractViolations() {
        assertThat(AppealAdjudicationPromptBuilder.parse(mapper, "这不是 JSON")).isEmpty();
        assertThat(
                        AppealAdjudicationPromptBuilder.parse(
                                mapper, "{\"verdict\": \"MAYBE\", \"reason\": \"骑墙\"}"))
                .isEmpty();
        assertThat(AppealAdjudicationPromptBuilder.parse(mapper, "{\"verdict\": \"SUPPORT\"}"))
                .isEmpty();
        assertThat(
                        AppealAdjudicationPromptBuilder.parse(
                                mapper, "{\"verdict\": \"SUPPORT\", \"reason\": \"  \"}"))
                .isEmpty();
        assertThat(AppealAdjudicationPromptBuilder.parse(mapper, "")).isEmpty();
    }

    /** 供应商把 JSON 包进代码围栏时剥离容错；verdict 小写归一为大写枚举。 */
    @Test
    @DisplayName("代码围栏容错与 verdict 小写归一")
    void stripsFencesAndNormalizesCase() {
        Optional<AppealAdjudicationOutput> parsed =
                AppealAdjudicationPromptBuilder.parse(
                        mapper, "```json\n{\"verdict\": \"oppose\", \"reason\": \"语义偏离\"}\n```");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().verdict()).isEqualTo("OPPOSE");
    }

    /** 理由按码点截断到 300 字（decision_reason 列上限内），且不在代理对中间切开。 */
    @Test
    @DisplayName("理由截断到 300 字")
    void truncatesReasonToLimit() {
        String longReason = "解".repeat(400);
        Optional<AppealAdjudicationOutput> parsed =
                AppealAdjudicationPromptBuilder.parse(
                        mapper, "{\"verdict\": \"SUPPORT\", \"reason\": \"" + longReason + "\"}");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().reason())
                .hasSize(AppealAdjudicationPromptBuilder.REASON_MAX_CHARS)
                .startsWith("解解解");
    }

    /** 题目已删（detail=null）与主张缺省时的降级形状：占位文案而不是 NPE。 */
    @Test
    @DisplayName("题目缺失与主张缺省按占位降级")
    void degradesGracefullyOnMissingContext() {
        String prompt = AppealAdjudicationPromptBuilder.userPrompt(null, null, null, null, null);

        assertThat(prompt)
                .contains("【中文原句】（未取得）")
                .contains("（无语块结构）")
                .contains("（未取得本地批注详情）")
                .contains("认为可接受的答案：（未提供）")
                .contains("申诉理由：（未提供）");
    }

    // ---------- 夹具 ----------

    private static SentenceDetail detail() {
        return new SentenceDetail(
                "01HXXXXXXSENTENCE0",
                "1",
                1,
                "101",
                1,
                "PUBLISHED",
                false,
                ZH,
                "He is paying more and more attention to this matter.",
                "解析",
                List.of(new SentenceChunk(0, "他", List.of("He", "He is"), "代词", null)),
                List.of(0),
                List.of("代词"),
                null);
    }

    private static GradingAnnotation annotation() {
        return new GradingAnnotation(
                "slot:0",
                0,
                "搭配",
                List.of("attach importance to"),
                "attach importance on",
                false,
                "搭配错误",
                "attach importance to");
    }

    private static GradingResult result() {
        return new GradingResult(1, 0, 100, 0, 3, List.of(annotation()), "还差 1 处");
    }
}
