package io.github.codejinxz.yilian.upload.support;

import io.github.codejinxz.yilian.upload.validation.ImportFileFormat;

/**
 * 通过上传策略校验后的文件描述。
 *
 * @param safeFilename 去掉客户端路径并做长度校验后的文件名，仅用于展示与格式判断
 * @param contentType 归一化后的 MIME 类型，如 {@code text/csv}
 * @param format 由扩展名决定的处理格式
 */
public record ImportFileDescriptor(
        String safeFilename, String contentType, ImportFileFormat format) {}
