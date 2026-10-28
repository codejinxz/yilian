package io.github.codejinxz.yilian.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.upload.exception.ImportFileTooLargeException;
import io.github.codejinxz.yilian.upload.support.ImportFileDescriptor;
import io.github.codejinxz.yilian.upload.support.ImportFilePolicy;
import io.github.codejinxz.yilian.upload.validation.ImportFileFormat;

import org.junit.jupiter.api.Test;

/**
 * 上传策略的边界测试：扩展名、MIME、5MB 上限与文件名归一化。
 *
 * <p>这些用例不启动 Spring、不写磁盘，直接验证策略层；413/415 的 HTTP 映射由 preview 端点 （波次二落地）的集成测试覆盖。
 */
class ImportFilePolicyTest {

    /** .csv + text/csv 应通过并识别为 CSV。 */
    @Test
    void acceptsCsv() {
        ImportFileDescriptor descriptor = validate("sentences.csv", "text/csv", 10);

        assertThat(descriptor.format()).isEqualTo(ImportFileFormat.CSV);
        assertThat(descriptor.contentType()).isEqualTo("text/csv");
        assertThat(descriptor.safeFilename()).isEqualTo("sentences.csv");
    }

    /** .json + application/json 应通过并识别为 JSON Lines。 */
    @Test
    void acceptsJson() {
        ImportFileDescriptor descriptor = validate("sentences.json", "application/json", 10);

        assertThat(descriptor.format()).isEqualTo(ImportFileFormat.JSON_LINES);
    }

    /** .txt 即使 MIME 合法也必须拒绝，返回 415。 */
    @Test
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> validate("sentences.txt", "text/plain", 10))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.UNSUPPORTED_MEDIA_TYPE));
    }

    /** MIME 不在白名单时必须拒绝，返回 415。 */
    @Test
    void rejectsUnsupportedMime() {
        assertThatThrownBy(() -> validate("sentences.csv", "application/pdf", 10))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.UNSUPPORTED_MEDIA_TYPE));
    }

    /** 浏览器可能带 charset 参数，比较时只取 type/subtype。 */
    @Test
    void acceptsMimeWithCharsetParameter() {
        ImportFileDescriptor descriptor = validate("sentences.csv", "text/csv; charset=UTF-8", 10);

        assertThat(descriptor.contentType()).isEqualTo("text/csv");
    }

    /** 恰好 5MB 必须通过，边界不能因为使用 >= 而被误杀。 */
    @Test
    void acceptsExactlyFiveMegabytes() {
        assertThatCode(
                        () ->
                                validate(
                                        "sentences.csv",
                                        "text/csv",
                                        ImportFilePolicy.MAX_FILE_SIZE_BYTES))
                .doesNotThrowAnyException();
    }

    /** 超过 5MB 一个字节就应返回 413 专用异常。 */
    @Test
    void rejectsMoreThanFiveMegabytes() {
        long size = ImportFilePolicy.MAX_FILE_SIZE_BYTES + 1;

        assertThatThrownBy(() -> validate("sentences.csv", "text/csv", size))
                .isInstanceOfSatisfying(
                        ImportFileTooLargeException.class,
                        exception -> {
                            assertThat(exception.actualBytes()).isEqualTo(size);
                            assertThat(exception.maxBytes())
                                    .isEqualTo(ImportFilePolicy.MAX_FILE_SIZE_BYTES);
                        });
    }

    /** 空文件没有可导入内容，按 400 拒绝而不是 413。 */
    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> validate("sentences.csv", "text/csv", 0))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
    }

    /** 浏览器上传的 Windows 全路径只保留最后一段，且扩展名大小写不敏感。 */
    @Test
    void stripsClientPathAndIgnoresExtensionCase() {
        ImportFileDescriptor descriptor = validate("C:\\fakepath\\sentences.CSV", "text/csv", 10);

        assertThat(descriptor.safeFilename()).isEqualTo("sentences.CSV");
        assertThat(descriptor.format()).isEqualTo(ImportFileFormat.CSV);
    }

    /** 文件名中的控制字符会污染日志与响应头，应直接拒绝。 */
    @Test
    void rejectsControlCharacterInFilename() {
        assertThatThrownBy(() -> validate("sentences\n.csv", "text/csv", 10))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
    }

    /**
     * 调用策略校验，参数顺序对齐浏览器 multipart 的常见读取顺序。
     *
     * @param filename 客户端文件名
     * @param contentType MIME
     * @param sizeBytes 字节数
     * @return 校验通过后的文件描述
     */
    private static ImportFileDescriptor validate(
            String filename, String contentType, long sizeBytes) {
        return ImportFilePolicy.validate(filename, sizeBytes, contentType);
    }
}
