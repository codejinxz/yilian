package io.github.codejinxz.yilian.practice.dao;

/** FSRS 参数版本 Dao：practice 模块只读，消费 GLOBAL ACTIVE 权重行 id。 */
public interface FsrsParameterVersionDao {

    /**
     * 取 GLOBAL ACTIVE 权重版本 id（review_logs.weights_version_id 外键）。
     *
     * @return 版本 id；V9 已播种原型行，正常恒存在
     */
    java.util.Optional<Long> findActiveGlobalId();
}
