package io.github.codejinxz.yilian.content.dao.jpa;

import io.github.codejinxz.yilian.content.dao.BankDao;
import io.github.codejinxz.yilian.content.entity.BankVisibility;
import io.github.codejinxz.yilian.content.entity.ContentStatus;
import io.github.codejinxz.yilian.content.entity.SentenceBank;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** BankDao 的 JPA 适配器。 */
@Repository
public class JpaBankDao implements BankDao {

    private final SentenceBankJpaRepository banks;
    private final SentenceJpaRepository sentences;

    /**
     * 注入题库与题目仓库，后者只用于统计题目数。
     *
     * @param banks 题库仓库
     * @param sentences 题目仓库
     */
    public JpaBankDao(SentenceBankJpaRepository banks, SentenceJpaRepository sentences) {
        this.banks = banks;
        this.sentences = sentences;
    }

    @Override
    public SentenceBank save(SentenceBank bank) {
        return banks.save(bank);
    }

    @Override
    public Optional<SentenceBank> findById(long id) {
        return banks.findById(id);
    }

    @Override
    public Optional<SentenceBank> lockById(long id) {
        return banks.lockById(id);
    }

    @Override
    public Page<SentenceBank> searchVisible(
            Long userId, String keyword, BankVisibility visibility, Pageable pageable) {
        return banks.searchVisible(userId, keyword, visibility, pageable);
    }

    @Override
    public Page<SentenceBank> searchAdmin(String keyword, ContentStatus status, Pageable pageable) {
        return banks.searchAdmin(keyword, status, pageable);
    }

    @Override
    public long countPublishedSentences(long bankId) {
        return sentences.countByBankIdAndStatusAndDeletedAtIsNull(bankId, ContentStatus.PUBLISHED);
    }
}
