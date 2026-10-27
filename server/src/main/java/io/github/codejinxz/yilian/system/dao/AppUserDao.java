package io.github.codejinxz.yilian.system.dao;

import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.entity.UserRole;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/** 账号表 Dao：只暴露业务需要的单表操作，不向 Service 泄漏 delete/deleteAll。 */
public interface AppUserDao {

    /**
     * 保存账号；id 为空时插入，否则合并。
     *
     * @param user 账号实体
     * @return 持久化后的账号
     */
    AppUser save(AppUser user);

    /**
     * 按主键查账号。
     *
     * @param id 账号 id
     * @return 账号实体
     */
    Optional<AppUser> findById(Long id);

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
     * 按登录名或邮箱查账号。
     *
     * @param login 登录名或邮箱
     * @return 账号实体
     */
    Optional<AppUser> findByLogin(String login);

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
     * 统计未删除的启用管理员数量。
     *
     * @return 数量
     */
    long countActiveAdmins();

    /**
     * 管理端用户检索。
     *
     * @param keyword 关键字
     * @param role 角色，可为 null
     * @param status active/disabled/deleted/all
     * @param pageable 分页
     * @return 分页结果
     */
    Page<AppUser> search(String keyword, UserRole role, String status, Pageable pageable);
}
