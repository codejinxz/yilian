package io.github.codejinxz.yilian.common.storage;

import java.time.Instant;
import java.util.List;

/**
 * 分片上传会话及其已完成的分片。
 *
 * <p>{@code uploadId} 是提供方返回的会话标识，调用方只负责透传，不解析其格式。{@code parts} 是
 * 已成功上传的分片快照，完成上传时按分片号升序拼接；分片内容本身不进入本记录，避免内存膨胀。
 *
 * @param uploadId 上传会话标识，不能为空
 * @param key 目标对象键
 * @param contentType 上传时声明的 MIME 类型，允许为 null
 * @param initiatedAt 会话创建时间
 * @param parts 已完成的分片，按分片号排序的不可变列表
 */
public record MultipartUpload(
        String uploadId, String key, String contentType, Instant initiatedAt, List<Part> parts) {

    /**
     * 归一化空值并复制分片列表，保证记录不可变。
     *
     * @throws IllegalArgumentException 会话标识或对象键为空时抛出
     */
    public MultipartUpload {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId 不能为空");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        initiatedAt = initiatedAt == null ? Instant.now() : initiatedAt;
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /**
     * 一个已完成的分片。
     *
     * @param partNumber 分片号，从 1 开始
     * @param etag 提供方返回的分片摘要，用于完成上传时校验
     * @param size 分片字节数，不能为负
     */
    public record Part(int partNumber, String etag, long size) {

        /**
         * 校验分片号与摘要。
         *
         * @throws IllegalArgumentException 分片号小于 1、etag 为空或大小为负时抛出
         */
        public Part {
            if (partNumber < 1) {
                throw new IllegalArgumentException("分片号必须从 1 开始");
            }
            if (etag == null || etag.isBlank()) {
                throw new IllegalArgumentException("分片 etag 不能为空");
            }
            if (size < 0) {
                throw new IllegalArgumentException("分片大小不能为负数");
            }
        }
    }
}
