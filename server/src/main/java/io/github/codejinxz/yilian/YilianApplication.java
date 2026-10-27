package io.github.codejinxz.yilian;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 译练后端启动类。
 *
 * <p>{@link ConfigurationPropertiesScan} 让各模块的配置记录（JWT、bootstrap 管理员等）无需逐个
 * {@code @EnableConfigurationProperties} 即可绑定；这些配置的默认值与合法性校验放在各自的记录或 配置类里，启动失败即视为部署配置有问题，不静默降级。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class YilianApplication {

    /**
     * 进程入口，交给 Spring Boot 完成容器启动与 profile 解析。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(YilianApplication.class, args);
    }
}
