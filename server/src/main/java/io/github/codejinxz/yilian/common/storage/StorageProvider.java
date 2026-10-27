package io.github.codejinxz.yilian.common.storage;

/**
 * 对象存储提供方。
 *
 * <p>枚举值只表达「配置里能写什么」，不代表当前模块已经支持：本模块只实现 {@link #LOCAL}， 云提供方需要在后续模块中实现 {@link StorageProviderSpi}
 * 并引入对应 SDK。这样配置可以先按环境 写好，真正的接入不会污染核心依赖。
 */
public enum StorageProvider {

    /** 本地文件系统，开发与单机部署使用；不依赖任何云服务。 */
    LOCAL,

    /** 腾讯云 COS。 */
    TENCENT_COS,

    /** 阿里云 OSS。 */
    ALI_OSS,

    /** AWS S3 及兼容 S3 协议的服务。 */
    S3,

    /** Cloudflare R2。 */
    R2;

    /**
     * 是否为当前模块自带实现的提供方。
     *
     * @return 仅 {@link #LOCAL} 返回 true
     */
    public boolean implementedInModule() {
        return this == LOCAL;
    }
}
