package io.github.codejinxz.yilian.practice.llm;

import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/**
 * OpenAI Chat Completions 协议实现（017 引入为 SpringAiLlmClient，022 收编更名）：服务智谱/DeepSeek 等 OpenAI
 * 兼容端点（spring-ai openai 路径）。
 *
 * <p>Spring AI 2.0 的 OpenAI 模型底层是 OpenAI 官方 Java SDK（{@code com.openai:openai-java-core}）， 这里手工组装
 * {@link ClientOptions}（端点/密钥/超时/不重试）后包一层 {@link OpenAiChatModel}， 不走 starter 自动配置（application.yml
 * 已把 {@code spring.ai.model.*} 全部置 none）。重试显式置 0：答疑超时 15s 已是护栏，SDK 层自动重试会把 最坏等待翻倍，失败语义统一交给 {@code
 * TutorServiceImpl} 降级（openai 两轨无网关冷却特性，不做应用层退避重试）。
 *
 * <p>拼接口径：{@code baseUrl} 填到 {@code /v4} 级前缀（如智谱 {@code https://open.bigmodel.cn/api/paas/v4}），
 * {@code /chat/completions} 由 SDK 拼。温度取 0.3：答疑要求克制的解释性输出，不做发散创作。输出上限 {@link
 * LlmChatClient#MAX_OUTPUT_TOKENS}（022 起三协议同源 2048）：显式封顶避免异常长回复把单次成本放大。
 *
 * <p>装配（022）：由 {@code LlmClientRegistry} 按激活 provider（或 legacy 环境变量）构造，凭证口径 = baseUrl + apiKey 双必填。
 */
public class OpenAiChatLlmClient implements LlmChatClient {

    private final ChatClient chatClient;

    /**
     * 按端点参数组装模型客户端；只做本地对象构造，不发任何网络请求。
     *
     * @param endpoint 已过齐备性校验的端点参数（openai-chat 轨 apiKey 必填）
     */
    public OpenAiChatLlmClient(LlmEndpoint endpoint) {
        ClientOptions options =
                new ClientOptions.Builder()
                        .httpClient(
                                SpringAiOpenAiHttpClient.builder()
                                        .timeout(endpoint.timeout())
                                        .build())
                        .baseUrl(endpoint.baseUrl())
                        .apiKey(endpoint.apiKey())
                        .timeout(endpoint.timeout())
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
                                        .model(endpoint.model())
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
