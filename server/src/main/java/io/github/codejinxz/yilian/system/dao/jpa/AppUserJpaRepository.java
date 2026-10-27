package io.github.codejinxz.yilian.system.dao.jpa;

import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.entity.UserRole;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 账号 Spring Data 仓库，只允许同包 JpaAppUserDao 使用。 */
public interface AppUserJpaRepository extends JpaRepository<AppUser, Long> {

    /**
     * 按登录名查账号。
     *
     * @param username 登录名
     * @return 账号实体
     */
    Optional<AppUser> findByUsername(String username);

    /**
     * 按邮箱查账号。
     *
     * @param email 邮箱
     * @return 账号实体
     */
    Optional<AppUser> findByEmail(String email);

    /**
     * 按登录名或邮箱查账号，供「用户名或邮箱」登录。
     *
     * @param username 登录名
     * @param email 邮箱
     * @return 账号实体
     */
    Optional<AppUser> findByUsernameOrEmail(String username, String email);

    /**
     * 用户名是否已存在。
     *
     * @param username 登录名
     * @return 存在时为 true
     */
    boolean existsByUsername(String username);

    /**
     * 邮箱是否已存在。
     *
     * @param email 邮箱
     * @return 存在时为 true
     */
    boolean existsByEmail(String email);

    /**
     * 统计未删除的启用管理员数量，用于「不能停用/降级最后一个管理员」。
     *
     * @param role 角色
     * @return 数量
     */
    long countByRoleAndEnabledTrueAndDeletedAtIsNull(UserRole role);

    /**
     * 管理端用户检索。
     *
     * @param keyword 用户名或邮箱关键字，可为 null
     * @param role 角色过滤，可为 null
     * @param status active/disabled/deleted/all
     * @param pageable 分页
     * @return 分页结果
     */
    @Query(
            """
            select u from AppUser u
            where (:keyword is null
                   or lower(u.username) like lower(concat('%', :keyword, '%'))
                   or lower(u.email) like lower(concat('%', :keyword, '%')))
              and (:role is null or u.role = :role)
              and (:status = 'all'
                   or (:status = 'active' and u.enabled = true and u.deletedAt is null)
                   or (:status = 'disabled' and u.enabled = false and u.deletedAt is null)
                   or (:status = 'deleted' and u.deletedAt is not null))
            """)
    Page<AppUser> search(
            @Param("keyword") String keyword,
            @Param("role") UserRole role,
            @Param("status") String status,
            Pageable pageable);
}
