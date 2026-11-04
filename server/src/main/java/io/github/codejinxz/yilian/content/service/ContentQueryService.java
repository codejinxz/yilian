package io.github.codejinxz.yilian.content.service;

import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.content.entity.BankVisibility;
import io.github.codejinxz.yilian.content.entity.ContentStatus;
import io.github.codejinxz.yilian.content.service.vo.BankView;
import io.github.codejinxz.yilian.content.service.vo.PickerSentenceView;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;
import io.github.codejinxz.yilian.content.service.vo.SentencePage;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 内容只读查询：供题库接口与 practice 模块使用，不暴露写操作与实体。 */
public interface ContentQueryService {

    /**
     * 题库列表：匿名只返回公开题库，登录后附加自己的私有题库。
     *
     * <p>公开可见集合恒为「已发布且未软删」；请求其他状态时结果为空页而不是报错。
     *
     * @param userId 当前用户 id，可为 null
     * @param keyword 关键字
     * @param visibility 可见性过滤
     * @param status 状态过滤，仅 PUBLISHED 有效；null 视为 PUBLISHED
     * @param page 页码
     * @param size 每页大小
     * @return 分页视图
     */
    PageResponse<BankView> listBanks(
            Long userId,
            String keyword,
            BankVisibility visibility,
            ContentStatus status,
            int page,
            int size);

    /**
     * 题库详情。
     *
     * @param bankId 题库 id
     * @param userId 当前用户 id，可为 null
     * @param admin 是否管理端
     * @return 题库视图
     */
    BankView getBank(long bankId, Long userId, boolean admin);

    /**
     * 按 sinceVersion 增量拉取题目。
     *
     * @param bankId 题库 id
     * @param sinceVersion 起始版本
     * @param limit 每页条数
     * @param userId 当前用户 id，可为 null
     * @param admin 是否管理端
     * @return 增量响应
     */
    SentencePage listSentences(
            long bankId, long sinceVersion, int limit, Long userId, boolean admin);

    /**
     * 练习选题句列表（API §5.3.1，020 AC-1）：题库内按页浏览 + 当前用户练过态。
     *
     * <p>与 {@link #listSentences(long, long, int, Long, boolean)} 刻意不同形：增量端点服务 PWA 离线缓存（版本游标 +
     * tombstone）， 本端点服务练习页选题面板（页码分页 + keyword/level/status 筛选 + 逐行练过态）。可见性与题库详情同口径： 不存在/未发布/已软删回
     * 40401，匿名访问私有库回 40100，登录后非所有者回 40300。
     *
     * @param bankId 题库 id
     * @param userId 当前用户 id，可为 null（匿名，练过态恒为「未学」）
     * @param keyword 关键字，匹配中文/参考译文，可空
     * @param level 难度精确过滤，可空
     * @param status 状态精确过滤，可空；缺省隐藏回收站（RECYCLED），显式传值时按精确状态过滤
     * @param page 页码，从 1 开始
     * @param size 每页大小，1–100
     * @return 分页选题句
     */
    PageResponse<PickerSentenceView> listPickerSentences(
            long bankId,
            Long userId,
            String keyword,
            String level,
            ContentStatus status,
            int page,
            int size);

    /**
     * 按 id 取题目详情（可能为 tombstone）。
     *
     * @param sentenceId 题目 id
     * @return 题目视图
     */
    Optional<SentenceDetail> findSentence(String sentenceId);

    /**
     * 按 id 批量取题目详情。
     *
     * @param sentenceIds 题目 id 集合
     * @return 题目视图
     */
    List<SentenceDetail> findSentences(Collection<String> sentenceIds);

    /**
     * 校验题库对当前用户可见（practice.next 显式 bankId 过滤的前置校验）。
     *
     * <p>与 {@link #getBank(long, Long, boolean)} 的可见性口径一致：不存在、未发布或已软删回 40401； 匿名访问私有库回
     * 40401，登录后非所有者回 40300。 通过校验只代表「库可见」，库内无题仍由取题逻辑返回空态。
     *
     * @param bankId 题库 id
     * @param userId 当前用户 id，可为 null
     */
    void requireVisibleBank(long bankId, Long userId);

    /**
     * 判断题库对当前用户可见（练习端口径，与 {@link #requireVisibleBank(long, Long)} 同规则的布尔版）：
     * 已发布、未软删，且为公开库或登录用户本人的私有库。
     *
     * <p>供 practice 模块对「按 id 取到的句子」补所属库可见性校验（016 P0-1）：MANUAL 指定句不可见时 静默退回常规取题，交卷校验不可见时回
     * 40300——两条路径都不回显题目内容（原文默认不可见红线）。
     *
     * @param bankId 题库 id
     * @param userId 当前用户 id，可为 null（匿名）
     * @return 可见返回 true；题库不存在时为 false
     */
    boolean isBankVisibleToUser(long bankId, Long userId);

    /**
     * 练习候选：只返回当前用户可见题库中已发布、未入队（无非 NEW 复习卡）的题目。
     *
     * <p>可见性与「未入队」均在数据层过滤（016 P0-1a / P1），limit 只作用于过滤后的候选窗口。
     *
     * @param userId 当前用户 id，可为 null（匿名，仅公开题库参与候选）
     * @param bankId 题库 id，可为 null
     * @param excludeIds 排除题目集合，可空；空集合表示不排除
     * @param tag 考点筛选（sentence_versions.tags JSON 精确成员匹配），可为 null
     * @param limit 最多条数
     * @return 候选题目
     */
    List<SentenceDetail> findPracticeCandidates(
            Long userId, Long bankId, Collection<String> excludeIds, String tag, int limit);
}
