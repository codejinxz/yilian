package io.github.codejinxz.yilian.practice.golden;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;
import io.github.codejinxz.yilian.practice.grading.AppealAdjudicationPromptBuilder;
import io.github.codejinxz.yilian.practice.grading.GradingAnnotation;
import io.github.codejinxz.yilian.practice.grading.GradingResult;
import io.github.codejinxz.yilian.practice.grading.ModelReviewPromptBuilder;
import io.github.codejinxz.yilian.practice.llm.LlmChatClient;
import io.github.codejinxz.yilian.practice.llm.LlmClientRegistry;
import io.github.codejinxz.yilian.practice.llm.LlmProtocol;
import io.github.codejinxz.yilian.practice.llm.PromptRegistry;
import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload;
import io.github.codejinxz.yilian.practice.service.impl.TutorServiceImpl;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 黄金集 LLM 三层回归（AC-5②）：review / appeal / ask 走 {@link LlmClientRegistry} 真实客户端， 断言按 AC-4
 * 结构与方向验收（不逐字对拍——LLM 非确定性，预披露裁决）。
 *
 * <p>双闸跳过（CI 默认绿）：① {@code -Dgolden.enabled=true} 未设置 → {@code Assumptions} 跳过； ②设置后网关不可达/无可用客户端 →
 * 探活失败 Assumptions 跳过。跳过逻辑由 {@link GoldenAssetsIntegrityTest} 在 CI 侧证明。
 *
 * <p>网关配置：目录播种在 {@code app_settings}（llm.providers/llm.active，@AfterAll 清理）， 条目默认 ZCode
 * 本地网关（anthropic-messages 轨 key 可空），可用系统属性覆盖：{@code golden.baseUrl} / {@code golden.model}。
 * 已知边界（网关不生效 top-level system 字段时 review/appeal 的 strict JSON 解析会失败——这正是黄金集要暴露的回归，SOP 如实记录该边界。
 */
@Tag("golden")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {"management.health.redis.enabled=false"})
@ActiveProfiles("test")
class GoldenLlmSetTest {

    /** 判分暗示检测：回答不得出现「N 分」形态的判分结论（「不打分」等拒绝性说明不匹配）。 */
    private static final Pattern SCORE_HINT = Pattern.compile("\\d{1,3}\\s*分");

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired private LlmClientRegistry llmClients;

    @Autowired private PromptRegistry promptRegistry;

    @Autowired private JdbcTemplate jdbcTemplate;

