package io.github.codejinxz.yilian.content.service;

import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.content.entity.BankVisibility;
import io.github.codejinxz.yilian.content.entity.ContentStatus;
import io.github.codejinxz.yilian.content.service.dto.BankView;
import io.github.codejinxz.yilian.content.service.dto.ImportedSentence;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.dto.SentenceDetail;

/** 内容管理端用例：题库增改、题目状态、导入写入。 */
public interface ContentAdminService {

    /**
     * 管理端题库列表，含草稿/停用/回收站。
     *
     * @param keyword 关键字
     * @param status 状态过滤
     * @param page 页码
     * @param size 每页大小
     * @return 分页视图
     */
    PageResponse<BankView> listAdminBanks(String keyword, ContentStatus status, int page, int size);

    /**
     * 管理端题库详情，含软删。
     *
     * @param bankId 题库 id
     * @return 题库视图
     */
    BankView getAdminBank(long bankId);

    /**
     * 新建官方题库。
     *
     * @param name 名称
     * @param description 说明
     * @param visibility 可见性
     * @param actorId 创建者
     * @return 题库视图
     */
    BankView createBank(String name, String description, BankVisibility visibility, long actorId);

    /**
     * 修改题库信息或状态。
     *
     * @param bankId 题库 id
     * @param name 名称，可为 null
     * @param description 说明，可为 null
     * @param visibility 可见性，可为 null
     * @param status 状态，可为 null
     * @param actorId 操作者
     * @return 题库视图
     */
    BankView updateBank(
            long bankId,
            String name,
            String description,
            BankVisibility visibility,
            ContentStatus status,
            long actorId);

    /**
     * 软删题库进回收站；不硬删题目。
     *
     * @param bankId 题库 id
     * @param reason 审计原因
     * @param actorId 操作者
     */
    void deleteBank(long bankId, String reason, long actorId);

    /**
     * 管理端题目分页。
     *
     * @param bankId 题库 id
     * @param page 页码
     * @param size 每页大小
     * @return 分页视图
     */
    PageResponse<SentenceDetail> listAdminSentences(long bankId, int page, int size);

    /**
     * 停用/恢复/回收题目，并 bump 银行级版本。
     *
     * @param sentenceId 题目 id
     * @param status 目标状态
     * @param reason 审计原因
     * @param actorId 操作者
     * @return 更新后的题目视图
     */
    SentenceDetail setSentenceStatus(
            String sentenceId, ContentStatus status, String reason, long actorId);

    /**
     * 导入提交时锁题库并返回当前银行级版本；锁持有到外层事务结束。
     *
     * @param bankId 题库 id
     * @return 当前版本
     */
    long lockBankVersion(long bankId);

    /**
     * 导入提交完成后推进题库版本并发布。
     *
     * @param bankId 题库 id
     * @param newVersion 新版本
     * @param actorId 操作者
     */
    void advanceBankVersion(long bankId, long newVersion, long actorId);

    /**
     * 导入单行写入：按 externalKey 新增或生成新版本，内容相同则幂等返回。
     *
     * @param bankId 题库 id
     * @param bankVersion 本次提交的银行级版本
     * @param content 已校验内容
     * @param batchId 导入批次 id
     * @param actorId 操作者
     * @return 写入结果
     */
    ImportedSentence upsertFromImport(
            long bankId, long bankVersion, SentenceContent content, String batchId, long actorId);
}
