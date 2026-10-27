package io.github.codejinxz.yilian.common.storage;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 按配置选择对象存储实现。
 *
 * <p>{@link StorageProvider#LOCAL} 由本模块直接构造；其余提供方从容器中查找 {@link StorageProviderSpi}
 * 实现。没有对应实现时启动失败并给出可操作的提示，避免线上把文件写到 临时目录后才暴露问题。第三方 SDK 不在本模块依赖中，接入方只需新增实现类并注册为 Bean。
 */
@Component
public class ObjectStorageFactory {

    private final List<StorageProviderSpi> providers;

    /**
     * 收集容器中所有云存储 SPI 实现；没有实现时得到空列表，不影响本地实现。
     *
     * @param providers SPI 实现列表，不能为 null
     */
    public ObjectStorageFactory(List<StorageProviderSpi> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
    }

    /**
     * 创建与配置匹配的对象存储。
     *
     * @param properties 存储配置
     * @return 对象存储实现
     * @throws IllegalStateException 配置了尚未接入的提供方时抛出
     */
    public ObjectStorage create(StorageProperties properties) {
        Objects.requireNonNull(properties, "properties");
        if (properties.provider().implementedInModule()) {
            StorageProperties.Local local = properties.local();
            return new LocalObjectStorage(
                    local.root().toPath(), local.tempDir().toPath(), properties.publicUrlPrefix());
        }
        return providers.stream()
                .filter(spi -> spi.provider() == properties.provider())
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "存储提供方 "
                                                + properties.provider()
                                                + " 尚未接入：本模块不引入第三方 SDK，请实现"
                                                + " StorageProviderSpi 并注册为 Spring Bean"))
                .create(properties);
    }
}
