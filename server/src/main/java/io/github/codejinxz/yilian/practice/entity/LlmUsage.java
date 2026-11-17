package io.github.codejinxz.yilian.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * LLM 调用用量账本（llm_usage 表，append-only，V11 建）。
 *
 * <p>只记元数据：调用场景、协议与模型快照、token、耗时、成败。问题与回答内容一律不落本表 （AGENTS §3.6 口径：完整译文不入日志/异常表，用量账本同规格）。行在调用终态写一次，
 * 不做更新；user_id / sentence_id 为逻辑引用（V10 后无物理外键），完整性由应用层校验。 落库是答疑主链路的旁路：写失败只降级为日志告警，不影响回答返回。
 */
@Entity
@Table(name = "llm_usage")
public class LlmUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发起调用的用户 id；逻辑引用 app_user.id。系统级调用（无用户语境）为 null。 */
    @Column private Long userId;

    /** 关联题目 id（ULID，26 位）；逻辑引用 sentences.id。与具体题目无关的调用为 null。 */
    @Column(name = "sentence_id", length = 26)
    private String sentenceId;

    /** 调用场景（如 tutor-ask）；同结构化日志的 scene 字段口径。 */
    @Column(nullable = false, length = 32)
    private String scene;

    /** 当次生效的协议（anthropic-messages / openai-chat / openai-responses）；未知为 null。 */
    @Column(length = 32)
    private String protocol;

    /** 当次生效的模型名快照；模型可能下线，快照使历史行自解释。未知为 null。 */
    @Column(length = 128)
    private String model;

    /** 提示词 token 数；调用失败未取得用量时为 null。 */
    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    /** 回复 token 数；调用失败未取得用量时为 null。 */
    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /** 调用耗时（毫秒，墙钟）；失败路径同样记录。 */
    @Column(name = "duration_ms")
    private Long durationMs;

    /**
     * 估算成本（微元整数，1 CNY = 1e6 微元，ROUND_HALF_UP）；按当次模型定价推算的估算值而非厂商账单。 当次调用时该模型无定价配置为
     * null——护栏只对已定价模型生效，未定价=免费语义如实（不计入预算）。
     */
    @Column(name = "estimated_cost_micro_cny")
    private Long estimatedCostMicroCny;

    /** 调用是否成功（拿到非空回复）；失败/空回复为 false。 */
    @Column(nullable = false)
    private boolean ok;

    /** 记录时刻（UTC）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LlmUsage() {
        // 供 JPA 反射实例化
    }

    /**
     * 创建一条终态用量记录（全字段显式给定，写一次不改）。
     *
     * @param userId 发起用户 id，可为 null
     * @param sentenceId 关联题目 id，可为 null
     * @param scene 调用场景（非空）
     * @param protocol 协议名，可为 null
     * @param model 模型名快照，可为 null
     * @param promptTokens 提示词 token，可为 null
     * @param completionTokens 回复 token，可为 null
     * @param durationMs 耗时毫秒，可为 null
     * @param estimatedCostMicroCny 估算成本微元（无定价时为 null，不计入预算）
     * @param ok 是否成功
     * @param now 记录时刻
     * @return 未持久化的用量记录
     */
    public static LlmUsage of(
            Long userId,
            String sentenceId,
            String scene,
            String protocol,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Long durationMs,
            Long estimatedCostMicroCny,
            boolean ok,
            Instant now) {
        LlmUsage usage = new LlmUsage();
        usage.userId = userId;
        usage.sentenceId = sentenceId;
        usage.scene = scene;
        usage.protocol = protocol;
        usage.model = model;
        usage.promptTokens = promptTokens;
        usage.completionTokens = completionTokens;
        usage.durationMs = durationMs;
        usage.estimatedCostMicroCny = estimatedCostMicroCny;
        usage.ok = ok;
        usage.createdAt = now;
        return usage;
    }

    /**
     * 主键。
     *
     * @return 自增 id
     */
    public Long getId() {
        return id;
    }

    /**
     * 发起用户 id。
     *
     * @return 用户 id，可为 null
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 关联题目 id。
     *
     * @return 题目 ULID，可为 null
     */
    public String getSentenceId() {
        return sentenceId;
    }

    /**
     * 调用场景。
     *
     * @return 场景标识
     */
    public String getScene() {
        return scene;
    }

    /**
     * 协议名。
     *
     * @return 协议标识，可为 null
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * 模型名快照。
     *
     * @return 模型名，可为 null
     */
    public String getModel() {
        return model;
    }

    /**
     * 提示词 token 数。
     *
     * @return token 数，可为 null
     */
    public Integer getPromptTokens() {
        return promptTokens;
    }

    /**
     * 回复 token 数。
     *
     * @return token 数，可为 null
     */
    public Integer getCompletionTokens() {
        return completionTokens;
    }

    /**
     * 耗时毫秒。
     *
     * @return 耗时，可为 null
     */
    public Long getDurationMs() {
        return durationMs;
    }

    /**
     * 估算成本微元。
     *
     * @return 微元整数（ROUND_HALF_UP 口径）；当次模型无定价时为 null
     */
    public Long getEstimatedCostMicroCny() {
        return estimatedCostMicroCny;
    }

    /**
     * 是否成功。
     *
     * @return 成功返回 true
     */
    public boolean isOk() {
        return ok;
    }

    /**
     * 记录时刻。
     *
     * @return UTC 时刻
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