    private boolean seeded = false;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 双闸门禁 + 网关探活；任一不满足即跳过整套件（CI 绿的前提）。 */
    @BeforeAll
    void assumeGoldenGateOpen() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                isGoldenEnabled(), "未设置 -Dgolden.enabled=true，golden LLM 套件跳过（CI 默认）");
        // 网关可达前置：目录空时测试上下文无可用客户端，按系统属性播种临时条目（anthropic-messages 轨 key 可空）。
        seedProviderCatalog();
        LlmClientRegistry.ChatHandle handle = llmClients.chatHandle().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                handle != null, "无可用 LLM 客户端（目录未配置或凭证不齐），golden LLM 套件跳过");
        try {
            // 探活：一次最小调用验证网关可达（DEF-01 实验：网关典型延迟 5-14s）。
            LlmChatClient.Reply probe = handle.client().ask("你是探活助手。", "只回复两个字：在线");
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    probe.content() != null && !probe.content().isBlank(), "LLM 网关探活空回复，跳过");
        } catch (RuntimeException exception) {
            org.junit.jupiter.api.Assumptions.abort(
                    "LLM 网关不可达：" + exception.getClass().getSimpleName());
        }
    }

    /** 用例后清理播种的目录条目，避免污染共享测试库。 */
    @AfterAll
    void cleanSeededCatalog() {
        if (seeded) {
            jdbcTemplate.update(
                    "DELETE FROM app_settings WHERE `key` IN ('llm.providers', 'llm.active')");
        }
    }

    /**
     * golden.enabled 门禁（供完整性测试证明跳过逻辑）。
     *
     * @return 属性为 true 时放行
     */
    static boolean isGoldenEnabled() {
        return Boolean.getBoolean("golden.enabled");
    }

    /** 播种临时 provider 目录（只播种一次；失败不抛——chatHandle 判空后按跳过处理）。 */
    private void seedProviderCatalog() {
        Long existing =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM app_settings WHERE `key` = 'llm.providers'",
                        Long.class);
        if (existing != null && existing > 0) {
            return;
        }
        String baseUrl =
                System.getProperty("golden.baseUrl", "http://127.0.0.1:47860/gateway/zcode");
        String model = System.getProperty("golden.model", "glm-5.3");
        String providersJson =
                "{\"schemaVersion\":1,\"providers\":[{\"id\":\"golden-gateway\",\"name\":\"黄金集网关\","
                        + "\"protocol\":\""
                        + LlmProtocol.ANTHROPIC_MESSAGES.wire()
                        + "\",\"baseUrl\":\""
                        + baseUrl
                        + "\",\"model\":\""
                        + model
                        + "\"}]}";
        try {
            jdbcTemplate.update(
                    "INSERT INTO app_settings (`key`, value, description) VALUES ('llm.providers', ?,"
                            + " 'golden runner 临时条目') ON DUPLICATE KEY UPDATE value = VALUES(value)",
                    providersJson);
            jdbcTemplate.update(
                    "INSERT INTO app_settings (`key`, value, description) VALUES ('llm.active', ?,"
                            + " 'golden runner 临时条目') ON DUPLICATE KEY UPDATE value = VALUES(value)",
                    "{\"schemaVersion\":1,\"providerId\":\"golden-gateway\"}");
            seeded = true;
        } catch (RuntimeException exception) {
            org.junit.jupiter.api.Assumptions.abort(
                    "播种 golden provider 目录失败：" + exception.getClass().getSimpleName());
        }
    }

    // ---------- review 层 ----------

    Stream<Named<GoldenCaseFiles.GoldenCase>> reviewCases() {
        return GoldenCaseFiles.load(mapper, GoldenCaseFiles.REVIEW_FILE, "review").cases().stream()
                .map(testCase -> Named.of(testCase.id() + " " + testCase.description(), testCase));
    }

    /**
     * [AC-5] review 层：复核输出结构 + 分数方向验收。
     *
     * @param testCase 黄金集用例
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reviewCases")
    @DisplayName("[AC-5] review 层结构+方向验收")
    void reviewGoldenSetAcceptsStructureAndDirection(GoldenCaseFiles.GoldenCase testCase) {
        ReviewInput input = mapper.convertValue(testCase.input(), ReviewInput.class);
        LlmChatClient.Reply reply =
                handle().ask(
                                systemPromptOf("review", ModelReviewPromptBuilder.systemPrompt()),
                                ModelReviewPromptBuilder.userPrompt(
                                        toDetail(input.detail()),
                                        toAnswer(input.answer()),
                                        toResult(input.localResult())));
        ModelReviewPromptBuilder.ModelReviewOutput output =
                ModelReviewPromptBuilder.parse(mapper, reply.content())
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "["
                                                        + testCase.id()
                                                        + "] 复核输出不可解析（strict JSON 契约破坏）："
                                                        + preview(reply.content())));
        assertThat(output.score()).as("[%s] score 应在 0-100", testCase.id()).isBetween(0, 100);
        assertThat(output.reason()).as("[%s] reason 非空", testCase.id()).isNotBlank();
        assertThat(output.reason().codePointCount(0, output.reason().length()))
                .as("[%s] reason ≤200 字", testCase.id())
                .isLessThanOrEqualTo(testCase.expectation().get("reasonMaxChars").intValue());
        JsonNode expectation = testCase.expectation();
        JsonNode min = expectation.get("minScore");
        if (min != null && !min.isNull()) {
            assertThat(output.score())
                    .as(
                            "[%s] 方向验收 high：score ≥%d（rationale=%s）",
                            testCase.id(), min.intValue(), testCase.rationale())
                    .isGreaterThanOrEqualTo(min.intValue());
        }
        JsonNode max = expectation.get("maxScore");
        if (max != null && !max.isNull()) {
            assertThat(output.score())
                    .as(
                            "[%s] 方向验收 low：score ≤%d（rationale=%s）",
                            testCase.id(), max.intValue(), testCase.rationale())
                    .isLessThanOrEqualTo(max.intValue());
        }
    }

    // ---------- appeal 层 ----------

    Stream<Named<GoldenCaseFiles.GoldenCase>> appealCases() {
        return GoldenCaseFiles.load(mapper, GoldenCaseFiles.APPEAL_FILE, "appeal").cases().stream()
                .map(testCase -> Named.of(testCase.id() + " " + testCase.description(), testCase));
    }

    /**
     * [AC-5] appeal 层：初裁输出结构 + verdict 方向验收。
     *
     * @param testCase 黄金集用例
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("appealCases")
    @DisplayName("[AC-5] appeal 层结构+方向验收")
    void appealGoldenSetAcceptsStructureAndDirection(GoldenCaseFiles.GoldenCase testCase) {
        AppealInput input = mapper.convertValue(testCase.input(), AppealInput.class);
        LlmChatClient.Reply reply =
                handle().ask(
                                systemPromptOf(
                                        "appeal", AppealAdjudicationPromptBuilder.systemPrompt()),
                                AppealAdjudicationPromptBuilder.userPrompt(
                                        toDetail(input.detail()),
                                        null,
                                        input.annotation() == null
                                                ? null
                                                : mapper.convertValue(
                                                        input.annotation(),
                                                        GradingAnnotation.class),
                                        input.proposedAnswer(),
                                        input.reason()));
        AppealAdjudicationPromptBuilder.AppealAdjudicationOutput output =
                AppealAdjudicationPromptBuilder.parse(mapper, reply.content())
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "["
                                                        + testCase.id()
                                                        + "] 初裁输出不可解析（strict JSON 契约破坏）："
                                                        + preview(reply.content())));
        List<String> allowed = new java.util.ArrayList<>();
        for (JsonNode verdict : testCase.expectation().get("verdicts")) {
            allowed.add(verdict.stringValue());
        }
        assertThat(output.verdict())
                .as(
                        "[%s] verdict 应在 %s（rationale=%s）",
                        testCase.id(), allowed, testCase.rationale())
                .isIn(allowed);
        assertThat(output.reason()).as("[%s] reason 非空", testCase.id()).isNotBlank();
        assertThat(output.reason().codePointCount(0, output.reason().length()))
                .as("[%s] reason ≤300 字", testCase.id())
                .isLessThanOrEqualTo(testCase.expectation().get("reasonMaxChars").intValue());
    }

    // ---------- ask 层 ----------

    Stream<Named<GoldenCaseFiles.GoldenCase>> askCases() {
        return GoldenCaseFiles.load(mapper, GoldenCaseFiles.ASK_FILE, "ask").cases().stream()
                .map(testCase -> Named.of(testCase.id() + " " + testCase.description(), testCase));
    }

    /**
     * [AC-5] ask 层：回答非空 + 内容红线（不含参考译文整句、不含判分暗示）+ 免责常量契约。
     *
     * @param testCase 黄金集用例
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("askCases")
    @DisplayName("[AC-5] ask 层红线验收")
    void askGoldenSetAcceptsContentBoundaries(GoldenCaseFiles.GoldenCase testCase) {
        AskInput input = mapper.convertValue(testCase.input(), AskInput.class);
        // 免责常量契约：服务端 TutorAskView 对每条回答统一附加（golden 层锁常量值，附加点由服务层单测覆盖）。
        assertThat(TutorServiceImpl.DISCLAIMER).as("答疑常驻免责标注契约值").isEqualTo("AI 生成，可能有误");
        LlmChatClient.Reply reply =
                handle().ask(
                                systemPromptOf("ask", TutorServiceImpl.ASK_SYSTEM_PROMPT_FALLBACK),
                                askUserPrompt(
                                        input.detail().zh(),
                                        input.detail().tags(),
                                        input.annotations(),
                                        input.question()));
        String answer = reply.content();
        assertThat(answer).as("[%s] 回答非空", testCase.id()).isNotBlank();
        for (JsonNode exclusion : testCase.expectation().get("exclusions")) {
            assertThat(answer)
                    .as("[%s] 回答不得含参考译文整句（红线）", testCase.id())
                    .doesNotContain(exclusion.stringValue());
        }
        assertThat(SCORE_HINT.matcher(answer).find())
                .as("[%s] 回答不得含判分暗示（N 分形态）", testCase.id())
                .isFalse();
    }

    /**
     * 答疑用户消息拼装：与 TutorServiceImpl.userPrompt 的上下文标记逐行同形（API §6.12 契约面）。
     *
     * @param zh 中文原句
     * @param tags 考点标签
     * @param annotations 错误批注摘要行
     * @param question 用户问题
     * @return 拼装后的用户消息
     */
    private static String askUserPrompt(
            String zh, List<String> tags, List<String> annotations, String question) {
        StringBuilder builder = new StringBuilder();
        builder.append("【句子（中文）】").append(zh == null ? "" : zh).append('\n');
        builder.append("【考点】")
                .append(tags == null || tags.isEmpty() ? "无" : String.join("、", tags))
                .append('\n');
        builder.append("【我最近的批注】\n");
        if (annotations == null || annotations.isEmpty()) {
            builder.append("（暂无：尚未提交过，或最近一次全对）\n");
        } else {
            for (String line : annotations) {
                builder.append("- ").append(line).append('\n');
            }
        }
        builder.append("【问题开始】\n")
                .append(question.replace('【', '『').replace('】', '』'))
                .append("\n【问题结束】");
        return builder.toString();
    }

    // ---------- 共用辅助 ----------

    /**
     * 取当前句柄的对话客户端（@BeforeAll 探活已保证存在）。
     *
     * @return 可用客户端
     */
    private LlmChatClient handle() {
        return llmClients
                .chatHandle()
                .orElseThrow(() -> new AssertionError("golden 门禁已放行但句柄缺失（探活与用例间状态漂移）"))
                .client();
    }

    /**
     * 系统提示：与主链同缝——注册表取 ACTIVE 版本，缺行回退代码常量（AC-2 换轨语义的消费点）。
     *
     * @param name 注册表场景名
     * @param fallback 兜底常量
     * @return 系统提示文本
     */
    private String systemPromptOf(String name, String fallback) {
        PromptRegistry.Entry entry = promptRegistry.load(name);
        return entry instanceof PromptRegistry.Present present ? present.content() : fallback;
    }

    /**
     * 输出预览（失败诊断用；只取前 120 字符，避免整段模型输出进构建日志）。
     *
     * @param content 模型原文
     * @return 预览
     */
    private static String preview(String content) {
        if (content == null) {
            return "null";
        }
        return content.length() <= 120 ? content : content.substring(0, 120) + "…";
    }

    /** review 输入形状。 */
    record ReviewInput(DetailLike detail, AnswerLike answer, ResultLike localResult) {}

    /** appeal 输入形状。 */
    record AppealInput(
            DetailLike detail, JsonNode annotation, String proposedAnswer, String reason) {}

    /** ask 输入形状。 */
    record AskInput(DetailLike detail, List<String> annotations, String question) {}

    /** 题目上下文（用户消息实际消费的字段）。 */
    record DetailLike(String zh, String ref, List<String> tags, List<SentenceChunk> chunks) {}

    /** 提交载荷（slots 或 text 二选一）。 */
    record AnswerLike(List<SlotLike> slots, String text) {}

    /** 单槽提交。 */
    record SlotLike(Integer chunkIndex, String value) {}

    /** 本地判分结果（批注来源）。 */
    record ResultLike(
            Integer score,
            Integer hitCount,
            Integer totalCount,
            String summary,
            List<JsonNode> annotations) {}

    /**
     * 构造题目详情（golden 用例只需 zh/ref/chunks/tags，其余字段为哑值——builder 只消费这四项）。
     *
     * @param like 输入形状
     * @return 题目详情
     */
    private SentenceDetail toDetail(DetailLike like) {
        List<SentenceChunk> chunks = like.chunks() == null ? List.of() : like.chunks();
        List<String> tags = like.tags() == null ? List.of() : like.tags();
        return new SentenceDetail(
                "golden",
                "1",
                1,
                "1",
                1,
                "PUBLISHED",
                false,
                like.zh(),
                like.ref(),
                null,
                chunks,
                List.of(),
                tags,
                null);
    }

    /**
     * 构造提交载荷。
     *
     * @param like 输入形状
     * @return 提交载荷
     */
    private AnswerPayload toAnswer(AnswerLike like) {
        List<AnswerPayload.Slot> slots =
                like.slots() == null
                        ? null
                        : like.slots().stream()
                                .map(
                                        slot ->
                                                new AnswerPayload.Slot(
                                                        slot.chunkIndex(), slot.value()))
                                .toList();
        return new AnswerPayload(1, slots != null ? "CLOZE" : "FREE", slots, like.text());
    }

    /**
     * 构造本地判分结果（builder 只消费 annotations）。
     *
     * @param like 输入形状
     * @return 判分结果
     */
    private GradingResult toResult(ResultLike like) {
        List<GradingAnnotation> annotations =
                like.annotations() == null
                        ? List.of()
                        : like.annotations().stream()
                                .map(node -> mapper.convertValue(node, GradingAnnotation.class))
                                .toList();
        return new GradingResult(
                1,
                like.score(),
                100,
                like.hitCount(),
                like.totalCount(),
                annotations,
                like.summary());
    }
}
