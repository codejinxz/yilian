package io.github.codejinxz.yilian.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Prompt 版本快照（prompt_versions 表，DATA-MODEL §5.19）：三条 LLM 链路（ask/review/appeal） 的系统提示登记源。
 *
 * <p>行内容（name/version/content）一经登记不改写——原地改写等于伪造历史快照， 判分与裁决的可追溯性依赖「落库的 prompt_version_id 指向当时的提示词」；
 * 新版本一律追加更高 version 行，启用态由 label 流转表达。表无 append-only 触发器， 因为 label 的 ACTIVE/CANDIDATE 流转本身就是 UPDATE
 * 语义。
 */
@Entity
@Table(name = "prompt_versions")
public class PromptVersion {

    /** 标签：ACTIVE=当前取用（每 name 约定至多一个）；CANDIDATE=候选待启用。 */
    public enum PromptLabel {
        /** 当前取用版本。 */
        ACTIVE,
        /** 候选版本（已登记未启用）。 */
        CANDIDATE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 场景名：ask / review / appeal；与 PromptRegistry 的取用键同源。 */
    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PromptLabel label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PromptVersion() {
        // 供 JPA 反射实例化
    }

    /**
     * 登记一个新版本行（管理端写路径）：行内容三列只在创建时赋值，此后经定向 UPDATE 改 label， 不经实体改写（append-only 纪律——本类不提供
     * name/version/content 的 setter）。
     *
     * @param name 场景名（ask / review / appeal）
     * @param version 版本号（同 name 内现存最大 +1，由服务端分配）
     * @param content 系统提示全文（版本快照）
     * @param label 初始标签（登记恒 CANDIDATE）
     * @param now 登记时间（UTC，服务端时钟）
     * @return 未持久化的版本行
     */
    public static PromptVersion create(
            String name, int version, String content, PromptLabel label, Instant now) {
        PromptVersion row = new PromptVersion();
        row.name = name;
        row.version = version;
        row.content = content;
        row.label = label;
        row.createdAt = now;
        return row;
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
     * 场景名。
     *
     * @return ask / review / appeal
     */
    public String getName() {
        return name;
    }

    /**
     * 版本号。
     *
     * @return 同 name 内递增
     */
    public int getVersion() {
        return version;
    }

    /**
     * 系统提示全文。
     *
     * @return 版本快照内容
     */
    public String getContent() {
        return content;
    }

    /**
     * 标签。
     *
     * @return ACTIVE / CANDIDATE
     */
    public PromptLabel getLabel() {
        return label;
    }

    /**
     * 登记时间。
     *
     * @return UTC
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
