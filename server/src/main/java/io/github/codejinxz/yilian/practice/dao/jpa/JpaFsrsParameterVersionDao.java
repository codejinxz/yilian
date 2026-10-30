package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.FsrsParameterVersionDao;

import org.springframework.stereotype.Repository;

import java.util.Optional;

/** FSRS 参数版本 Dao 的 JPA 实现（只读）。 */
@Repository
public class JpaFsrsParameterVersionDao implements FsrsParameterVersionDao {

    private final FsrsParameterVersionJpaRepository versions;

    /**
     * @param versions 参数版本 Spring Data 仓库
     */
    public JpaFsrsParameterVersionDao(FsrsParameterVersionJpaRepository versions) {
        this.versions = versions;
    }

    @Override
    public Optional<Long> findActiveGlobalId() {
        return versions.findActiveGlobalIds().stream().findFirst();
    }
}
