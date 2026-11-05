package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.LlmUsage;

/** LLM 用量账本 Dao：append-only 只写，读侧留给 M3 用量页再扩。 */
public interface LlmUsageDao {

    /**
     * 追加一条用量记录；实现不抛出受检异常，调用方自行决定失败策略。
     *
     * @param usage 用量记录
     * @return 持久化后的记录
     */
    LlmUsage save(LlmUsage usage);
}
