package io.github.codejinxz.yilian.upload.validation;

import java.util.Locale;
import java.util.Optional;

/**
 * 导入文件格式，由扩展名决定，MIME 只作为白名单校验，不参与格式判断。
 *
 * <p>{@code .json} 按 JSON Lines 处理：每行一个 JSON 对象，不支持跨行的漂亮打印数组。 这样 5MB 文件可以流式逐行校验，不需要把整个文档读进内存。
 */
public enum ImportFileFormat {
    /** 逗号分隔文本；当前只做结构性校验，列 schema 留给后续业务校验器。 */
    CSV(".csv"),
    /** JSON Lines：每行一个 JSON 对象。 */
    JSON_LINES(".json");

    private final String extension;

    ImportFileFormat(String extension) {
        this.extension = extension;
    }

    /**
     * 文件扩展名，含前导点。
     *
     * @return {@code .csv} 或 {@code .json}
     */
    public String extension() {
        return extension;
    }

    /**
     * 按文件名后缀识别格式，大小写不敏感。
     *
     * @param filename 文件名，允许为 null
     * @return 识别出的格式，后缀不受支持时为空
     */
    public static Optional<ImportFileFormat> fromFilename(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        for (ImportFileFormat format : values()) {
            if (lower.endsWith(format.extension)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
