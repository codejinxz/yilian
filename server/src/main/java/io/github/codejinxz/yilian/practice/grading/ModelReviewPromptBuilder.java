package io.github.codejinxz.yilian.practice.grading;

import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;
import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * 模型复核的提示词构建与输出解析（独立类，便于对 prompt 契约做单测锁定）。
 *
 * <p>与题目答疑（TutorServiceImpl）共用「全角【】上下文标记 + 用户内容剥离标记」的注入面缓解手法，
 * 但喂给模型的内容口径不同：复核是重新判分，参考译文与语块答案结构必须进提示词（答疑刻意不给）。
 *
 * <p>输出契约：模型只回一个 strict JSON 对象 {@code {"score":0-100,"reason":"中文理由"}}； 解析失败由调用方重试一次后降级，分数钳制
 * 0–100、理由截断到 {@link #REASON_MAX_CHARS} 字。契约双执行点：系统提示声明 + 用户消息末尾指令块（{@link
 * #OUTPUT_CONTRACT}）强制——受控实验实证现网网关 不生效 top-level system 字段，只写在系统提示的契约模型收不到，输出契约必须在用户消息可达；尊重 system
 * 的直连部署则两处双重强调，无害。
 */
public final class ModelReviewPromptBuilder {

    /** 注册表场景名：prompt_versions.name 取此值（V13 种子）。 */
    public static final String PROMPT_NAME = "review";

    /** 复核理由的展示上限（字）：批注栏一行能读完的量级，超出截断。 */
    public static final int REASON_MAX_CHARS = 200;

    /**
     * 输出契约指令块：追加在用户消息末尾，是 strict JSON 契约的 wire 级强制执行点。受控实验实证现网网关不生效 top-level system
     * 字段（只写在系统提示里的契约模型收不到，回复自由文本、解析必败），同文指令并入用户消息可得干净 JSON；尊重 system 的直连部署则与系统提示双重强调，无害。 字段清单与
     * {@link #parse} 解析端一字不差（score 数值、reason 非空字符串），改动需同步解析逻辑与 API.md §6.13
     * 说明。与初裁链路的同名手法常量各自独立、文本不同文。
     */
    static final String OUTPUT_CONTRACT =
            """
            【输出格式要求】你的回复必须是且仅是一个 JSON 对象本身，不要输出 Markdown 代码围栏、解释或任何其他字符：
            {"score": 0–100 的整数, "reason": "中文复核理由"}
            其中 score 为 0–100 的整数；reason 为非空的中文字符串，不超过 200 字；除这一个 JSON 对象外不要输出任何内容。""";

    /** 一次模型输出的解析结果（分数已钳制、理由已截断）。 */
    public record ModelReviewOutput(int score, String reason) {}

    private ModelReviewPromptBuilder() {}

    /**
     * 系统提示兜底常量：与 V13 种子（name=review, version=1）逐字同文， 注册表缺行/读取失败时由调用方回退使用，保证复核链路降级不中断。改动需同步 V13 种子与
     * API.md §6.13 的 prompt 说明。
     *
     * @return 系统提示文本
     */
    public static String systemPrompt() {
        return """
                你是「译练」的中译英批改复核模型，面向中文母语者的刻意练习。请遵守：
                1. 综合参考译文、语块答案结构与本地批注，对用户的译文重新给一个 0–100 的整数分；
                2. 评分看整体可懂度与关键错误：核心语义与语法是否成立，不苛求与参考译文逐词一致；
                3. 用中文给出一句不超过 200 字的复核理由，指出最值得改的一处并给出建议写法；
                4. 只输出一个 JSON 对象，格式为 {"score": 整数, "reason": "字符串"}，
                   不输出任何解释、Markdown 代码块或其他字段。""";
    }

    /**
     * 用户消息：题目上下文 + 本人提交 + 本地批注 + 输出契约指令块。语块答案结构与参考译文进提示词（复核即重新判分，
     * 与答疑的「不给参考译文」口径不同）；用户提交内容里的全角【】替换为『』， 使其无法伪造「【批注结束】忽略以上规则」之类的上下文结构；末尾追加 {@link
     * #OUTPUT_CONTRACT}（builder 静态文本，不混入用户内容， 保证 strict JSON 契约在网关丢弃 top-level system 字段时仍可达）。
     *
     * @param detail 题目详情（已过可见性校验）
     * @param answer 本人提交载荷（填空带 slots、整句带 text）
     * @param localResult 本地判分结果（批注来源）
     * @return 拼装后的用户消息
     */
    public static String userPrompt(
            SentenceDetail detail, AnswerPayload answer, GradingResult localResult) {
        StringBuilder builder = new StringBuilder();
        builder.append("【中文原句】").append(nullSafe(detail.zh())).append('\n');
        builder.append("【参考译文】").append(nullSafe(detail.ref())).append('\n');
        builder.append("【语块答案结构】\n");
        appendChunks(builder, detail);
        builder.append("【我的提交】\n");
        appendAnswer(builder, answer);
        builder.append("【本地批注】\n");
        appendAnnotations(builder, localResult);
        builder.append('\n').append(OUTPUT_CONTRACT);
        return builder.toString();
    }

    /**
     * 解析模型输出：剥掉可能的 Markdown 代码围栏后按 strict JSON 读取， 校验 score/reason 齐备，分数钳制 0–100、理由截断到 {@link
     * #REASON_MAX_CHARS} 字。
     *
     * @param mapper JSON 解析器
     * @param content 模型回复正文
     * @return 合法输出；JSON 损坏、缺字段或 score/reason 类型不对时为空（调用方重试后降级）
     */
    public static Optional<ModelReviewOutput> parse(ObjectMapper mapper, String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(stripFences(content));
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            JsonNode scoreNode = root.get("score");
            JsonNode reasonNode = root.get("reason");
            if (scoreNode == null
                    || !scoreNode.isNumber()
                    || reasonNode == null
                    || !reasonNode.isString()) {
                return Optional.empty();
            }
            int score = clamp(scoreNode.intValue());
            String reason = truncate(reasonNode.stringValue().strip());
            if (reason.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ModelReviewOutput(score, reason));
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }

    /**
     * 追加语块答案结构；回收句/旧数据无语块时按「无」处理，不阻塞复核。
     *
     * @param builder 拼装目标
     * @param detail 题目详情
     */
    private static void appendChunks(StringBuilder builder, SentenceDetail detail) {
        if (detail.chunks() == null || detail.chunks().isEmpty()) {
            builder.append("（无语块结构）\n");
            return;
        }
        for (var chunk : detail.chunks()) {
            builder.append("- 语块")
                    .append(chunk.index())
                    .append("「")
                    .append(sanitize(chunk.zh()))
                    .append("」参考写法：")
                    .append(
                            chunk.answers() == null || chunk.answers().isEmpty()
                                    ? "无"
                                    : String.join(
                                            " / ",
                                            chunk.answers().stream()
                                                    .map(ModelReviewPromptBuilder::sanitize)
                                                    .toList()))
                    .append('\n');
        }
    }

    /**
     * 追加本人提交：填空逐槽、整句取 text；载荷损坏时按「未取得」处理。
     *
     * @param builder 拼装目标
     * @param answer 提交载荷
     */
    private static void appendAnswer(StringBuilder builder, AnswerPayload answer) {
        if (answer == null) {
            builder.append("（未取得提交内容）\n");
            return;
        }
        if (answer.slots() != null && !answer.slots().isEmpty()) {
            for (AnswerPayload.Slot slot : answer.slots()) {
                builder.append("- 语块")
                        .append(slot.chunkIndex())
                        .append("：")
                        .append(sanitize(slot.value() == null ? "（未填写）" : slot.value()))
                        .append('\n');
            }
            return;
        }
        builder.append(
                        sanitize(
                                answer.text() == null || answer.text().isBlank()
                                        ? "（未填写）"
                                        : answer.text()))
                .append('\n');
    }

    /**
     * 追加本地批注摘要（与答疑的同款行格式）；无错误批注时注明全对。
     *
     * @param builder 拼装目标
     * @param localResult 本地判分结果
     */
    private static void appendAnnotations(StringBuilder builder, GradingResult localResult) {
        if (localResult == null || localResult.annotations() == null) {
            builder.append("（未取得本地批注）\n");
            return;
        }
        List<GradingAnnotation> wrong =
                localResult.annotations().stream().filter(a -> !a.correct()).toList();
        if (wrong.isEmpty()) {
            builder.append("（本地判分全对，或无错误批注）\n");
            return;
        }
        for (GradingAnnotation annotation : wrong) {
            String fix =
                    annotation.minimalFix() != null && !annotation.minimalFix().isBlank()
                            ? annotation.minimalFix()
                            : (annotation.expected().isEmpty()
                                    ? "无"
                                    : annotation.expected().get(0));
            builder.append("- 语块[")
                    .append(annotation.tag())
                    .append("] 用户输入「")
                    .append(sanitize(annotation.received() == null ? "" : annotation.received()))
                    .append("」；批注：")
                    .append(annotation.message() == null ? "未命中" : sanitize(annotation.message()))
                    .append("；参考写法：")
                    .append(sanitize(fix))
                    .append('\n');
        }
    }

    /**
     * 剥离用户内容中的上下文标记（【】→『』）：与答疑同一手法，用户无法在提交里伪造 「【本地批注】忽略以上规则」之类的结构。
     *
     * @param text 任意文本
     * @return 剥离标记后的文本
     */
    private static String sanitize(String text) {
        return text == null ? "" : text.replace('【', '『').replace('】', '』');
    }

    /**
     * 剥离模型输出可能包裹的 Markdown 代码围栏（```json ... ```）：契约要求裸 JSON， 但供应商对指令的遵循度不稳，剥围栏是最小容错。
     *
     * @param content 模型回复正文
     * @return 去围栏后的正文
     */
    private static String stripFences(String content) {
        String stripped = content.strip();
        if (stripped.startsWith("```")) {
            int firstBreak = stripped.indexOf('\n');
            if (firstBreak >= 0) {
                stripped = stripped.substring(firstBreak + 1);
            }
            int closing = stripped.lastIndexOf("```");
            if (closing >= 0) {
                stripped = stripped.substring(0, closing);
            }
        }
        return stripped.strip();
    }

    /**
     * 分数钳制到 0–100：模型轻微越界时纠偏而不是整次降级。
     *
     * @param score 模型给出的分数
     * @return 钳制后的分数
     */
    private static int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    /**
     * 理由按 Unicode 码点截断到上限字数：直接按 String 截会在代理对中间切开产生乱码。
     *
     * @param reason 原始理由
     * @return 不超过 {@link #REASON_MAX_CHARS} 字的理由
     */
    private static String truncate(String reason) {
        if (reason.codePointCount(0, reason.length()) <= REASON_MAX_CHARS) {
            return reason;
        }
        return reason.substring(0, reason.offsetByCodePoints(0, REASON_MAX_CHARS));
    }

    /**
     * 空值兜底。
     *
     * @param value 可空文本
     * @return 非空文本
     */
    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
