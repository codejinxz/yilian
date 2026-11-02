package io.github.codejinxz.yilian.common.config;

/**
 * 负缓存条目标记（013 AC-3 防穿透）。
 *
 * <p>实现本接口的缓存值表示「查询结果为空 / 对调用者不可见」；{@link JitteredTtlExpiry} 识别后 使用显著短于正缓存的
 * TTL——既挡住对不存在键的反复穿透，又不让「暂不可见」的状态陈旧太久。
 *
 * <p>标记接口放在 common 而非业务模块，避免缓存基础设施反向依赖 content 等业务包。
 */
public interface NegativeCacheEntry {}
