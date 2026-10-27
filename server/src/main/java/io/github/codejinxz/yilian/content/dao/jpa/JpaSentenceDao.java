package io.github.codejinxz.yilian.content.dao.jpa;

import io.github.codejinxz.yilian.content.dao.SentenceDao;
import io.github.codejinxz.yilian.content.entity.Sentence;
import io.github.codejinxz.yilian.content.entity.SentenceVersion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** SentenceDao 的 JPA 适配器。 */
@Repository
public class JpaSentenceDao implements SentenceDao {

    private final SentenceJpaRepository sentences;
    private final SentenceVersionJpaRepository versions;

    /**
     * 注入题目与版本仓库。
     *
     * @param sentences 题目仓库
     * @param versions 版本仓库
     */
    public JpaSentenceDao(SentenceJpaRepository sentences, SentenceVersionJpaRepository versions) {
        this.sentences = sentences;
        this.versions = versions;
    }

    @Override
    public Sentence save(Sentence sentence) {
        return sentences.saveAndFlush(sentence);
    }

    @Override
    public Optional<Sentence> findById(String id) {
        return sentences.findById(id);
    }

    @Override
    public Optional<Sentence> findByExternalKey(long bankId, String externalKey) {
        return sentences.findByBankIdAndExternalKey(bankId, externalKey);
    }

    @Override
    public Optional<Sentence> findActiveByFingerprint(long bankId, String fingerprint) {
        return sentences.findActiveByFingerprint(bankId, fingerprint);
    }

    @Override
    public List<Sentence> findChangedSince(long bankId, long sinceVersion, int limit) {
        return sentences.findChangedSince(bankId, sinceVersion, PageRequest.of(0, limit));
    }

    @Override
    public Page<Sentence> findPageByBank(long bankId, Pageable pageable) {
        return sentences.findByBankIdOrderByCreatedAtDesc(bankId, pageable);
    }

    @Override
    public List<Sentence> findPracticeCandidates(Long bankId, String excludeSentenceId, int limit) {
        return sentences.findPracticeCandidates(
                bankId, excludeSentenceId, PageRequest.of(0, limit));
    }

    @Override
    public SentenceVersion saveVersion(SentenceVersion version) {
        return versions.saveAndFlush(version);
    }

    @Override
    public List<SentenceVersion> findVersionsByIds(Collection<Long> ids) {
        return ids.isEmpty() ? List.of() : versions.findByIdIn(ids);
    }

    @Override
    public Optional<SentenceVersion> findLatestVersion(String sentenceId) {
        return versions.findFirstBySentenceIdOrderByVersionNoDesc(sentenceId);
    }
}
