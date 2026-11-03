package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link LlmProperties} 绑定与三态口径单测 [AC-2]：YILIAN_LLM_* 环境变量经 relaxed binding 映射、 model/timeout
 * 缺省兜底、hasCredentials 同时要求 base-url 与 api-key（功能不存在的判定依据）。
 */
class LlmPropertiesTest {

    /** [AC-2] 环境变量（kebab-case）绑定：值两端空白被规范化，超时字符串解析为 Duration。 */
    @Test
    void bindsEnvOverridesAndTrimsValues_ac2() {
        Map<String, Object> source = new HashMap<>();
        source.put("yilian.llm.base-url", " https://open.bigmodel.cn/api/paas/v4 ");
        source.put("yilian.llm.api-key", " sk-owner-plan-key ");
        source.put("yilian.llm.model", " glm-4.7 ");
        source.put("yilian.llm.timeout", "20s");

        LlmProperties properties = bind(source);

        assertThat(properties.baseUrl()).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(properties.apiKey()).isEqualTo("sk-owner-plan-key");
        assertThat(properties.model()).isEqualTo("glm-4.7");
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.hasCredentials()).isTrue();
    }

    /** [AC-2] 缺省兜底：未配置 model/timeout 时取 glm-4.7 / 15s，不出现 null 下游。 */
    @Test
    void fallsBackToDefaultModelAndTimeout_ac2() {
        Map<String, Object> source = new HashMap<>();
        source.put("yilian.llm.base-url", "https://open.bigmodel.cn/api/paas/v4");
        source.put("yilian.llm.api-key", "k");

        LlmProperties properties = bind(source);

        assertThat(properties.model()).isEqualTo(LlmProperties.DEFAULT_MODEL);
        assertThat(properties.timeout()).isEqualTo(LlmProperties.DEFAULT_TIMEOUT);
    }

    /** [AC-2] hasCredentials 口径：base-url 与 api-key 缺一即「功能不存在」；model 缺省不算缺配置。 */
    @Test
    void hasCredentialsRequiresBaseUrlAndKey_ac2() {
        assertThat(bind(Map.of("yilian.llm.api-key", "k")).hasCredentials()).isFalse();
        assertThat(bind(Map.of("yilian.llm.base-url", "https://x")).hasCredentials()).isFalse();
        assertThat(bind(Map.of()).hasCredentials()).isFalse();
        assertThat(
                        bind(Map.of("yilian.llm.base-url", "https://x", "yilian.llm.api-key", "k"))
                                .hasCredentials())
                .isTrue();
    }

    /** [AC-2] 非法/空白 timeout 回落缺省 15s，不让脏配置把调用拖成无限等待。 */
    @Test
    void fallsBackToDefaultTimeoutWhenInvalid_ac2() {
        assertThat(bind(Map.of("yilian.llm.timeout", "0s")).timeout())
                .isEqualTo(LlmProperties.DEFAULT_TIMEOUT);
        assertThat(bind(Map.of("yilian.llm.timeout", "-3s")).timeout())
                .isEqualTo(LlmProperties.DEFAULT_TIMEOUT);
    }

    /**
     * 以 Spring Binder 模拟环境变量绑定（与 @ConfigurationProperties 同一机制）。
     *
     * @param source 属性表
     * @return 绑定结果
     */
    private static LlmProperties bind(Map<String, Object> source) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", source));
        return Binder.get(environment)
                .bind("yilian.llm", Bindable.of(LlmProperties.class))
                .orElse(new LlmProperties(null, null, null, null));
    }
}
