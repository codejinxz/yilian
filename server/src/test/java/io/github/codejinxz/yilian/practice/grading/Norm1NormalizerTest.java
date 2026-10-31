package io.github.codejinxz.yilian.practice.grading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * norm-1 归一化 golden 测试：样例与前端 {@code grading.ts} 的 {@code normalizeAnswer} 行为逐值对齐（ECMAScript
 * 语义逐步推导），锁定全角、弯引号、句末标点与空白压缩。
 */
class Norm1NormalizerTest {

    /** 全角字母、全角标点与全角空格全部折叠为半角。 */
    @Test
    @DisplayName("全角转半角：Ｐａｙ　Ａｔｔｅｎｔｉｏｎ。→ pay attention")
    void fullWidthToAscii() {
        assertThat(Norm1Normalizer.normalizeAnswer("Ｐａｙ　Ａｔｔｅｎｔｉｏｎ。")).isEqualTo("pay attention");
        assertThat(Norm1Normalizer.normalizeAnswer("ＰＡＹ")).isEqualTo("pay");
        assertThat(Norm1Normalizer.normalizeAnswer("Ｍore　ａｎｄ　ｍｏｒｅ。")).isEqualTo("more and more");
    }

    /** 弯引号族（单双两种）转直引号。 */
    @Test
    @DisplayName("弯引号转直引号：it’s / “pay” / ‘pay’")
    void curlyQuotesToAscii() {
        assertThat(Norm1Normalizer.normalizeAnswer("it’s fine")).isEqualTo("it's fine");
        assertThat(Norm1Normalizer.normalizeAnswer("“pay”")).isEqualTo("\"pay\"");
        assertThat(Norm1Normalizer.normalizeAnswer("‘pay’")).isEqualTo("'pay'");
        assertThat(Norm1Normalizer.normalizeAnswer("it′s")).isEqualTo("it's");
    }

    /** 句末标点剥除：中英句号、问叹号、逗号分号与省略号，仅剥末尾。 */
    @Test
    @DisplayName("去句末标点：句号/问号/逗号/省略号，仅末尾")
    void trailingPunctuationStripped() {
        assertThat(Norm1Normalizer.normalizeAnswer("more and more.")).isEqualTo("more and more");
        assertThat(Norm1Normalizer.normalizeAnswer("more and more！")).isEqualTo("more and more");
        assertThat(Norm1Normalizer.normalizeAnswer("he said,")).isEqualTo("he said");
        assertThat(Norm1Normalizer.normalizeAnswer("he said;")).isEqualTo("he said");
        assertThat(Norm1Normalizer.normalizeAnswer("he said…")).isEqualTo("he said");
        assertThat(Norm1Normalizer.normalizeAnswer("attention.to")).isEqualTo("attention.to");
        // 标点 + 引号结尾：引号保留（与前端 $2 回填行为一致）。
        assertThat(Norm1Normalizer.normalizeAnswer("he said.\"")).isEqualTo("he said\"");
    }

    /** 空白压缩：连续半角空格、制表符与换行压成单空格并去首尾。 */
    @Test
    @DisplayName("压缩空白：多空格/制表符/换行 → 单空格")
    void whitespaceCollapsed() {
        assertThat(Norm1Normalizer.normalizeAnswer("  pay   attention\t\n"))
                .isEqualTo("pay attention");
        assertThat(Norm1Normalizer.normalizeAnswer("　pay　")).isEqualTo("pay");
    }

    /** 空值与纯标点输入归一化为空串，切词为空列表。 */
    @Test
    @DisplayName("空答案：null/空串/纯标点 → 空串与空词表")
    void emptyAnswers() {
        assertThat(Norm1Normalizer.normalizeAnswer(null)).isEmpty();
        assertThat(Norm1Normalizer.normalizeAnswer("")).isEmpty();
        assertThat(Norm1Normalizer.normalizeAnswer("。。")).isEmpty();
        assertThat(Norm1Normalizer.tokenizeAnswer("")).isEmpty();
        assertThat(Norm1Normalizer.tokenizeAnswer(null)).isEmpty();
    }

    /** 切词：小写化与压缩后再按空格切。 */
    @Test
    @DisplayName("切词：Pay  Attention → [pay, attention]")
    void tokenize() {
        assertThat(Norm1Normalizer.tokenizeAnswer("Pay  Attention"))
                .containsExactly("pay", "attention");
        assertThat(Norm1Normalizer.tokenizeAnswer("Ｍore ａｎｄ ｍｏｒｅ。"))
                .containsExactly("more", "and", "more");
    }

    /** 中文原句同样过 norm-1：末尾中文句号剥除；NFKC 会把内部全角逗号折叠为半角（与前端一致）。 */
    @Test
    @DisplayName("中文句末标点：越来越。→ 越来越；内部全角逗号折叠为半角")
    void chineseTrailingPunctuation() {
        assertThat(Norm1Normalizer.normalizeAnswer("越来越。")).isEqualTo("越来越");
        assertThat(Norm1Normalizer.normalizeAnswer("越来越重视，这件事")).isEqualTo("越来越重视,这件事");
    }

    /** 版本号：判分落库与幂等哈希共用 norm-1 口径。 */
    @Test
    @DisplayName("版本号固定为 norm-1")
    void versionIsNorm1() {
        assertThat(Norm1Normalizer.VERSION).isEqualTo("norm-1");
    }
}
