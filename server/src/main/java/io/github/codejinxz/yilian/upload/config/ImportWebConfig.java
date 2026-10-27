package io.github.codejinxz.yilian.upload.config;

import io.github.codejinxz.yilian.upload.support.ImportFilePolicy;

import jakarta.servlet.MultipartConfigElement;

import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * 导入接口的 multipart 限制。
 *
 * <p>Spring Boot 默认单文件 1MB，必须显式覆盖到 5MB，否则合法文件会在进入 Controller 之前被拒。 这里提供 {@link
 * MultipartConfigElement} Bean，Boot 的 Multipart 自动配置检测到已有 Bean 后 会直接采用；请求总大小留 1MB 余量给 boundary
 * 与表单头。Controller 与 Service 仍会再校验一次 文件大小，MockMvc 独立测试不会经过 Servlet 容器，两层校验都保留。
 */
@Configuration(proxyBeanMethods = false)
public class ImportWebConfig {

    /** 请求体余量：multipart boundary、Content-Disposition 等开销不会超过 1MB。 */
    private static final long REQUEST_OVERHEAD_BYTES = 1024L * 1024;

    /**
     * 单文件 5MB、总请求 6MB；阈值设为 0 让 Tomcat 直接落临时文件，避免大文件占用堆内存。
     *
     * @return multipart 配置
     */
    @Bean
    public MultipartConfigElement importMultipartConfig() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(ImportFilePolicy.MAX_FILE_SIZE_BYTES));
        factory.setMaxRequestSize(
                DataSize.ofBytes(ImportFilePolicy.MAX_FILE_SIZE_BYTES + REQUEST_OVERHEAD_BYTES));
        factory.setFileSizeThreshold(DataSize.ofBytes(0));
        return factory.createMultipartConfig();
    }
}
