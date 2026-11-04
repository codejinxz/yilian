package io.github.codejinxz.yilian.practice.llm;

/**
 * LLM 对话客户端接缝（三协议统一接口）：隔离具体协议实现（Anthropic Messages / OpenAI Chat Completions / OpenAI
 * Responses），业务层只依赖本接口，便于测试替身与按 provider 目录切换供应商。
 *
 * <p>实现类不经容器条件装配，改由 {@code LlmClientRegistry} 按激活 provider 的 protocol 在运行时装配并缓存；目录为空且 legacy
 * 环境变量齐备时回退 legacy 单 provider（读路径兼容）。
 */
public interface LlmChatClient {

    /**
     * 单次回复输出 token 上限（三协议同源）：思考模型的 thinking 计入 max_tokens（实测 max_tokens=32 时输出全被 thinking
     * 吃掉、stop_reason=max_tokens），768 档会被思考型模型挤占殆尽，提到 2048 保证 thinking 之后仍有正文余量；约 1000+ 汉字，仍覆盖答疑 300
     * 字上限（PRD §5.4）的成本护栏。
     */
    int MAX_OUTPUT_TOKENS = 2048;

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
     * @param content 模型回复文本（思考模型的 thinking 块不拼入；供应商返回空正文时为 null/空白，调用方按降级处理）
     * @param promptTokens 输入 token 数（供应商缺失时为 0）
     * @param completionTokens 输出 token 数（供应商缺失时为 0）
     */
    record Reply(String content, int promptTokens, int completionTokens) {}
}
