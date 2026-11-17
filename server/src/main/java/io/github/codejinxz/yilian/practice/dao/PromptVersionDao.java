package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.PromptVersion;
import io.github.codejinxz.yilian.practice.entity.PromptVersion.PromptLabel;

import java.util.List;
import java.util.Optional;

/** Prompt 版本 Dao：PromptRegistry 的数据源（按场景名取 ACTIVE 版本），并兼作管理端 写路径（登记/检索/激活）的读写口。 */
public interface PromptVersionDao {

    /**
     * 取某场景的当前 ACTIVE 版本（同 name 多行 ACTIVE 时取 version 最高者，注册表不做跨行约束）。
     *
     * @param name 场景名（ask / review / appeal）
     * @return ACTIVE 版本；未登记时为空
     */
    Optional<PromptVersion> findActiveByName(String name);

    /**
     * 按 id 取版本行（激活前定位与窗口竞态后的回读）。
     *
     * @param id 版本行 id
     * @return 版本行；不存在时为空
     */
    Optional<PromptVersion> findById(long id);

    /**
     * 取某场景已登记的最大版本号（登记新版本 = 最大值 + 1 的依据）。
     *
     * @param name 场景名（ask / review / appeal）
     * @return 现存最大版本号；该场景未登记时为空
     */
    Optional<Integer> findMaxVersion(String name);

    /**
     * 分页检索版本行：name/label 可选过滤（null 不过滤），按 name 升序 + version 倒序稳定排列 （治理列表语义：同场景新版本在前）。
     *
     * @param name 场景名过滤，null 不过滤
     * @param label 标签过滤，null 不过滤
     * @param offset 偏移量（(page-1)*size，调用方按 011 口径换算）
     * @param limit 每页条数（调用方保证 1–100）
     * @return 当前页版本行
     */
    List<PromptVersion> search(String name, PromptLabel label, int offset, int limit);

    /**
     * 分页检索总数（与 {@link #search} 同过滤口径）。
     *
     * @param name 场景名过滤，null 不过滤
     * @param label 标签过滤，null 不过滤
     * @return 满足过滤条件的总行数
     */
    long countSearch(String name, PromptLabel label);

    /**
     * 保存版本行（登记新版本；label 流转由 {@link #updateLabel} 定向 UPDATE 承担， 不走实体改写——append-only 语义下行内容三列永不修改）。
     *
     * @param version 未持久化的版本行
     * @return 已持久化的版本行（含 id）
     */
    PromptVersion save(PromptVersion version);

    /**
     * 定向 UPDATE 改标签（激活/降级唯一写入口）：只碰 label 一列，name/version/content 三列 不可达（append-only 行内容不改写由 SQL
     * 面约束，而不是仅靠代码纪律）。
     *
     * @param id 版本行 id
     * @param label 目标标签（ACTIVE / CANDIDATE）
     * @return 受影响行数（0=行不存在）
     */
    int updateLabel(long id, PromptLabel label);
}
