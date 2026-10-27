package io.github.codejinxz.yilian.upload.support;

import io.github.codejinxz.yilian.common.storage.MultipartUpload;
import io.github.codejinxz.yilian.common.storage.ObjectStorage;
import io.github.codejinxz.yilian.common.storage.StorageObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用内存对象存储，避免导入测试依赖本地磁盘或云服务。
 *
 * <p>只实现 get/put/stat/delete/exists；分片上传与预签名不属于导入链路，直接抛 {@link UnsupportedOperationException}。
 */
public final class InMemoryObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    private final AtomicInteger getCount = new AtomicInteger();

    /**
     * 写入对象并校验声明长度。
     *
     * @param key 对象键
     * @param content 内容流
     * @param contentLength 声明长度
     * @param contentType MIME 类型，仅记录不使用
     * @throws IOException 读取或长度不一致时抛出
     */
    @Override
    public void put(String key, InputStream content, long contentLength, String contentType)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        content.transferTo(buffer);
        byte[] bytes = buffer.toByteArray();
        if (contentLength >= 0 && bytes.length != contentLength) {
            throw new IOException("写入长度与声明不一致");
        }
        objects.put(key, bytes);
    }

    /**
     * 读取对象到目标流。
     *
     * @param key 对象键
     * @param target 目标流
     * @return 对象元数据
     * @throws IOException 对象不存在时抛出
     */
    @Override
    public StorageObject get(String key, OutputStream target) throws IOException {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new NoSuchFileException(key);
        }
        getCount.incrementAndGet();
        target.write(bytes);
        return new StorageObject(key, bytes.length, Instant.now(), null, null, null);
    }

    /**
     * 删除对象。
     *
     * @param key 对象键
     * @return 是否确实删除
     */
    @Override
    public boolean delete(String key) {
        return objects.remove(key) != null;
    }

    /**
     * 判断对象是否存在。
     *
     * @param key 对象键
     * @return 存在返回 true
     */
    @Override
    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    /**
     * 内存实现不支持预签名。
     *
     * @param key 对象键
     * @param validity 有效期
     * @return 空
     */
    @Override
    public Optional<String> presign(String key, Duration validity) {
        return Optional.empty();
    }

    /**
     * 内存实现不支持分片上传。
     *
     * @param key 对象键
     * @param contentType MIME 类型
     * @return 不会返回
     */
    @Override
    public MultipartUpload initiateMultipart(String key, String contentType) {
        throw new UnsupportedOperationException("测试存储不支持分片上传");
    }

    /**
     * 内存实现不支持分片上传。
     *
     * @param uploadId 会话 id
     * @param partNumber 分片号
     * @param content 分片内容
     * @param contentLength 分片长度
     * @return 不会返回
     */
    @Override
    public MultipartUpload.Part uploadPart(
            String uploadId, int partNumber, InputStream content, long contentLength) {
        throw new UnsupportedOperationException("测试存储不支持分片上传");
    }

    /**
     * 内存实现不支持分片上传。
     *
     * @param uploadId 会话 id
     * @param parts 分片列表
     * @return 不会返回
     */
    @Override
    public StorageObject completeMultipart(String uploadId, List<MultipartUpload.Part> parts) {
        throw new UnsupportedOperationException("测试存储不支持分片上传");
    }

    /**
     * 内存实现不支持分片上传。
     *
     * @param uploadId 会话 id
     */
    @Override
    public void abortMultipart(String uploadId) {
        throw new UnsupportedOperationException("测试存储不支持分片上传");
    }

    /**
     * 读取对象元数据。
     *
     * @param key 对象键
     * @return 对象元数据
     * @throws IOException 对象不存在时抛出
     */
    @Override
    public StorageObject stat(String key) throws IOException {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new NoSuchFileException(key);
        }
        return new StorageObject(key, bytes.length, Instant.now(), null, null, null);
    }

    /**
     * 对象被读取的次数，用于验证「已完成批次 resume 不重新读文件」。
     *
     * @return 读取次数
     */
    public int getCount() {
        return getCount.get();
    }

    /**
     * 直接读取对象字节，供测试断言。
     *
     * @param key 对象键
     * @return 对象内容，不存在时为 null
     */
    public byte[] object(String key) {
        return objects.get(key);
    }
}
