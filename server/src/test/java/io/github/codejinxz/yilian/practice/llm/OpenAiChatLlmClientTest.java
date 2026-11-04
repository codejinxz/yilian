package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * {@link OpenAiChatLlmClient} 装配单测（017 引入为 SpringAiLlmClient，022 收编更名）：手工组装路径 （ClientOptions/OpenAI
 * SDK/ChatClient）在离线环境只做本地构造不发请求；输出 token 上限三协议同源（022 起单一定义 在 {@link
 * LlmChatClient#MAX_OUTPUT_TOKENS}，2048 起步——思考模型的 thinking 计入 max_tokens）。
 */
class OpenAiChatLlmClientTest {

    /** [AC-1] 凭证齐备的配置可离线完成客户端装配（starter 自动配置关闭后的唯一 OpenAI 兼容路径来源之一）。 */
    @Test
    void buildsClientOfflineWithDummyCredentials_ac1() {
        LlmEndpoint endpoint =
                new LlmEndpoint("https://open.bigmodel.cn/api/paas/v4", "dummy-key", null, null);

        assertThatCode(() -> new OpenAiChatLlmClient(endpoint)).doesNotThrowAnyException();
    }

    /** [AC-1] 输出上限三协议同源且 ≥2048（022 思考模型口径），防异常长回复放大成本的同时保住正文余量。 */
    @Test
    void maxOutputTokensSharedAcrossProtocolsAndAboveThinkingFloor_ac1() {
        assertThat(LlmChatClient.MAX_OUTPUT_TOKENS).isEqualTo(2048).isGreaterThanOrEqualTo(2048);
    }
}
