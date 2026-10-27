package io.github.codejinxz.yilian.common.storage;

/**
 * 云对象存储提供方的 SPI。
 *
 * <p>核心模块刻意不引入腾讯 COS、阿里 OSS、AWS S3、Cloudflare R2 的 SDK：这些 SDK 体积大、传递依赖多，
 * 且多数场景只需其中一种。接入时在独立模块中实现本接口并注册为 Spring Bean，{@link ObjectStorageFactory} 会按 {@link
 * StorageProvider} 选择实现；未注册就配置对应提供方时启动失败，而不是运行期才发现。
 *
 * <p>实现方必须保证：
 *
 * <ul>
 *   <li>密钥只从环境变量或 secret 读取，不写入配置类、日志或异常消息；
 *   <li>{@link ObjectStorage#get} 以流式方式写出，不把整个对象读入内存；
 *   <li>分片上传的 {@code uploadId}、分片号与 etag 语义与 {@link MultipartUpload} 一致；
 *   <li>对象键沿用本包契约（相对路径、正斜杠、禁止 {@code ..} 与绝对路径）。
 * </ul>
 */
public interface StorageProviderSpi {

    /**
     * 本实现对应的提供方枚举。
     *
     * @return 提供方标识，不能为 null
     */
    StorageProvider provider();

    /**
     * 根据配置创建存储实现。
     *
     * @param properties 存储配置，包含根目录、公开访问前缀等
     * @return 可用的对象存储实现
     */
    ObjectStorage create(StorageProperties properties);
}
