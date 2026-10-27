package io.github.codejinxz.yilian.upload.support;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.upload.dto.ImportUploadCommand;
import io.github.codejinxz.yilian.upload.exception.ImportFileTooLargeException;
import io.github.codejinxz.yilian.upload.validation.ImportFileFormat;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 导入上传策略：扩展名、MIME 白名单与 5MB 上限。
 *
 * <p>校验顺序固定为「文件名 → 扩展名 → 大小 → MIME」，所有异常都在写对象存储之前抛出， 不会产生孤儿文件。扩展名与 MIME 都合法才允许进入处理阶段；MIME 允许带
 * {@code charset} 参数，但比较时只取 {@code type/subtype} 并转小写。
 *
 * <p>文件名只保留最后一段，防止把 {@code ..\..\} 或绝对路径写进展示字段；对象键完全由 服务端生成（UUID + 扩展名），不复用用户文件名，从根上避免路径穿越与覆盖。
 */
public final class ImportFilePolicy {

    /** 单文件上限 5MB，与 PRD 验收项「导入文件上限 5MB」一致。 */
    public static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;

    /** 原始文件名字段长度，与 {@code import_batch.original_filename} 对齐。 */
    public static final int MAX_FILENAME_LENGTH = 255;

    /** MIME 白名单；只允许这三种，避免浏览器把任意二进制伪装成导入文件。 */
    private static final Set<String> ALLOWED_MEDIA_TYPES =
            Set.of("text/csv", "application/json", "text/plain");

    private ImportFilePolicy() {}

    /**
     * 校验上传元数据并返回后续处理所需的描述。
     *
     * @param command 上传输入；只读取元数据，不消费内容流
     * @return 安全文件名、归一化 MIME 与文件格式
     * @throws BusinessException 文件名为空/过长/含控制字符、扩展名不合法、文件为空时抛出； 扩展名或 MIME 不受支持时使用 415 业务码
     * @throws ImportFileTooLargeException 文件超过 5MB 时抛出
     */
    public static ImportFileDescriptor validate(ImportUploadCommand command) {
        Objects.requireNonNull(command, "command");
        String filename = normalizeFilename(command.originalFilename());
        ImportFileFormat format =
                ImportFileFormat.fromFilename(filename)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ApiCode.UNSUPPORTED_MEDIA_TYPE,
                                                "仅支持 .csv 或 .json 文件"));
        if (command.sizeBytes() <= 0) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "导入文件不能为空");
        }
        if (command.sizeBytes() > MAX_FILE_SIZE_BYTES) {
            throw new ImportFileTooLargeException(command.sizeBytes(), MAX_FILE_SIZE_BYTES);
        }
        String contentType = normalizeContentType(command.contentType());
        return new ImportFileDescriptor(filename, contentType, format);
    }

    /**
     * 去掉客户端路径并校验文件名安全性与长度。
     *
     * @param originalFilename 原始文件名
     * @return 可直接展示的安全文件名
     * @throws BusinessException 文件名为空白、过长或包含控制字符时抛出
     */
    private static String normalizeFilename(String originalFilename) {
        if (StringUtils.isBlank(originalFilename)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "缺少文件名");
        }
        String name = originalFilename.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        name = name.strip();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "文件名不合法");
        }
        if (name.length() > MAX_FILENAME_LENGTH) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "文件名最多 255 个字符");
        }
        for (int index = 0; index < name.length(); index++) {
            char current = name.charAt(index);
            if (current < 0x20 || current == 0x7f) {
                throw new BusinessException(ApiCode.VALIDATION_FAILED, "文件名包含非法字符");
            }
        }
        return name;
    }

    /**
     * 校验并归一化 MIME 类型。
     *
     * @param rawContentType 客户端声明的 Content-Type，允许带参数
     * @return 小写的 {@code type/subtype}
     * @throws BusinessException 缺失、无法解析或不在白名单时抛出 415
     */
    private static String normalizeContentType(String rawContentType) {
        if (StringUtils.isBlank(rawContentType)) {
            throw unsupportedMediaType();
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(rawContentType);
            String normalized =
                    (mediaType.getType() + "/" + mediaType.getSubtype()).toLowerCase(Locale.ROOT);
            if (!ALLOWED_MEDIA_TYPES.contains(normalized)) {
                throw unsupportedMediaType();
            }
            return normalized;
        } catch (InvalidMediaTypeException exception) {
            throw unsupportedMediaType();
        }
    }

    private static BusinessException unsupportedMediaType() {
        return new BusinessException(
                ApiCode.UNSUPPORTED_MEDIA_TYPE, "仅支持 text/csv、application/json 或 text/plain 类型的文件");
    }
}
