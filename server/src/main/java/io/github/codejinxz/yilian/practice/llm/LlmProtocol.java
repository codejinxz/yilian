package io.github.codejinxz.yilian.practice.llm;

import java.util.Optional;

/**
 * LLM 对话协议：provider 目录条目按本枚举选择 {@link LlmChatClient} 实现。
 *
 * <p>wire 值（{@code anthropic-messages} / {@code openai-chat} / {@code openai-responses}）是目录 JSON
 * 与管理端 契约里的稳定标识，发布后不改号；解析未知值一律 {@link #fromWire} 返回 empty，由调用方跳过该条目（fail-closed）， 不静默映射到其他协议。
 */
public enum LlmProtocol {

    /** Anthropic Messages 协议（{@code POST {base}/v1/messages}），服务 ZCode 本地网关等 Anthropic 兼容端点。 */
    ANTHROPIC_MESSAGES("anthropic-messages"),

    /** OpenAI Chat Completions 协议（Spring AI OpenAI 路径），服务智谱/DeepSeek 等 OpenAI 兼容端点。 */
    OPENAI_CHAT("openai-chat"),

    /** OpenAI Responses 协议（{@code POST {base}/v1/responses}，022 新增，手写 HTTP 客户端）。 */
    OPENAI_RESPONSES("openai-responses");

    private final String wire;

    LlmProtocol(String wire) {
        this.wire = wire;
    }

    /**
     * 目录 JSON 与管理端契约中的稳定标识。
     *
     * @return wire 值
     */
    public String wire() {
        return wire;
    }

    /**
     * 解析目录 JSON 里的协议字符串。
     *
     * @param raw wire 值（可空或空白）
     * @return 命中的协议；未知/空白返回 empty（调用方跳过该条目，fail-closed）
     */
    public static Optional<LlmProtocol> fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        for (LlmProtocol candidate : values()) {
            if (candidate.wire.equals(raw.trim())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
