package io.github.codejinxz.yilian.content.service;

import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.content.service.vo.BankView;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;

import java.util.List;

/**
 * 内容管理用例（content 模块对 admin 模块的接缝，跨模块只依赖 service 接口）。
 *
 * <p>管理端看全量生命周期（含 DRAFT/RECYCLED），写路径全部落在本模块既有实体上； visibility/status 用字符串承载，实现侧白名单校验（同 system 模块
 * role 的先例），非法值按参数校验失败返回。
 */
public interface ContentAdminService {

    /**
     * 管理端题库检索：不分可见集合，含 DRAFT/DISABLED/RECYCLED。
     *
     * @param keyword 关键字，匹配名称与说明，null 或空白表示不过滤
     * @param visibility 可见性过滤 PUBLIC/PRIVATE，null 表示不过滤
     * @param status 状态过滤，null 表示不过滤
     * @param page 页码，从 1 开始
     * @param size 每页大小，1–100
     * @return 分页视图，按 id 倒序（新建在前）
     */
    PageResponse<BankView> adminListBanks(
            String keyword, String visibility, String status, int page, int size);

    /**
     * 管理端题库详情：任何状态可见（含回收站）。
     *
     * @param bankId 题库 id
     * @return 题库视图
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 题库不存在（40401）
     */
    BankView adminGetBank(long bankId);

    /**
     * 新建官方题库：owner 为空、初始 DRAFT、修订号 0。
     *
     * @param actorId 操作管理员 id，写 created_by/updated_by
     * @param name 名称，1–128 字符
     * @param description 说明，最长 500 字符，null 表示无说明
     * @param visibility 可见性 PUBLIC/PRIVATE
     * @return 新题库视图
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 名称/可见性不合法（40000）
     */
    BankView adminCreateBank(Long actorId, String name, String description, String visibility);

    /**
     * 部分更新题库：null 字段不修改；说明传非 null 值（含空串）视为清空/改写。
     *
     * <p>status 非空时走生命周期流转（含恢复：移出回收站会清 deleted_at）。
     *
     * @param actorId 操作管理员 id
     * @param bankId 题库 id
     * @param name 新名称，null 表示不改
     * @param description 新说明，null 表示不改，空串清空
     * @param visibility 新可见性，null 表示不改
     * @param status 新状态，null 表示不改
     * @return 更新后的视图
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 题库不存在（40401）或字段不合法（40000）
     */
    BankView adminUpdateBank(
            Long actorId,
            long bankId,
            String name,
            String description,
            String visibility,
            String status);

    /**
     * 软删题库：状态流转到 RECYCLED 并写 deleted_at/by；不物理删除，题目一并隐藏于练习端。
     *
     * @param actorId 操作管理员 id
     * @param bankId 题库 id
     * @param reason 审计原因，必填 1–255 字符
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 题库不存在（40401）或原因缺失（40000）
     */
    void adminDeleteBank(long actorId, long bankId, String reason);

    /**
     * 题库内管理端题目列表：含 DRAFT/RECYCLED 全量生命周期。
     *
     * @param bankId 题库 id
     * @param keyword 关键字，匹配中文/参考译文，null 表示不过滤
     * @param status 状态过滤，null 表示不过滤
     * @param page 页码，从 1 开始
     * @param size 每页大小，1–100
     * @return 分页视图，按创建时间倒序
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 题库不存在（40401）或分页非法（40004）
     */
    PageResponse<SentenceDetail> adminListBankSentences(
            long bankId, String keyword, String status, int page, int size);

    /**
     * 跨题库管理端题目列表：缺省隐藏回收站，显式传 status（含 RECYCLED）时按精确状态过滤。
     *
     * @param keyword 关键字，匹配中文/参考译文，null 表示不过滤
     * @param bankId 题库过滤，数字字符串；null 表示跨题库
     * @param level 难度精确过滤，null 表示不过滤
     * @param status 状态过滤，null 表示「全部但隐藏回收站」
     * @param page 页码，从 1 开始
     * @param size 每页大小，1–100
     * @return 分页视图，按创建时间倒序
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 分页/筛选参数非法（40000/40004）
     */
    PageResponse<SentenceDetail> adminListSentences(
            String keyword, String bankId, String level, String status, int page, int size);

