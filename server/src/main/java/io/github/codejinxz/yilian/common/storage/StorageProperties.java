package io.github.codejinxz.yilian.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.File;
import java.nio.file.Path;

/**
 * 对象存储配置，前缀 {@code yilian.storage}。
 *
 * <p>默认值面向本机开发：本地根目录放在工作目录下的 {@code data/storage}，临时文件放在根目录内的 {@code .multipart}，公开访问前缀留空（不生成公开
 * URL）。生产环境通过环境变量覆盖，密钥类配置 不放在这里，云提供方的凭据由各自 SPI 实现从环境变量读取。
 *
 * <p>路径字段使用 {@link File} 而不是 {@link Path}：Spring Boot 的配置绑定只内置了 {@code String -> File} 转换器，没有
 * {@code String -> Path}，用 {@link Path} 会在启动时因缺少 转换器失败。真正使用路径时再转成 {@link Path}。
 *
 * @param provider 提供方，默认 {@link StorageProvider#LOCAL}
 * @param publicUrlPrefix 公开访问前缀，如 {@code https://cdn.example.com/media}；空串表示不生成公开 URL
 * @param local 本地文件系统参数
 */
@ConfigurationProperties(prefix = "yilian.storage")
public record StorageProperties(StorageProvider provider, String publicUrlPrefix, Local local) {

    /**
     * 补全默认值并去掉公开前缀末尾的斜杠，避免拼接出双斜杠。
     *
     * @throws IllegalArgumentException 公开前缀不是 http(s) 绝对地址或站内绝对路径时抛出
     */
    public StorageProperties {
        provider = provider == null ? StorageProvider.LOCAL : provider;
        publicUrlPrefix = normalizePublicUrlPrefix(publicUrlPrefix);
        local = local == null ? new Local(null, null) : local;
    }

    /**
     * 本地文件系统参数。路径可以是相对路径（相对进程工作目录），启动时统一转成绝对路径。
     *
     * @param root 对象根目录，默认 {@code ./data/storage}
     * @param tempDir 临时目录，默认 {@code <root>/.multipart}；不得等于或包含根目录
     */
    public record Local(File root, File tempDir) {

        /** 本地对象根目录的默认值。 */
        private static final File DEFAULT_ROOT = new File("./data/storage");

        /**
         * 补全默认临时目录。
         *
         * @throws IllegalArgumentException 临时目录等于或包含对象根目录时抛出，否则临时文件会暴露为对象
         */
        public Local {
            root = root == null ? DEFAULT_ROOT : root;
            tempDir = tempDir == null ? new File(root, ".multipart") : tempDir;
            Path normalizedRoot = root.toPath().toAbsolutePath().normalize();
            Path normalizedTemp = tempDir.toPath().toAbsolutePath().normalize();
            if (normalizedTemp.equals(normalizedRoot)
                    || normalizedRoot.startsWith(normalizedTemp)) {
                throw new IllegalArgumentException(
                        "yilian.storage.local.temp-dir 不能等于或包含 local.root");
            }
        }
    }

    /**
     * 校验并归一化公开访问前缀：允许完整的 http(s) 地址或站内绝对路径，统一去掉末尾斜杠。
     *
     * @param prefix 原始前缀，允许为 null
     * @return 归一化后的前缀；null 或空白返回空串
     */
    private static String normalizePublicUrlPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String normalized = prefix.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("http://")
                && !normalized.startsWith("https://")
                && !normalized.startsWith("/")) {
            throw new IllegalArgumentException(
                    "yilian.storage.public-url-prefix 必须是 http(s) 地址或以 / 开头的站内路径");
        }
        return normalized;
    }
}
