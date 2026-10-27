package io.github.codejinxz.yilian.content.dao.jpa;

import io.github.codejinxz.yilian.content.entity.SentenceBank;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 题库 Spring Data 仓库，只允许同包 JpaBankDao 使用。 */
public interface SentenceBankJpaRepository extends JpaRepository<SentenceBank, Long> {

    /**
     * 导入提交需要锁住题库行，保证银行级版本号串行递增。
     *
     * @param id 题库 id
     * @return 题库
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from SentenceBank b where b.id = :id")
    Optional<SentenceBank> lockById(@Param("id") long id);

    /**
     * 公开题库检索：匿名只看到 PUBLIC，登录后额外看到自己的私有题库。
     *
     * @param userId 当前用户 id，可为 null
     * @param keyword 关键字，可为 null
     * @param visibility 可见性过滤，可为 null
     * @param pageable 分页
     * @return 分页结果
     */
    @Query(
            """
            select b from SentenceBank b
            where b.status = io.github.codejinxz.yilian.content.entity.ContentStatus.PUBLISHED
              and b.deletedAt is null
              and (b.visibility = io.github.codejinxz.yilian.content.entity.BankVisibility.PUBLIC
                   or b.ownerId = :userId)
              and (:keyword is null or lower(b.name) like lower(concat('%', :keyword, '%')))
              and (:visibility is null or b.visibility = :visibility)
            """)
    Page<SentenceBank> searchVisible(
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("visibility")
                    io.github.codejinxz.yilian.content.entity.BankVisibility visibility,
            Pageable pageable);

    /**
     * 管理端题库检索：包含 DRAFT/DISABLED/RECYCLED。
     *
     * @param keyword 关键字，可为 null
     * @param status 状态过滤，可为 null
     * @param pageable 分页
     * @return 分页结果
     */
    @Query(
            """
            select b from SentenceBank b
            where (:keyword is null or lower(b.name) like lower(concat('%', :keyword, '%')))
              and (:status is null or b.status = :status)
            """)
    Page<SentenceBank> searchAdmin(
            @Param("keyword") String keyword,
            @Param("status") io.github.codejinxz.yilian.content.entity.ContentStatus status,
            Pageable pageable);
}
