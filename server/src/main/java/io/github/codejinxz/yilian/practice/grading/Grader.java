package io.github.codejinxz.yilian.practice.grading;

import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 判分器：语块填空 / 整句翻译 / 看答案三分支的纯函数实现。
 *
 * <p>语义基准是前端 {@code web/src/features/practice/grading.ts} 的 {@code gradeCloze} 与
 * {@code gradeFree}、{@code engine.ts} 的 {@code revealGrading}，服务端逐值移植；
 * golden 单测锁定全角、弯引号、句末标点、部分命中与 FREE 覆盖率等样例。
 * 判分输入经 {@link Norm1Normalizer}（norm-1）归一化，与幂等哈希共用同一口径。
 */
public final class Grader {

    private Grader() {
        // 纯函数工具类，不实例化
    }

    /**
     * 语块填空判分：整块命中任一可接受答案即该语块全对；否则按规范答案逐词位置比对，
     * 部分对 = 命中词数 / 总词数 × 100。
     *
     * @param chunks 题目语块（含可接受答案与考点标签）
     * @param blank 挖空语块下标，顺序即题面编号顺序
     * @param answers 与 blank 对齐的用户答案；缺位或 null 视为未填写
     * @return 0–100 的分数、逐槽批注与一句摘要
     */
    public static GradingResult gradeCloze(
            List<SentenceChunk> chunks, List<Integer> blank, List<String> answers) {
        List<GradingAnnotation> annotations = new ArrayList<>();
        int hitCount = 0;
        int totalCount = 0;

        for (int position = 0; position < blank.size(); position++) {
            int chunkIndex = blank.get(position);
            String received = answerAt(answers, position);
            String normalizedReceived = Norm1Normalizer.normalizeAnswer(received);
            SentenceChunk chunk = findChunk(chunks, chunkIndex);

            if (chunk == null) {
                annotations.add(
                        new GradingAnnotation(
                                "slot:" + chunkIndex,
                                chunkIndex,
                                "未分类",
                                List.of(),
                                received,
                                false,
                                "语块不存在",
                                null));
                continue;
            }

            List<String> expected = acceptableAnswers(chunk);
            String canonical = expected.isEmpty() ? "" : expected.get(0);
            String tag = chunk.tag() == null || chunk.tag().isBlank() ? "未分类" : chunk.tag().trim();
            boolean wholeHit =
                    !normalizedReceived.isEmpty()
                            && expected.stream()
                                    .anyMatch(
                                            answer ->
                                                    Norm1Normalizer.normalizeAnswer(answer)
                                                            .equals(normalizedReceived));

            List<String> expectedTokens = Norm1Normalizer.tokenizeAnswer(canonical);
            totalCount += expectedTokens.size();

            int matched;
            if (wholeHit) {
                matched = expectedTokens.size();
            } else {
                List<String> receivedTokens = Norm1Normalizer.tokenizeAnswer(received);
                int limit = Math.min(expectedTokens.size(), receivedTokens.size());
                matched = 0;
                for (int index = 0; index < limit; index++) {
                    if (expectedTokens.get(index).equals(receivedTokens.get(index))) {
                        matched++;
                    }
                }
            }
            hitCount += matched;

            annotations.add(
                    new GradingAnnotation(
                            "slot:" + chunkIndex,
                            chunkIndex,
                            tag,
                            expected,
                            received,
                            wholeHit,
                            message(tag, normalizedReceived, wholeHit),
                            wholeHit || canonical.isEmpty() ? null : canonical));
        }

        int score =
                totalCount == 0 ? 0 : (int) Math.round((double) hitCount / totalCount * 100);
        long missed = annotations.stream().filter(annotation -> !annotation.correct()).count();
        String summary =
                annotations.isEmpty()
                        ? "没有需要判定的语块"
                        : missed == 0 ? "全部命中" : "还差 " + missed + " 处";
        return new GradingResult(1, score, 100, hitCount, totalCount, annotations, summary);
    }

