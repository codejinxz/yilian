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
 * 答疑 LLM 客户端装配（017）：凭证齐备才建 {@link LlmChatClient} bean，未配置时容器里没有该 bean，
 * 答疑走「功能不存在」分支——这是三态里「功能不存在」与「已配置未开启」的服务端分界。
 *
 * <p>starter 的 OpenAI 自动配置已在 application.yml 通过 {@code spring.ai.model.*=none} 关闭， 避免它在缺省凭证下自行拼装模型
 * bean；本类是唯一的模型客户端来源。
 */
@Configuration
public class TutorLlmConfiguration {

    /**
     * 按环境里的 {@code yilian.llm} 配置决定是否装配客户端。
     *
     * @param environment 环境配置
     * @return 凭证齐备时返回客户端实例，否则不注册 bean
     */
    @Bean
    @Conditional(CredentialsPresentCondition.class)
    public LlmChatClient llmChatClient(Environment environment) {
        return new SpringAiLlmClient(bindProperties(environment));
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
                .orElse(new LlmProperties("", "", null, null));
    }

    /** 凭证存在性条件：baseUrl 与 apiKey 都非空才放行（与 {@link LlmProperties#hasCredentials()} 同口径）。 */
    static final class CredentialsPresentCondition implements Condition {

        @Override
        public boolean matches(
                ConditionContext context,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            return bindProperties(context.getEnvironment()).hasCredentials();
        }
    }
}
