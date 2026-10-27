package io.github.codejinxz.yilian.common.storage;

import java.time.Instant;

/**
 * 对象元数据。
 *
 * <p>不含对象内容，避免把文件读进内存。{@code contentType}、{@code etag} 在不同提供方可能缺失 （本地实现不持久化上传时的 content
 * type，只能按扩展名探测），调用方不得把它们当作必填字段。
 *
 * @param key 对象键，相对路径、正斜杠分隔
 * @param size 字节数，不能为负
 * @param lastModified 最后修改时间；提供方不返回时为 null
 * @param contentType MIME 类型；无法判断时为 null
 * @param etag 提供方的对象摘要；本地实现与云实现格式可能不同，仅用于比较是否为同一版本
 * @param publicUrl 按公开访问前缀拼接的 URL；未配置前缀时为 null
 */
public record StorageObject(
        String key,
        long size,
        Instant lastModified,
        String contentType,
        String etag,
        String publicUrl) {

    /**
     * 校验元数据的基本不变量。
     *
     * @throws IllegalArgumentException 对象键为空或大小为负时抛出
     */
    public StorageObject {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        if (size < 0) {
            throw new IllegalArgumentException("对象大小不能为负数");
        }
    }
}
