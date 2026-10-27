package io.github.codejinxz.yilian.common.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储装配：默认提供 {@link StorageProvider#LOCAL}，云提供方通过 SPI 扩展。
 *
 * <p>配置属性由启动类的 {@code @ConfigurationPropertiesScan} 绑定；本类只负责把配置翻译成 {@link ObjectStorage}
 * Bean，不读取环境变量、不创建目录，目录在首次写入时按需创建。
 */
@Configuration
public class StorageConfig {

    /**
     * 全局唯一的对象存储入口。
     *
     * @param properties 存储配置
     * @param factory 提供方工厂
     * @return 对象存储实现
     */
    @Bean
    public ObjectStorage objectStorage(StorageProperties properties, ObjectStorageFactory factory) {
        return factory.create(properties);
    }
}
