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
 * <p>温度取 0.3：答疑要求克制的解释性输出，不做发散创作。输出上限 {@link #MAX_OUTPUT_TOKENS}： 答疑回答 ≤300 字（PRD
 * §5.4），显式封顶避免异常长回复把单次成本放大（017 R1 成本护栏）。
 */
public class SpringAiLlmClient implements LlmChatClient {

    /** 单次回复输出 token 上限（成本护栏）：约 400–500 个汉字，覆盖 300 字回答上限仍有余量。 */
    static final int MAX_OUTPUT_TOKENS = 768;

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
        // 同一组 ClientOptions 同时喂同步与异步客户端：Builder.build() 对未显式提供的 async
        // 客户端会经 OpenAiSetup 按默认环境凭据现拼（R1 修复），在无 OPENAI_API_KEY 的部署机上
        // 直接抛 IllegalStateException——必须两个都显式给定，才不会落到那条回退路径。
        OpenAIClientImpl client = new OpenAIClientImpl(options);
        OpenAiChatModel model =
                OpenAiChatModel.builder()
                        .openAiClient(client)
                        .openAiClientAsync(client.async())
                        .options(
                                OpenAiChatOptions.builder()
                                        .model(properties.model())
                                        .temperature(0.3)
                                        .maxTokens(MAX_OUTPUT_TOKENS)
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
