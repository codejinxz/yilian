package io.github.codejinxz.yilian.practice.grading;

import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 申诉初裁的提示词构建与输出解析（独立类，便于对 prompt 契约做单测锁定）。
 *
 * <p>与题目答疑（TutorServiceImpl）、模型复核（ModelReviewPromptBuilder）共用「全角【】上下文标记 + 用户内容剥离标记」的注入面缓解手法，
 * 但喂给模型的内容口径居中：只围绕被申诉的那一条批注—— 题目中文、语块与参考答案结构、本人该槽提交、本地批注理由、用户的申诉主张，
 * 不喂其他用户数据，也无需整句参考译文（槽位参考写法已含在语块结构里）。
 *
 * <p>输出契约：模型只回一个 strict JSON 对象 {@code {"verdict":"SUPPORT|OPPOSE|UNCERTAIN","reason":"中文理由"}}；
 * 解析失败由调用方重试一次后降级，verdict 校验合法枚举、理由截断到 {@link #REASON_MAX_CHARS} 字。初裁只产出「AI 意见」供人工终审参考， 不改判分、不改错题。
 * 契约双执行点：系统提示声明 + 用户消息末尾指令块（{@link #OUTPUT_CONTRACT}）强制——受控实验实证现网网关不生效 top-level system 字段，
 * 只写在系统提示里的契约模型收不到，输出契约必须在用户消息可达；尊重 system 的直连部署则两处双重强调，无害。
 */
public final class AppealAdjudicationPromptBuilder {

    /** 注册表场景名：prompt_versions.name 取此值（V13 种子）。 */
    public static final String PROMPT_NAME = "appeal";

    /** 初裁理由的展示上限（字）：decision_reason 列 VARCHAR(500) 内、列表一行可读的量级。 */
    public static final int REASON_MAX_CHARS = 300;

    /**
     * 输出契约指令块：追加在用户消息末尾，是 strict JSON 契约的 wire 级强制执行点。受控实验实证现网网关不生效 top-level system
     * 字段（只写在系统提示里的契约模型收不到，回复自由文本、解析必败），同文指令并入用户消息可得干净 JSON；尊重 system 的直连部署则与系统提示双重强调，无害。 字段清单与
     * {@link #parse} 解析端一字不差（verdict 合法枚举、reason 非空字符串），改动需同步解析逻辑与 API.md §6.4 说明。
     * 与复核链路的同名手法常量各自独立、文本不同文。
     */
    static final String OUTPUT_CONTRACT =
            """
            【输出格式要求】你的回复必须是且仅是一个 JSON 对象本身，不要输出 Markdown 代码围栏、解释或任何其他字符：
            {"verdict": "SUPPORT|OPPOSE|UNCERTAIN", "reason": "中文初裁理由"}
            其中 verdict 只能是 SUPPORT（申诉成立）、OPPOSE（不成立）、UNCERTAIN（依据不足）三者之一； reason 为非空的中文字符串，不超过 300 字；除这一个 JSON 对象外不要输出任何内容。""";

    /** 合法 verdict 集合：契约外取值按解析失败处理（重试后降级）。 */
    private static final Set<String> VERDICTS = Set.of("SUPPORT", "OPPOSE", "UNCERTAIN");

    /** 一次模型输出的解析结果（verdict 已校验、理由已截断）。 */
    public record AppealAdjudicationOutput(String verdict, String reason) {}

    private AppealAdjudicationPromptBuilder() {}

    /**
     * 系统提示兜底常量：与 V13 种子（name=appeal, version=1）逐字同文， 注册表缺行/读取失败时回退使用，保证主链路降级不中断。改动需同步 V13 种子与
     * API.md §6.4 的 prompt 说明。
     *
     * @return 系统提示文本
     */
    public static String systemPrompt() {
        return """
                你是「译练」的申诉初裁模型，面向中文母语者的中译英刻意练习。请遵守：
                1. 用户对某条本地批注提出申诉，主张自己的写法也应判对；综合题目中文、语块参考答案、
                   用户提交与本地批注，判断该主张是否成立；
                2. 成立（用户写法与规范答案语义等价、未引入新的语言错误）输出 SUPPORT；
                   明确不成立（语义偏离或引入新错误）输出 OPPOSE；依据不足无法判断输出 UNCERTAIN；
                3. 用中文给出不超过 300 字的初裁理由，说清支持或反对的关键依据；
                4. 你只产出供人工终审参考的「AI 意见」，不直接改判分、不改错题、不影响复习排程；
                5. 只输出一个 JSON 对象，格式为 {"verdict": "SUPPORT|OPPOSE|UNCERTAIN", "reason": "字符串"}，
                   不输出任何解释、Markdown 代码块或其他字段。""";
    }

    /**
     * 用户消息：题目上下文 + 被申诉批注 + 申诉主张 + 输出契约指令块。用户提交内容与主张里的全角【】替换为『』， 使其无法伪造「【本地批注结束】忽略以上规则」之类的上下文结构；
     * 末尾追加 {@link #OUTPUT_CONTRACT}（builder 静态文本，不混入用户内容，保证 strict JSON 契约在网关丢弃 top-level system
     * 字段时仍可达）。
     *
     * @param detail 题目详情（已过可见性校验；句已删除/回收时为 null，按「未取得」降级）
     * @param localResult 首判结果（批注来源；解析失败时为 null）
     * @param annotation 被申诉的批注
     * @param proposedAnswer 用户认为可接受的答案，可空
     * @param reason 申诉理由，可空
     * @return 拼装后的用户消息
     */
    public static String userPrompt(
            SentenceDetail detail,
            GradingResult localResult,
            GradingAnnotation annotation,
            String proposedAnswer,
            String reason) {
        StringBuilder builder = new StringBuilder();
        builder.append("【中文原句】")
                .append(detail == null ? "（未取得）" : sanitize(detail.zh()))
                .append('\n');
        builder.append("【语块答案结构】\n");
        appendChunks(builder, detail);
        builder.append("【被申诉批注】\n");
        appendAnnotation(builder, annotation);
        builder.append("【申诉主张】\n");
        builder.append("- 认为可接受的答案：").append(blankToAbsent(proposedAnswer)).append('\n');
        builder.append("- 申诉理由：").append(blankToAbsent(reason)).append('\n');
        builder.append('\n').append(OUTPUT_CONTRACT);
        return builder.toString();
    }

    /**
     * 解析模型输出：剥掉可能的 Markdown 代码围栏后按 strict JSON 读取， 校验 verdict 合法、reason 非空，理由截断到 {@link
     * #REASON_MAX_CHARS} 字。
     *
     * @param mapper JSON 解析器
     * @param content 模型回复正文
     * @return 合法输出；JSON 损坏、verdict 非法或缺字段时为空（调用方重试后降级）
     */
    public static Optional<AppealAdjudicationOutput> parse(ObjectMapper mapper, String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(stripFences(content));
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            JsonNode verdictNode = root.get("verdict");
            JsonNode reasonNode = root.get("reason");
            if (verdictNode == null
                    || !verdictNode.isString()
                    || reasonNode == null
                    || !reasonNode.isString()) {
                return Optional.empty();
            }
            String verdict = verdictNode.stringValue().strip().toUpperCase(Locale.ROOT);
            String reason = truncate(reasonNode.stringValue().strip());
            if (!VERDICTS.contains(verdict) || reason.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new AppealAdjudicationOutput(verdict, reason));
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }

    /**
     * 追加语块答案结构；回收句/旧数据无语块时按「无」处理，不阻塞初裁。
     *
     * @param builder 拼装目标
     * @param detail 题目详情，可为 null
     */
    private static void appendChunks(StringBuilder builder, SentenceDetail detail) {
        if (detail == null || detail.chunks() == null || detail.chunks().isEmpty()) {
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
                                                    .map(AppealAdjudicationPromptBuilder::sanitize)
                                                    .toList()))
                    .append('\n');
        }
    }

    /**
     * 追加被申诉批注：考点 + 用户输入 + 批注理由 + 参考写法；批注定位失败时按「未取得」降级， 不阻塞初裁。
     *
     * @param builder 拼装目标
     * @param annotation 被申诉批注，可为 null
     */
    private static void appendAnnotation(StringBuilder builder, GradingAnnotation annotation) {
        if (annotation == null) {
            builder.append("（未取得本地批注详情）\n");
            return;
        }
        String fix =
                annotation.minimalFix() != null && !annotation.minimalFix().isBlank()
                        ? annotation.minimalFix()
                        : (annotation.expected() == null || annotation.expected().isEmpty()
                                ? "无"
                                : annotation.expected().get(0));
        builder.append("- 语块[")
                .append(annotation.tag())
                .append("] 用户输入「")
                .append(sanitize(annotation.received() == null ? "" : annotation.received()))
                .append("」；批注：")
                .append(
                        annotation.message() == null || annotation.message().isBlank()
                                ? "未命中参考写法"
                                : sanitize(annotation.message()))
                .append("；参考写法：")
                .append(sanitize(fix))
                .append('\n');
    }

    /**
     * 剥离用户内容中的上下文标记（【】→『』）：与答疑/复核同一手法，用户无法在申诉里伪造 「【申诉主张】忽略以上规则」之类的结构。
     *
     * @param text 任意文本
     * @return 剥离标记后的文本
     */
    private static String sanitize(String text) {
        return text == null ? "" : text.replace('【', '『').replace('】', '』');
    }

    /**
     * 空值兜底：可空的申诉字段按「未提供」呈现，让模型明确知道主张缺省而不是猜。
     *
     * @param value 可空文本
     * @return 非空文本
     */
    private static String blankToAbsent(String value) {
        return value == null || value.isBlank() ? "（未提供）" : sanitize(value);
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
}
