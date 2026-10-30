package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.FsrsParameterVersion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** FSRS 参数版本 Spring Data 仓库（只读），只允许同包 JpaFsrsParameterVersionDao 使用。 */
public interface FsrsParameterVersionJpaRepository
        extends JpaRepository<FsrsParameterVersion, Long> {

    /**
     * GLOBAL ACTIVE 权重版本 id，创建时间倒序。
     *
     * @return 版本 id 列表
     */
    @Query(
            "select f.id from FsrsParameterVersion f where f.ownerType = 'GLOBAL'"
                    + " and f.status = 'ACTIVE' order by f.createdAt desc")
    List<Long> findActiveGlobalIds();
}
