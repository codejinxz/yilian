package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.FsrsParameterVersionDao;
import io.github.codejinxz.yilian.practice.entity.FsrsParameterVersion;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** FSRS 参数版本 Dao 的 JPA 实现。 */
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
    public Optional<FsrsParameterVersion> findActiveGlobal() {
        return versions.findActiveGlobals().stream().findFirst();
    }

    @Override
    public List<FsrsParameterVersion> findAllByRecency() {
        return versions.findAllByRecency();
    }

    @Override
    public Optional<FsrsParameterVersion> findById(Long id) {
        return versions.findById(id);
    }

    @Override
    public FsrsParameterVersion save(FsrsParameterVersion version) {
        return versions.save(version);
    }

    @Override
    public int demoteActiveGlobal() {
        return versions.demoteActiveGlobals();
    }
}
