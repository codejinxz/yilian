package io.github.codejinxz.yilian.content.dao;

import io.github.codejinxz.yilian.content.entity.BankVisibility;
import io.github.codejinxz.yilian.content.entity.ContentStatus;
import io.github.codejinxz.yilian.content.entity.SentenceBank;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/** 题库 Dao。 */
public interface BankDao {

    /**
     * 保存题库。
     *
     * @param bank 题库
     * @return 持久化后的实体
     */
    SentenceBank save(SentenceBank bank);

    /**
     * 按 id 查题库。
     *
     * @param id 题库 id
     * @return 题库
     */
    Optional<SentenceBank> findById(long id);

    /**
     * 导入提交时加悲观写锁。
     *
     * @param id 题库 id
     * @return 题库
     */
    Optional<SentenceBank> lockById(long id);

    /**
     * 公开/登录可见题库检索。
     *
     * @param userId 当前用户，可为 null
     * @param keyword 关键字
     * @param visibility 可见性
     * @param pageable 分页
     * @return 分页结果
     */
    Page<SentenceBank> searchVisible(
            Long userId, String keyword, BankVisibility visibility, Pageable pageable);

    /**
     * 管理端题库检索。
     *
     * @param keyword 关键字
     * @param status 状态
     * @param pageable 分页
     * @return 分页结果
     */
    Page<SentenceBank> searchAdmin(String keyword, ContentStatus status, Pageable pageable);

    /**
     * 统计题库内已发布且未删题目数。
     *
     * @param bankId 题库 id
     * @return 数量
     */
    long countPublishedSentences(long bankId);
}
