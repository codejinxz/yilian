package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link TutorLlmConfiguration} 装配选择单测 [AC-1]：YILIAN_LLM_PROVIDER 决定客户端实现（缺省 openai 向后兼容； anthropic
 * 走 ZCode 网关轨且 key 可空），凭证不齐时不装配任何 bean（三态之「功能不存在」分界）。
 */
class TutorLlmConfigurationTest {

    private final TutorLlmConfiguration configuration = new TutorLlmConfiguration();
    private final TutorLlmConfiguration.CredentialsPresentCondition condition =
            new TutorLlmConfiguration.CredentialsPresentCondition();

    /** [AC-1] provider 缺省：base-url+api-key 齐备装配 OpenAI 轨（017 语义不变）。 */
    @Test
    void assemblesOpenaiClientByDefault_ac1() {
        StandardEnvironment environment =
                env(
                        "yilian.llm.base-url", "https://open.bigmodel.cn/api/paas/v4",
                        "yilian.llm.api-key", "k");

        assertThat(conditionMatches(environment)).isTrue();
        assertThat(configuration.llmChatClient(environment)).isInstanceOf(SpringAiLlmClient.class);
    }

    /** [AC-1] provider=anthropic：仅 base-url（无 key）即装配 Anthropic 轨客户端。 */
    @Test
    void assemblesAnthropicClientWithBaseUrlOnly_ac1() {
        StandardEnvironment environment =
                env(
                        "yilian.llm.provider", "anthropic",
                        "yilian.llm.base-url", "http://127.0.0.1:47860/gateway/zcode");

        assertThat(conditionMatches(environment)).isTrue();
        assertThat(configuration.llmChatClient(environment))
                .isInstanceOf(AnthropicMessagesLlmClient.class);
    }

    /** [AC-1] 凭证不齐不装配：openai 缺 key、anthropic 缺 base-url，容器里都不应有 bean。 */
    @Test
    void noBeanWhenCredentialsMissing_ac1() {
        assertThat(
                        conditionMatches(
                                env("yilian.llm.base-url", "https://open.bigmodel.cn/api/paas/v4")))
                .isFalse();
        assertThat(conditionMatches(env("yilian.llm.provider", "anthropic"))).isFalse();
        assertThat(conditionMatches(new StandardEnvironment())).isFalse();
    }

    /** [AC-1] anthropic 轨离线构造可完成（无网络请求），且装配即绑定网关 baseUrl。 */
    @Test
    void anthropicClientBuildsOffline_ac1() {
        LlmProperties properties =
                new LlmProperties(
                        "http://127.0.0.1:47860/gateway/zcode",
                        "",
                        "glm-5.3",
                        null,
                        LlmProperties.LlmProvider.ANTHROPIC);

        assertThat(new AnthropicMessagesLlmClient(properties)).isNotNull();
    }

    /**
     * 构造带属性表的环境。
     *
     * @param pairs key/value 交替的属性表
     * @return 环境实例
     */
    private static StandardEnvironment env(String... pairs) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> source = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            source.put(pairs[i], pairs[i + 1]);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("test", source));
        return environment;
    }

    /**
     * 以最小 ConditionContext 评估凭证条件。
     *
     * @param environment 环境
     * @return 条件命中返回 true
     */
    private boolean conditionMatches(StandardEnvironment environment) {
        return condition.matches(
                new ConditionContext() {
                    @Override
                    public org.springframework.beans.factory.support.BeanDefinitionRegistry
                            getRegistry() {
                        return null;
                    }

                    @Override
                    public org.springframework.beans.factory.config.ConfigurableListableBeanFactory
                            getBeanFactory() {
                        return null;
                    }

                    @Override
                    public ClassLoader getClassLoader() {
                        return null;
                    }

                    @Override
                    public org.springframework.core.env.Environment getEnvironment() {
                        return environment;
                    }

                    @Override
                    public org.springframework.core.io.ResourceLoader getResourceLoader() {
                        return null;
                    }
                },
                (AnnotatedTypeMetadata) () -> null);
    }
}
