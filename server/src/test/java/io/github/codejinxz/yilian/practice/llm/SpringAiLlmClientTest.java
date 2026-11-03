package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * {@link SpringAiLlmClient} 装配与成本护栏单测 [R1-F2]：手工组装路径（ClientOptions/OpenAI SDK/ChatClient）
 * 在离线环境只做本地构造不发请求；输出 token 上限常量落在审查约定的 512–1024 区间。
 */
class SpringAiLlmClientTest {

    /** [R1-F2] 凭证齐备的配置可离线完成客户端装配（starter 自动配置关闭后的唯一模型来源）。 */
    @Test
    void buildsClientOfflineWithDummyCredentials_r1() {
        LlmProperties properties =
                new LlmProperties("https://open.bigmodel.cn/api/paas/v4", "dummy-key", null, null);

        assertThatCode(() -> new SpringAiLlmClient(properties)).doesNotThrowAnyException();
    }

    /** [R1-F2] 输出上限是显式常量并落在 512–1024（成本护栏约定区间），防异常长回复放大成本。 */
    @Test
    void maxOutputTokensStaysInGuardrailRange_r1() {
        assertThat(SpringAiLlmClient.MAX_OUTPUT_TOKENS).isBetween(512, 1024);
    }
}
