package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.FsrsParameterVersion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** FSRS 参数版本 Spring Data 仓库，只允许同包 JpaFsrsParameterVersionDao 使用。 */
public interface FsrsParameterVersionJpaRepository
        extends JpaRepository<FsrsParameterVersion, Long> {

    /**
     * GLOBAL ACTIVE 权重版本行，创建时间倒序。
     *
     * @return 版本行列表（uk_fsrs_active_owner 保证至多一行）
     */
    @Query(
            "select f from FsrsParameterVersion f where f.ownerType = 'GLOBAL'"
                    + " and f.status = 'ACTIVE' order by f.createdAt desc")
    List<FsrsParameterVersion> findActiveGlobals();

    /**
     * 全部版本行，创建时间倒序、id 倒序（同秒稳定）。
     *
     * @return 版本行列表
     */
    @Query("select f from FsrsParameterVersion f order by f.createdAt desc, f.id desc")
    List<FsrsParameterVersion> findAllByRecency();

    /**
     * 即时退役 GLOBAL ACTIVE 行（启用新版本的先决步骤，bulk UPDATE 立即落库）。
     *
     * @return 退役行数
     */
    @Modifying
    @Query(
            "update FsrsParameterVersion f set f.status = 'RETIRED'"
                    + " where f.ownerType = 'GLOBAL' and f.status = 'ACTIVE'")
    int demoteActiveGlobals();
}
