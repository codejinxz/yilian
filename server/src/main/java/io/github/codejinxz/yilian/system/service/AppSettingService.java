package io.github.codejinxz.yilian.system.service;

import java.util.Optional;

/** 系统配置（app_settings）键值存取：供各模块读写运营开关，结构解析由调用方按 schemaVersion 分支。 */
public interface AppSettingService {

    /**
     * 读取配置值原文（JSON 文本）。
     *
     * @param key 配置键
     * @return JSON 文本；键不存在时为空
     */
    Optional<String> findValue(String key);

    /**
     * 写入配置：无键插入、有键覆盖（末写胜出）。
     *
     * <p>本表不缓存：低频写、单行主键读，不值得引入缓存失效面（AGENTS §3.8 的分层针对热点元数据）。 密钥类配置禁止写入本表。
     *
     * @param key 配置键
     * @param value JSON 文本（调用方保证含 schemaVersion 且合法）
     * @param description 首次插入时的描述；覆盖已有键时忽略
     * @param updatedBy 操作者 id，可空
     * @return 落库后的 JSON 文本
     */
    String upsert(String key, String value, String description, Long updatedBy);
}
