package io.github.codejinxz.yilian.practice.llm;

import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/**
 * 基于 Spring AI ChatClient 的 OpenAI 兼容实现（017）。
 *
 * <p>Spring AI 2.0 的 OpenAI 模型底层是 OpenAI 官方 Java SDK（{@code com.openai:openai-java-core}）， 这里手工组装
 * {@link ClientOptions}（端点/密钥/超时/不重试）后包一层 {@link OpenAiChatModel}， 不走 starter 自动配置（application.yml
 * 已把 {@code spring.ai.model.*} 全部置 none）——凭证未配置时本类不被装配， 避免启动期拼出一个空凭证的模型 bean。重试显式置 0：答疑超时 15s
 * 已是护栏，SDK 层自动重试会把 最坏等待翻倍，失败语义统一交给 {@code TutorServiceImpl} 降级。
 *
 * <p>温度取 0.3：答疑要求克制的解释性输出，不做发散创作。
 */
public class SpringAiLlmClient implements LlmChatClient {

    private final ChatClient chatClient;

    /**
     * 按配置组装模型客户端；只做本地对象构造，不发任何网络请求。
     *
     * @param properties 已校验非空的 LLM 配置
     */
    public SpringAiLlmClient(LlmProperties properties) {
        ClientOptions options =
                new ClientOptions.Builder()
                        .httpClient(
                                SpringAiOpenAiHttpClient.builder()
                                        .timeout(properties.timeout())
                                        .build())
                        .baseUrl(properties.baseUrl())
                        .apiKey(properties.apiKey())
                        .timeout(properties.timeout())
                        .maxRetries(0)
                        .build();
        OpenAiChatModel model =
                OpenAiChatModel.builder()
                        .openAiClient(new OpenAIClientImpl(options))
                        .options(
                                OpenAiChatOptions.builder()
                                        .model(properties.model())
                                        .temperature(0.3)
                                        .maxRetries(0)
                                        .build())
                        .build();
        this.chatClient = ChatClient.create(model);
    }

    @Override
    public Reply ask(String systemPrompt, String userPrompt) {
        ChatResponse response =
                chatClient.prompt().system(systemPrompt).user(userPrompt).call().chatResponse();
        String content =
                response.getResult() == null || response.getResult().getOutput() == null
                        ? null
                        : response.getResult().getOutput().getText();
        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            promptTokens = response.getMetadata().getUsage().getPromptTokens();
            completionTokens = response.getMetadata().getUsage().getCompletionTokens();
        }
        return new Reply(
                content,
                promptTokens == null ? 0 : promptTokens,
                completionTokens == null ? 0 : completionTokens);
    }
}
