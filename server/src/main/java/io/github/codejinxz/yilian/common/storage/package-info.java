/**
 * 对象存储抽象：统一本地文件系统与云对象存储的读写、预签名与分片上传接口。
 *
 * <p>本包只定义接口、DTO、配置和本地实现，<strong>不引入任何第三方云 SDK</strong>。腾讯 COS、阿里 OSS、 AWS S3、Cloudflare R2
 * 等提供方由后续模块实现 {@link io.github.codejinxz.yilian.common.storage.StorageProviderSpi}，以 SPI
 * 方式注册，保持核心依赖干净。 调用方拿到的始终是 {@link io.github.codejinxz.yilian.common.storage.ObjectStorage} 接口，不感知
 * 具体提供方；下载接口写入调用方给的 {@code OutputStream}，不把整个对象读进内存。
 */
package io.github.codejinxz.yilian.common.storage;
