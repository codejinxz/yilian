package io.github.codejinxz.yilian.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 全局时钟装配：统一以 {@link Clock} 注入取代散落的 {@code Instant.now()}， 调度类组件（如账号匿名化任务）与依赖时间的用例得以在测试中注入固定时钟。
 *
 * <p>UTC 口径与全库一致；测试可用 {@code @TestConfiguration} 提供 {@code @Primary} 固定时钟覆盖。
 */
@Configuration
public class TimeConfig {

    /**
     * 生产时钟：UTC 系统时钟。
     *
     * @return UTC Clock
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
