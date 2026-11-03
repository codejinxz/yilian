package io.github.codejinxz.yilian.practice.llm;

/**
 * LLM 对话客户端接缝（017）：隔离 Spring AI 的具体类型，业务层只依赖本接口，便于测试替身与后续换供应商。
 *
 * <p>实现类仅在 {@code YILIAN_LLM_*} 凭证齐备时装配（见 {@link TutorLlmConfiguration}）；未配置时容器里没有
 * bean，答疑走「功能不存在」分支，与「配了但管理员未开启（40304）」区分。
 */
public interface LlmChatClient {

    /**
     * 发起一次同步对话补全。
     *
     * @param systemPrompt 系统提示（角色与安全边界，由调用方拼装）
     * @param userPrompt 用户消息（题目上下文 + 定界后的问题）
     * @return 回复内容与 token 用量
     * @throws RuntimeException 网络/超时/供应商错误由实现直接抛出，调用方统一降级
     */
    Reply ask(String systemPrompt, String userPrompt);

    /**
     * 一次调用的结果与用量。
     *
     * @param content 模型回复文本
     * @param promptTokens 输入 token 数（供应商缺失时为 0）
     * @param completionTokens 输出 token 数（供应商缺失时为 0）
     */
    record Reply(String content, int promptTokens, int completionTokens) {}
}
