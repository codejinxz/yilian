package io.github.codejinxz.yilian.auth.service.impl;

import io.github.codejinxz.yilian.auth.dto.TotpActivateRequest;
import io.github.codejinxz.yilian.auth.dto.TotpDisableRequest;
import io.github.codejinxz.yilian.auth.dto.TotpSetupRequest;
import io.github.codejinxz.yilian.auth.service.TotpAuthService;
import io.github.codejinxz.yilian.auth.service.TotpService;
import io.github.codejinxz.yilian.auth.vo.TotpActivationView;
import io.github.codejinxz.yilian.auth.vo.TotpSetupView;
import io.github.codejinxz.yilian.auth.vo.TotpStatusView;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.security.SecretCipher;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.system.service.TotpAccountService;
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.dto.TotpAccountView;
import io.github.codejinxz.yilian.system.service.dto.UserCredentials;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 两步验证自助用例实现：TOTP 密钥生成/验码（{@link TotpService}）、密文加密（{@link SecretCipher}）与账号状态落库（system {@code
 * TotpAccountService}）在这里编排。
 *
 * <p>安全口径：密码确认走 BCrypt（与登录同一编码器，哈希经 {@code UserCredentials} 只在
 * 认证模块内存中短暂存在）；密钥/恢复码明文只在内存短暂存在，本类不打印任何含明文或密文 的日志；密文落库前绝不回显。
 */
@Service
public class TotpAuthServiceImpl implements TotpAuthService {

    /** 一次性恢复码数量。 */
    static final int RECOVERY_CODE_COUNT = 10;

    /** 恢复码取值下界（含）：1–9 开头避免前导零造成抄写歧义。 */
    static final int RECOVERY_CODE_MIN = 10_000_000;

    /** 恢复码取值上界（exclusive）。 */
    private static final int RECOVERY_CODE_MAX = 100_000_000;

    private final TotpService totpService;
    private final TotpAccountService totpAccountService;
    private final UserAccountService userAccountService;
    private final SecretCipher secretCipher;
    private final PasswordEncoder passwordEncoder;

    /** 恢复码随机源；实例级复用（线程安全），只产恢复码不产 TOTP 密钥。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 注入算法、账号状态、凭据读取、加密与密码编码器依赖。
     *
     * @param totpService TOTP 算法服务
     * @param totpAccountService 账号 2FA 状态用例（system 接口）
     * @param userAccountService 账号用例（读 UserCredentials 供密码比对，system 接口）
     * @param secretCipher AES-256-GCM 加密基建（common）
     * @param passwordEncoder BCrypt 编码器（密码确认与恢复码哈希）
     */
    public TotpAuthServiceImpl(
            TotpService totpService,
            TotpAccountService totpAccountService,
            UserAccountService userAccountService,
            SecretCipher secretCipher,
            PasswordEncoder passwordEncoder) {
        this.totpService = totpService;
        this.totpAccountService = totpAccountService;
        this.userAccountService = userAccountService;
        this.secretCipher = secretCipher;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public TotpStatusView status(YilianUserPrincipal principal) {
        TotpAccountView account = requireAccount(principal);
        return new TotpStatusView(
                account.totpEnabled(), account.enrolledAt(), account.remainingRecoveryCodes());
    }

    @Override
    public TotpSetupView setup(YilianUserPrincipal principal, TotpSetupRequest request) {
        TotpAccountView account = requireAccount(principal);
        verifyPassword(principal.id(), request.password());
        // 注册是独立能力，不要求已启用（启用前/关闭后都可重新注册）；重复 setup 覆盖旧密文，
        // 旧验证器绑定的密钥即作废。
        String secret = totpService.generateSecret();
        totpAccountService.stageTotpSecret(principal.id(), secretCipher.encrypt(secret));
        return new TotpSetupView(secret, totpService.otpauthUri(secret, account.username()));
    }

    @Override
    public TotpActivationView activate(YilianUserPrincipal principal, TotpActivateRequest request) {
        TotpAccountView account = requireAccount(principal);
        if (account.secretCipher() == null) {
            // 没有落过密文无从验码：引导先走 setup，而不是静默生成新密钥（会让用户绑定未知密钥）。
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "请先完成两步验证注册（setup）");
        }
        String cipher = account.secretCipher();
        // 密文只在这里解密进内存验码，不落日志；验的是「落库的那把密钥」，绑定即验证过。
        String plaintextSecret = secretCipher.decrypt(cipher);
        if (!totpService.verify(plaintextSecret, request.code(), Instant.now())) {
            throw new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS, "验证码不正确，请重试");
        }
        List<String> codes = generateRecoveryCodes();
        List<String> hashes = codes.stream().map(passwordEncoder::encode).toList();
        totpAccountService.activateTotp(principal.id(), cipher, hashes);
        return new TotpActivationView(codes);
    }

    @Override
    public void disable(YilianUserPrincipal principal, TotpDisableRequest request) {
        TotpAccountView account = requireAccount(principal);
        if (!account.totpEnabled()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "两步验证未开启");
        }
        verifyPassword(principal.id(), request.password());
        // 关闭同样接受恢复码：验证器丢失时用户仍可凭剩余恢复码自助关闭（公网锁死的另一条出口）。
        String plaintextSecret = secretCipher.decrypt(account.secretCipher());
        boolean codeOk =
                totpService.verify(plaintextSecret, request.code(), Instant.now())
                        || account.unusedRecoveryCodes().stream()
                                .anyMatch(
                                        candidate ->
                                                passwordEncoder.matches(
                                                        request.code(), candidate.codeHash()));
        if (!codeOk) {
            throw new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS, "验证码不正确，请重试");
        }
        totpAccountService.clearTotpEnrollment(principal.id());
    }

    /**
     * 生成 10 个 8 位恢复码明文（首位非零）；BCrypt 哈希由调用点在落库前完成。
     *
     * @return 恢复码明文列表
     */
    private List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            codes.add(
                    Integer.toString(
                            RECOVERY_CODE_MIN
                                    + secureRandom.nextInt(RECOVERY_CODE_MAX - RECOVERY_CODE_MIN)));
        }
        return codes;
    }

    /**
     * 密码确认：与登录同一 BCrypt 口径；账号不可用时统一 401，不区分原因。
     *
     * @param userId 用户 id
     * @param password 提交的当前密码
     */
    private void verifyPassword(long userId, String password) {
        String passwordHash =
                userAccountService
                        .findById(userId)
                        .map(UserCredentials::passwordHash)
                        .orElseThrow(this::authRequired);
        if (!passwordEncoder.matches(password, passwordHash)) {
            throw new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS, "当前密码不正确");
        }
    }

    /**
     * 加载当前用户的 2FA 账户状态；principal 缺失或用户不存在统一按未登录收敛。
     *
     * @param principal 当前用户
     * @return 2FA 账户视图
     */
    private TotpAccountView requireAccount(YilianUserPrincipal principal) {
        if (principal == null) {
            throw authRequired();
        }
        return totpAccountService.findAccount(principal.id()).orElseThrow(this::authRequired);
    }

    /**
     * 统一 401 业务异常。
     *
     * @return AUTH_REQUIRED 业务异常
     */
    private BusinessException authRequired() {
        return new BusinessException(ApiCode.AUTH_REQUIRED);
    }
}
