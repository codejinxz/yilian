package io.github.codejinxz.yilian.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 对象存储统一接口。
 *
 * <p><strong>对象键契约</strong>：使用相对路径与正斜杠（如 {@code exercises/2026/10/abc.webp}）， 不以 {@code /} 开头，不含
 * {@code .}、{@code ..} 段、反斜杠、盘符或 NTFS 数据流冒号。实现必须拒绝 越界键，不能依赖调用方自觉。
 *
 * <p><strong>流式约定</strong>：{@link #put} 从 {@link InputStream} 读取，{@link #get} 写入调用方给的 {@link
 * OutputStream}，双方都不得把整个对象读进内存。单文件大小上限（当前业务为 5MB）由调用方在 进入本接口前校验，本接口不重复实现业务限制。
 *
 * <p><strong>错误约定</strong>：对象不存在时 {@link #get} 与 {@link #stat} 抛 {@link
 * java.nio.file.NoSuchFileException}；键非法抛 {@link IllegalArgumentException}；其余 IO 问题 抛 {@link
 * IOException}。云实现可以把 SDK 异常包装成 {@link IOException} 或运行时异常，但不得吞掉。
 */
public interface ObjectStorage {

    /**
     * 流式写入对象，已存在时覆盖。
     *
     * @param key 对象键
     * @param content 内容流；方法不会关闭它，由调用方负责
     * @param contentLength 声明长度，未知时传 -1；已知时实现应校验实际写入长度
     * @param contentType MIME 类型，允许为 null
     * @throws IOException 读写失败或实际长度与声明不一致时抛出
     */
    void put(String key, InputStream content, long contentLength, String contentType)
            throws IOException;

    /**
     * 流式读取对象到调用方输出流，返回对象元数据。
     *
     * <p>本方法不关闭 {@code target}，便于调用方在同一个响应流里继续写其他内容。
     *
     * @param key 对象键
     * @param target 目标输出流
     * @return 对象元数据
     * @throws java.nio.file.NoSuchFileException 对象不存在时抛出
     * @throws IOException 读取失败时抛出
     */
    StorageObject get(String key, OutputStream target) throws IOException;

    /**
     * 删除对象；对象不存在时不报错。
     *
     * @param key 对象键
     * @return 确实删除了对象返回 true，对象本就不存在返回 false
     * @throws IllegalArgumentException 键非法时抛出
     */
    boolean delete(String key);

    /**
     * 判断对象是否存在。
     *
     * @param key 对象键
     * @return 存在返回 true
     * @throws IllegalArgumentException 键非法时抛出
     */
    boolean exists(String key);

    /**
     * 生成限时访问 URL；不支持预签名的实现（如本地）返回空。
     *
     * @param key 对象键
     * @param validity 有效期，不能为负或零
     * @return 可公开访问的 URL，或 empty 表示需要走鉴权下载
     * @throws IllegalArgumentException 键非法或有效期为非正数时抛出
     */
    Optional<String> presign(String key, Duration validity);

    /**
     * 初始化分片上传会话。
     *
     * @param key 目标对象键
     * @param contentType MIME 类型，允许为 null
     * @return 新建的上传会话，分片列表为空
     * @throws IOException 无法创建会话资源时抛出
     */
    MultipartUpload initiateMultipart(String key, String contentType) throws IOException;

    /**
     * 上传单个分片；同一分片号重复上传时以最后一次为准。
     *
     * @param uploadId 上传会话标识
     * @param partNumber 分片号，从 1 开始
     * @param content 分片内容流；方法不会关闭它
     * @param contentLength 声明长度，未知时传 -1
     * @return 分片号、etag 与大小的记录
     * @throws IOException 读写失败或长度不一致时抛出
     */
    MultipartUpload.Part uploadPart(
            String uploadId, int partNumber, InputStream content, long contentLength)
            throws IOException;

    /**
     * 按分片号升序合并分片并完成上传。
     *
     * @param uploadId 上传会话标识
     * @param parts 调用方确认的分片列表；实现应校验分片确实已上传且 etag 一致
     * @return 合并后的对象元数据
     * @throws IOException 合并或校验失败时抛出
     */
    StorageObject completeMultipart(String uploadId, List<MultipartUpload.Part> parts)
            throws IOException;

    /**
     * 中止上传并释放临时资源；会话不存在时幂等返回。
     *
     * @param uploadId 上传会话标识
     */
    void abortMultipart(String uploadId);

    /**
     * 读取对象元数据，不读取内容。
     *
     * @param key 对象键
     * @return 对象元数据
     * @throws java.nio.file.NoSuchFileException 对象不存在时抛出
     * @throws IOException 读取失败时抛出
     */
    StorageObject stat(String key) throws IOException;
}
