package io.github.codejinxz.yilian.upload.dto;

import java.io.InputStream;

/**
 * 一次导入上传的输入。
 *
 * <p>{@code content} 由 Controller 从 multipart 请求打开，生命周期只覆盖本次 {@link
 * io.github.codejinxz.yilian.upload.service.ImportBatchService#upload(ImportUploadCommand)}
 * 调用；Service 只把它流式写入对象存储，不关闭也不缓存整个文件。文件名已经过 路径剥离前的原始值，安全校验与 MIME 归一化由导入策略统一完成。
 *
 * @param originalFilename 客户端提交的原始文件名，允许为 null，由策略层拒绝
 * @param contentType 客户端声明的 MIME 类型，允许带 charset 参数
 * @param sizeBytes 文件字节数；未知时为负数，由策略层拒绝
 * @param content 文件内容流，调用方负责关闭
 * @param createdBy 上传管理员用户名，用于审计展示
 */
public record ImportUploadCommand(
        String originalFilename,
        String contentType,
        long sizeBytes,
        InputStream content,
        String createdBy) {}
