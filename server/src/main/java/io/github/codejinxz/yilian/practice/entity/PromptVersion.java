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
