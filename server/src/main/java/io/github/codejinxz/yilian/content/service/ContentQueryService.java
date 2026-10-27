package io.github.codejinxz.yilian.content.service;

import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.content.entity.BankVisibility;
import io.github.codejinxz.yilian.content.entity.ContentStatus;
import io.github.codejinxz.yilian.content.service.dto.BankView;
import io.github.codejinxz.yilian.content.service.dto.SentenceDetail;
import io.github.codejinxz.yilian.content.service.dto.SentencePage;

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
     * 练习候选：只返回已发布题库中的已发布题目。
     *
     * @param bankId 题库 id，可为 null
     * @param excludeSentenceId 排除题目
     * @param limit 最多条数
     * @return 候选题目
     */
    List<SentenceDetail> findPracticeCandidates(Long bankId, String excludeSentenceId, int limit);
}
