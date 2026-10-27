package io.github.codejinxz.yilian.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.upload.dto.ImportUploadCommand;
import io.github.codejinxz.yilian.upload.exception.ImportFileTooLargeException;
import io.github.codejinxz.yilian.upload.support.ImportFileDescriptor;
import io.github.codejinxz.yilian.upload.support.ImportFilePolicy;
import io.github.codejinxz.yilian.upload.validation.ImportFileFormat;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

/**
 * 上传策略的边界测试：扩展名、MIME、5MB 上限与文件名归一化。
 *
 * <p>这些用例不启动 Spring、不写磁盘，直接验证策略层；Controller 还会通过 MockMvc 覆盖 413/415 的 HTTP 映射。
 */
class ImportFilePolicyTest {

    /** .csv + text/csv 应通过并识别为 CSV。 */
    @Test
    void acceptsCsv() {
        ImportFileDescriptor descriptor =
                ImportFilePolicy.validate(command("sentences.csv", "text/csv", 10));

        assertThat(descriptor.format()).isEqualTo(ImportFileFormat.CSV);
        assertThat(descriptor.contentType()).isEqualTo("text/csv");
        assertThat(descriptor.safeFilename()).isEqualTo("sentences.csv");
    }

    /** .json + application/json 应通过并识别为 JSON Lines。 */
    @Test
    void acceptsJson() {
        ImportFileDescriptor descriptor =
                ImportFilePolicy.validate(command("sentences.json", "application/json", 10));

        assertThat(descriptor.format()).isEqualTo(ImportFileFormat.JSON_LINES);
    }

    /** .txt 即使 MIME 合法也必须拒绝，返回 415。 */
    @Test
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(
                        () -> ImportFilePolicy.validate(command("sentences.txt", "text/plain", 10)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.UNSUPPORTED_MEDIA_TYPE));
    }

    /** MIME 不在白名单时必须拒绝，返回 415。 */
    @Test
    void rejectsUnsupportedMime() {
        assertThatThrownBy(
                        () ->
                                ImportFilePolicy.validate(
                                        command("sentences.csv", "application/pdf", 10)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.UNSUPPORTED_MEDIA_TYPE));
    }

    /** 浏览器可能带 charset 参数，比较时只取 type/subtype。 */
    @Test
    void acceptsMimeWithCharsetParameter() {
        ImportFileDescriptor descriptor =
                ImportFilePolicy.validate(command("sentences.csv", "text/csv; charset=UTF-8", 10));

        assertThat(descriptor.contentType()).isEqualTo("text/csv");
    }

    /** 恰好 5MB 必须通过，边界不能因为使用 >= 而被误杀。 */
    @Test
    void acceptsExactlyFiveMegabytes() {
        assertThatCode(
                        () ->
                                ImportFilePolicy.validate(
                                        command(
                                                "sentences.csv",
                                                "text/csv",
                                                ImportFilePolicy.MAX_FILE_SIZE_BYTES)))
                .doesNotThrowAnyException();
    }

    /** 超过 5MB 一个字节就应返回 413 专用异常。 */
    @Test
    void rejectsMoreThanFiveMegabytes() {
        long size = ImportFilePolicy.MAX_FILE_SIZE_BYTES + 1;

        assertThatThrownBy(
                        () -> ImportFilePolicy.validate(command("sentences.csv", "text/csv", size)))
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
        assertThatThrownBy(() -> ImportFilePolicy.validate(command("sentences.csv", "text/csv", 0)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
    }

    /** 浏览器上传的 Windows 全路径只保留最后一段，且扩展名大小写不敏感。 */
    @Test
    void stripsClientPathAndIgnoresExtensionCase() {
        ImportFileDescriptor descriptor =
                ImportFilePolicy.validate(command("C:\\fakepath\\sentences.CSV", "text/csv", 10));

        assertThat(descriptor.safeFilename()).isEqualTo("sentences.CSV");
        assertThat(descriptor.format()).isEqualTo(ImportFileFormat.CSV);
    }

    /** 文件名中的控制字符会污染日志与响应头，应直接拒绝。 */
    @Test
    void rejectsControlCharacterInFilename() {
        assertThatThrownBy(
                        () -> ImportFilePolicy.validate(command("sentences\n.csv", "text/csv", 10)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
    }

    /**
     * 构造只含元数据、不消费内容的上传命令。
     *
     * @param filename 文件名
     * @param contentType MIME
     * @param sizeBytes 字节数
     * @return 上传命令
     */
    private static ImportUploadCommand command(
            String filename, String contentType, long sizeBytes) {
        return new ImportUploadCommand(
                filename, contentType, sizeBytes, InputStream.nullInputStream(), "admin");
    }
}
