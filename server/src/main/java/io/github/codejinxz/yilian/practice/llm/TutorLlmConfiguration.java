package io.github.codejinxz.yilian.practice.llm;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 答疑 LLM 客户端装配（017 引入，021 起 provider 双轨）：凭证按 {@link LlmProperties#hasCredentials()} 口径齐备才建 {@link
 * LlmChatClient} bean，未配置时容器里没有该 bean，答疑走「功能不存在」分支—— 这是三态里「功能不存在」与「已配置未开启」的服务端分界。
 *
 * <p>021 协议族选择：{@code YILIAN_LLM_PROVIDER}（openai|anthropic，缺省 openai 向后兼容）决定装配 {@link
 * SpringAiLlmClient}（OpenAI 兼容，智谱/DeepSeek）还是 {@link AnthropicMessagesLlmClient}（Anthropic
 * Messages，服务主人的 ZCode 本地网关）。starter 的模型自动配置已在 application.yml 通过 {@code spring.ai.model.*=none}
 * 关闭（对两 provider 一并生效），本类是唯一的模型客户端来源。
 */
@Configuration
public class TutorLlmConfiguration {

    /**
     * 按环境里的 {@code yilian.llm} 配置决定是否装配客户端以及装配哪个协议实现。
     *
     * @param environment 环境配置
     * @return 凭证齐备时返回对应 provider 的客户端实例，否则不注册 bean
     */
    @Bean
    @Conditional(CredentialsPresentCondition.class)
    public LlmChatClient llmChatClient(Environment environment) {
        LlmProperties properties = bindProperties(environment);
        return switch (properties.provider()) {
            case ANTHROPIC -> new AnthropicMessagesLlmClient(properties);
            case OPENAI -> new SpringAiLlmClient(properties);
        };
    }

    /**
     * 从环境绑定 {@code yilian.llm} 配置；缺省字段由 record 规范化兜底。
     *
     * @param environment 环境配置
     * @return 绑定后的配置
     */
    private static LlmProperties bindProperties(Environment environment) {
        return Binder.get(environment)
                .bind("yilian.llm", Bindable.of(LlmProperties.class))
                .orElse(new LlmProperties("", "", null, null, null));
    }

    /**
     * 凭证存在性条件：与 {@link LlmProperties#hasCredentials()} 同口径——baseUrl 必填；apiKey 仅 openai
     * 轨必填（anthropic 轨服务无鉴权的本地网关，key 可空）。
     */
    static final class CredentialsPresentCondition implements Condition {

        @Override
        public boolean matches(
                ConditionContext context,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            return bindProperties(context.getEnvironment()).hasCredentials();
        }
    }
}