    /**
     * 整句翻译粗判：完全一致给 100；否则按参考译文词形的多重集覆盖率给部分分。
     *
     * <p>与本地引擎一致：只做词形覆盖，不做语法分析；真实深度批改由服务端后续波次接入。
     *
     * @param ref 参考译文
     * @param text 用户译文
     * @return 与填空结果同结构的批注结果
     */
    public static GradingResult gradeFree(String ref, String text) {
        String normalizedRef = Norm1Normalizer.normalizeAnswer(ref);
        String normalizedText = Norm1Normalizer.normalizeAnswer(text);
        List<String> expected = Norm1Normalizer.tokenizeAnswer(ref);
        List<String> received = Norm1Normalizer.tokenizeAnswer(text);
        boolean exact = !normalizedRef.isEmpty() && normalizedRef.equals(normalizedText);

        List<String> pool = new ArrayList<>(received);
        int hitCount = 0;
        for (String token : expected) {
            int index = pool.indexOf(token);
            if (index >= 0) {
                hitCount++;
                pool.remove(index);
            }
        }

        int totalCount = expected.size();
        int score =
                exact ? 100 : totalCount == 0 ? 0 : (int) Math.round((double) hitCount / totalCount * 100);
        boolean blankAnswer = text == null || text.trim().isEmpty();
        List<GradingAnnotation> annotations =
                exact || blankAnswer
                        ? List.of()
                        : List.of(
                                new GradingAnnotation(
                                        "free:0",
                                        0,
                                        "整句",
                                        ref == null ? List.of() : List.of(ref),
                                        text,
                                        false,
                                        "与参考译文有差异",
                                        ref));
        String summary = exact ? "全部命中" : blankAnswer ? "未作答" : "要点覆盖 " + score + "%";
        return new GradingResult(1, score, 100, hitCount, totalCount, annotations, summary);
    }

    /**
     * 看答案的判分结果：不计分，但按挖空语块规范答案的词数给出占位 totalCount。
     *
     * @param chunks 题目语块
     * @param blank 挖空语块下标
     * @return score=null 的判分结果
     */
    public static GradingResult gradeForReveal(List<SentenceChunk> chunks, List<Integer> blank) {
        int total = 0;
        for (Integer chunkIndex : blank) {
            SentenceChunk chunk = findChunk(chunks, chunkIndex);
            String canonical =
                    chunk == null || chunk.answers() == null || chunk.answers().isEmpty()
                            ? ""
                            : chunk.answers().get(0);
            total += Norm1Normalizer.tokenizeAnswer(canonical).size();
        }
        return new GradingResult(1, null, 100, 0, total, List.of(), "已看答案，记为学习中");
    }

    /**
     * 生成批注文案；保持「考点 + 错误」的格式，便于结果展示可解释性。
     *
     * @param tag 考点标签
     * @param normalizedReceived 归一化后的用户输入
     * @param correct 是否整块命中
     * @return 可展示的批注或 null
     */
    private static String message(String tag, String normalizedReceived, boolean correct) {
        if (correct) {
            return null;
        }
        if (normalizedReceived.isEmpty()) {
            return "未填写";
        }
        return tag.endsWith("错误") ? tag : tag + "错误";
    }

    /**
     * 读取某个挖空位置的用户答案。
     *
     * @param answers 与 blank 对齐的答案列表，可空
     * @param position blank 数组中的位置
     * @return 用户输入，缺省为空串
     */
    private static String answerAt(List<String> answers, int position) {
        if (answers == null || position >= answers.size()) {
            return "";
        }
        String answer = answers.get(position);
        return answer == null ? "" : answer;
    }

    /**
     * 按稳定下标取语块。
     *
     * @param chunks 题目语块
     * @param chunkIndex 语块下标
     * @return 语块；不存在时为 null
     */
    private static SentenceChunk findChunk(List<SentenceChunk> chunks, int chunkIndex) {
        if (chunks == null) {
            return null;
        }
        for (SentenceChunk chunk : chunks) {
            if (chunk.index() == chunkIndex) {
                return chunk;
            }
        }
        return null;
    }

    /**
     * 过滤可接受答案：归一化后非空的才参与命中判定。
     *
     * @param chunk 语块
     * @return 非空可接受答案列表
     */
    private static List<String> acceptableAnswers(SentenceChunk chunk) {
        if (chunk.answers() == null) {
            return List.of();
        }
        return chunk.answers().stream()
                .filter(answer -> answer != null && !Norm1Normalizer.normalizeAnswer(answer).isEmpty())
                .toList();
    }
}
