package io.github.codejinxz.yilian.content.entity;

/** 内容生命周期；回收站 = {@code RECYCLED} + 非空 {@code deleted_at}。 */
public enum ContentStatus {
    /** 草稿，仅管理端可见。 */
    DRAFT,
    /** 已发布，练习可见。 */
    PUBLISHED,
    /** 已停用，练习不可见，可恢复。 */
    DISABLED,
    /** 回收站；不硬删。 */
    RECYCLED,
    /** 归档，长期不再使用。 */
    ARCHIVED
}
