package io.github.codejinxz.yilian.system.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.system.dao.AppUserDao;
import io.github.codejinxz.yilian.system.dao.User2faRecoveryCodeDao;
import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.entity.User2faRecoveryCode;
import io.github.codejinxz.yilian.system.service.TotpAccountService;
import io.github.codejinxz.yilian.system.service.dto.TotpAccountView;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 用户 2FA 账户用例实现：users 三列与恢复码行的原子迁移，零明文接触。 */
@Service
public class TotpAccountServiceImpl implements TotpAccountService {

    private final AppUserDao appUserDao;
    private final User2faRecoveryCodeDao recoveryCodeDao;

    /**
     * 注入账号 Dao 与恢复码 Dao。
     *
     * @param appUserDao 账号 Dao
     * @param recoveryCodeDao 恢复码 Dao
     */
    public TotpAccountServiceImpl(AppUserDao appUserDao, User2faRecoveryCodeDao recoveryCodeDao) {
        this.appUserDao = appUserDao;
        this.recoveryCodeDao = recoveryCodeDao;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TotpAccountView> findAccount(long userId) {
        return appUserDao
                .findById(userId)
                .map(
                        user ->
                                new TotpAccountView(
                                        user.getId(),
                                        user.getUsername(),
                                        user.isTotpEnabled(),
                                        user.getTotpEnrolledAt(),
                                        user.getTotpSecretCipher(),
                                        unusedCodes(user.getId()).size(),
                                        unusedHashes(user.getId())));
    }

    @Override
    @Transactional
    public void stageTotpSecret(long userId, String secretCipher) {
        AppUser user = requireUser(userId);
        user.stageTotpSecret(secretCipher);
        appUserDao.save(user);
    }

    @Override
    @Transactional
    public void activateTotp(long userId, String expectedCipher, List<String> recoveryCodeHashes) {
        AppUser user = requireUser(userId);
        // 密文漂移护栏：setup 与 activate 之间若并发重跑 setup 覆盖了密文，这里拒绝——
        // 验过的码必须对上要绑定的密钥，否则等于启用了一把未经验证的密钥。
        if (!Objects.equals(user.getTotpSecretCipher(), expectedCipher)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "两步验证注册已刷新，请重新开始");
        }
        // 恢复码整体重发：先清旧行再插新行（凭据投影的受控替换，非历史抹除）。
        recoveryCodeDao.deleteByUserId(userId);
        Instant now = Instant.now();
        for (String hash : recoveryCodeHashes) {
            recoveryCodeDao.save(User2faRecoveryCode.unused(userId, hash, now));
        }
        user.activateTotp(expectedCipher, now);
        appUserDao.save(user);
    }

    @Override
    @Transactional
    public void clearTotpEnrollment(long userId) {
        AppUser user = requireUser(userId);
        user.clearTotp();
        appUserDao.save(user);
        // 三列清空与恢复码删行同事务：两步验证关闭后旧恢复码必须立即失效。
        recoveryCodeDao.deleteByUserId(userId);
    }

    @Override
    @Transactional
    public void markRecoveryCodeUsed(long userId, long codeId) {
        // 条件更新原子化「查未用→标记」：used_at IS NULL 下沉到 UPDATE 的 WHERE，
        // 两笔并发消费同码时后到者影响行数 0——单次语义由数据库原子性保证，
        // 不存在「读-判-写」竞态窗口。
        if (!recoveryCodeDao.markUsedIfUnused(userId, codeId, Instant.now())) {
            throw new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS, "恢复码无效或已使用");
        }
    }

    /**
     * 取未使用恢复码行（哈希比对由 auth 侧完成，本层不接触明文）。
     *
     * @param userId 用户 id
     * @return 未使用行
     */
    private List<User2faRecoveryCode> unusedCodes(long userId) {
        return recoveryCodeDao.findUnusedByUserId(userId);
    }

    /**
     * 未使用恢复码的 id + 哈希投影。
     *
     * @param userId 用户 id
     * @return 投影列表
     */
    private List<TotpAccountView.RecoveryCodeHash> unusedHashes(long userId) {
        return unusedCodes(userId).stream()
                .map(code -> new TotpAccountView.RecoveryCodeHash(code.getId(), code.getCodeHash()))
                .toList();
    }

    /**
     * 加载用户，不存在按 40400 收敛。
     *
     * @param userId 用户 id
     * @return 账号实体
     */
    private AppUser requireUser(long userId) {
        return appUserDao
                .findById(userId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "用户不存在"));
    }
}