    /**
     * 批量变更题目状态；单事务，任一失败整体回滚。
     *
     * <p>语义（与前端 mock 一致，禁止静默吞差异）：不存在的 id 跳过不报错；已处于目标状态的 id 跳过且不推进修订号； 返回值是实际变更的条数，调用方据此暴露「实际处理 N
     * 条」。每条变更按「每题递增」推进所属题库修订号（P1-1：增量游标永不重版本分组）， 草稿题库随内容变更发布（与导入流程同语义）。
     *
     * @param actorId 操作管理员 id
     * @param ids 题目 id 列表，去重后 1–500 条
     * @param status 目标状态 DRAFT/PUBLISHED/DISABLED/RECYCLED/ARCHIVED
     * @param reason 审计原因，必填
     * @return 实际变更的题目数
     * @throws io.github.codejinxz.yilian.common.api.BusinessException ids/reason/status 不合法（40000）
     */
    int adminBulkChangeStatus(long actorId, List<String> ids, String status, String reason);

    /**
     * 批量移动题目到另一题库；单事务，任一失败整体回滚。
     *
     * <p>语义同 {@link #adminBulkChangeStatus}：不存在的 id 与已在目标题库的 id 跳过，返回实际移动数。 目标题库必须存在且未进回收站（40401）；
     * 目标题库已有相同内容的活跃题目时整批拒绝（40000， 与导入指纹唯一约束同源）。目标题库修订号按「每题递增」推进，源题库各推进一次。
     *
     * @param actorId 操作管理员 id
     * @param ids 题目 id 列表，去重后 1–500 条
     * @param bankId 目标题库 id，数字字符串
     * @param reason 审计原因，必填
     * @return 实际移动的题目数
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 参数不合法（40000）、目标题库不可用（40401）
     */
    int adminBulkMove(long actorId, List<String> ids, String bankId, String reason);

    /**
     * 单题状态流转（停用/恢复/回收）；恢复会清软删标记，单事务。
     *
     * <p>已处于目标状态时不推进修订号（幂等，与批量同语义），直接回当前视图。
     *
     * @param actorId 操作管理员 id
     * @param sentenceId 题目 ULID
     * @param status 目标状态 DRAFT/PUBLISHED/DISABLED/RECYCLED/ARCHIVED
     * @param reason 审计原因，必填 1–255 字符
     * @return 更新后的题目视图（含归属题库 bankId）
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 题目不存在（40402）、参数不合法（40000）
     */
    SentenceDetail adminChangeSentenceStatus(
            long actorId, String sentenceId, String status, String reason);

    /**
     * 行内编辑题目可变字段；单事务。空请求体视为无操作，返回当前视图。
     *
     * <p>字段分两类落地（语义见 API §8.8）：
     *
     * <ul>
     *   <li><strong>版本字段</strong> zh/level/tags/note：内容快照 {@code sentence_versions} 不可变（DB 触发器禁止
     *       UPDATE）， 因此新增 {@code version_no = 最新 + 1} 的版本行并回填指纹（与导入共用同一 canonical 算法，
     *       改题后再次导入同内容仍会被去重拦截）， 所属题库按「每题递增」推进修订号；ref/chunks/blank 本端点不可改。
     *   <li><strong>元字段</strong> bankId/status：只改 {@code sentences} 行——移动复用批量移动语义（目标库须存在且未回收 40401、
     *       指纹冲突整批拒绝 40000、目标库每题递增、源库推进一次）；状态流转含恢复语义（移出回收站清 {@code deleted_at}）， 与当前状态相同则不推进修订号。
     * </ul>
     *
     * @param actorId 操作管理员 id
     * @param sentenceId 题目 ULID
     * @param zh 新中文，4–255 字符，null 表示不改
     * @param level 新难度，null 表示不改，空白清空
     * @param tags 新考点标签，null 表示不改，空白项剔除
     * @param note 新解析，最长 1000 字符，null 表示不改，空串清空
     * @param bankId 目标题库 id，数字字符串，null 表示不移动
     * @param status 新状态，null 表示不改
     * @param reason 审计原因，可空
     * @return 更新后的题目视图（含归属题库 bankId）
     * @throws io.github.codejinxz.yilian.common.api.BusinessException
     *     题目不存在（40402）、目标题库不可用（40401）、参数不合法（40000）
     */
    SentenceDetail adminUpdateSentenceDetail(
            long actorId,
            String sentenceId,
            String zh,
            String level,
            List<String> tags,
            String note,
            String bankId,
            String status,
            String reason);
}
