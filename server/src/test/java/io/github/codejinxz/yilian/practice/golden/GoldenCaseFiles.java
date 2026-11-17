package io.github.codejinxz.yilian.practice.golden;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 黄金集资产加载器（AC-4/AC-5）：从 classpath {@code golden/} 读取四层用例 JSON， 映射为最小类型形状供 runner 与资产完整性测试共用。
 *
 * <p>加载即校验（fail-fast）：文件缺失、cases 缺失或层号不符直接抛出——黄金集是「必跑」门禁的输入， 输入本身损坏时宁可红也不静默跳过。
 */
public final class GoldenCaseFiles {

    /** 资产目录（classpath 根相对路径）。 */
    public static final String DIR = "golden";

    /** 四层资产文件名与最低条数（AC-4 验收下限；完整性测试据此锁定）。 */
    public static final String GRADING_FILE = "grading-cloze.json";

    public static final String REVIEW_FILE = "review-cases.json";

    public static final String APPEAL_FILE = "appeal-cases.json";

    public static final String ASK_FILE = "ask-cases.json";

    /** grading 层最低条数。 */
    public static final int GRADING_MIN_CASES = 30;

    /** review 层最低条数。 */
    public static final int REVIEW_MIN_CASES = 25;

    /** appeal 层最低条数。 */
    public static final int APPEAL_MIN_CASES = 25;

    /** ask 层最低条数。 */
    public static final int ASK_MIN_CASES = 10;

    private GoldenCaseFiles() {
        // 工具类，不实例化
    }

    /**
     * 读取一层黄金集资产。
     *
     * @param mapper JSON 解析器
     * @param filename 资产文件名（{@link #DIR} 下）
     * @param expectedLayer 期望的层号（资产内 layer 字段必须一致）
     * @return 解析后的资产
     */
    public static GoldenFile load(ObjectMapper mapper, String filename, String expectedLayer) {
        try (var stream =
                GoldenCaseFiles.class.getClassLoader().getResourceAsStream(DIR + "/" + filename)) {
            if (stream == null) {
                throw new IllegalStateException("黄金集资产缺失：" + DIR + "/" + filename);
            }
            GoldenFile file = mapper.readValue(stream, GoldenFile.class);
            if (!expectedLayer.equals(file.layer())) {
                throw new IllegalStateException(
                        "黄金集资产层号不符：" + filename + " 期望 " + expectedLayer + " 实际 " + file.layer());
            }
            if (file.cases() == null || file.cases().isEmpty()) {
                throw new IllegalStateException("黄金集资产无用例：" + filename);
            }
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException("黄金集资产解析失败：" + filename, exception);
        }
    }

    /** 资产文件根结构：layer/version/说明字段 + cases 列表（input/expectation 保持 JsonNode 由各 runner 自行映射）。 */
    public record GoldenFile(
            String layer,
            int version,
            String baseline,
            String acceptance,
            String source,
            List<GoldenCase> cases) {}

    /** 单条用例：id/layer/description/input/expectation 为固定形状（AC-4），rationale 为人工复核说明。 */
    public record GoldenCase(
            String id,
            String layer,
            String mode,
            String description,
            String rationale,
            JsonNode input,
            JsonNode expectation) {}
}
