package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.FsrsParameterVersion;

import java.util.List;
import java.util.Optional;

/** FSRS 参数版本 Dao：调度消费 ACTIVE 权重，优化器链路读写版本行。 */
public interface FsrsParameterVersionDao {

    /**
     * 取 GLOBAL ACTIVE 权重版本行（review_logs.weights_version_id 外键 + 排程参数来源）。
     *
     * @return 版本行；V9 已播种原型行，正常恒存在
     */
    Optional<FsrsParameterVersion> findActiveGlobal();

    /**
     * 全部版本行（管理端列表），创建时间倒序、id 倒序稳定排序。
     *
     * @return 版本行列表
     */
    List<FsrsParameterVersion> findAllByRecency();

    /**
     * 按 id 取版本行。
     *
     * @param id 版本 id
     * @return 版本行
     */
    Optional<FsrsParameterVersion> findById(Long id);

    /**
     * 保存版本行（新候选或状态流转）。
     *
     * @param version 版本行
     * @return 持久化后的版本行
     */
    FsrsParameterVersion save(FsrsParameterVersion version);

    /**
     * 即时退役当前 GLOBAL ACTIVE 行（bulk UPDATE，绕过持久化上下文延迟 flush）。
     *
     * <p>启用新版本时必须先让旧行落库为 RETIRED 再激活候选，否则两次 UPDATE 的 flush 顺序 不受控，会撞
     * uk_fsrs_active_owner（函数索引在同一时刻看到两行 ACTIVE）。
     *
     * @return 退役的行数
     */
    int demoteActiveGlobal();
}
