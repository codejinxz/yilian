package io.github.codejinxz.yilian.practice.llm;

import io.github.codejinxz.yilian.common.config.NegativeCacheEntry;

/**
 * Prompt 版本注册表读端：按场景名取 label=ACTIVE 的系统提示版本（DATA-MODEL §5.19）。
 *
 * <p>三条 LLM 链路（答疑 ask / 模型复核 review / 申诉初裁 appeal）的系统提示不再取自代码常量，
 * 而是从注册表取版本快照；调用方在缺行/读取失败时回退到同文的代码兜底常量， 主链路不因注册表 空缺中断（AGENTS §2.2 增强失败必须降级）。
 *
 * <p>读路径带 L1 Caffeine 缓存（{@code sync=true} 防击穿、负条目防穿透）； prompt 的写路径（未来管理端改版）落库后必须调用 {@link
 * #evictAll()} 失效缓存。
 */
public interface PromptRegistry {

    /**
     * 取某场景的 ACTIVE 版本条目（缓存方法；同键并发重建互斥）。
     *
     * @param name 场景名（ask / review / appeal）
     * @return 正缓存 {@link Present} / 负缓存 {@link Absent}
     */
    Entry load(String name);

    /** 失效全部 prompt 缓存：写路径（登记新版本、切换 ACTIVE）落库后调用，保证下次读取即时生效。 */
    void evictAll();

    /** 一次读取的条目。 */
    sealed interface Entry permits Present, Absent {}

    /** 正缓存：ACTIVE 版本行（versionId 供 attempt_gradings/appeals 的 prompt_version_id 落值）。 */
    record Present(Long versionId, String content) implements Entry {}

    /** 负缓存：该场景无 ACTIVE 版本；实现 {@link NegativeCacheEntry} 以获得短 TTL。 */
    record Absent() implements Entry, NegativeCacheEntry {}
}
