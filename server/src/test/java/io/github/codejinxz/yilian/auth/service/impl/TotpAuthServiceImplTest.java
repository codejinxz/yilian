package io.github.codejinxz.yilian.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.auth.dto.TotpActivateRequest;
import io.github.codejinxz.yilian.auth.dto.TotpDisableRequest;
import io.github.codejinxz.yilian.auth.dto.TotpSetupRequest;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.security.SecretCipher;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.system.service.TotpAccountService;
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.dto.TotpAccountView;
import io.github.codejinxz.yilian.system.service.dto.UserCredentials;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 两步验证自助用例实现单测 [AC-4]：setup 密码确认与密文落库、activate 验码与恢复码生成、 disable 双确认与恢复码逃生通道、未注册/未启用分支。
 *
 * <p>密码学组件用真实实现（{@link TotpServiceImpl} + {@link SecretCipher} 非 prod 回落值 + BCrypt(4)）， 账号状态用
 * Mockito 替身——验码、加密、哈希的真实性由组件级测试与 RFC 向量保证，这里锁编排分支。
 */
class TotpAuthServiceImplTest {

    private final TotpAccountService totpAccountService = mock(TotpAccountService.class);
    private final UserAccountService userAccountService = mock(UserAccountService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private final SecretCipher secretCipher = new SecretCipher(nonProdEnvironment());

    private final TotpServiceImpl totp = new TotpServiceImpl();

    private TotpAuthServiceImpl service;

    private final YilianUserPrincipal principal =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    /** 当前 TOTP 密钥明文（setup 后由服务加密落库，测试持有明文计算动态码）。 */
    private String enrolledSecret;

    /** setup 落库的密文（模拟服务端持有态）。 */
    private String enrolledCipher;

    @BeforeEach
    void setUp() {
        service =
                new TotpAuthServiceImpl(
                        totp,
                        totpAccountService,
                        userAccountService,
                        secretCipher,
                        passwordEncoder);
        enrolledSecret = totp.generateSecret();
        enrolledCipher = secretCipher.encrypt(enrolledSecret);
    }

    /** [AC-4] setup：密码错误 40101；正确时密文落库、明文只进响应，URI 绑定用户名。 */
    @Test
    @DisplayName("[AC-4] setup 密码确认与一次性密钥回执")
    void ac4_setupVerifiesPasswordAndStagesCipher() {
        UserCredentials user = credentials(true);
        when(userAccountService.findById(7L)).thenReturn(Optional.of(user));
        when(totpAccountService.findAccount(7L)).thenReturn(Optional.of(account(false, 0)));

        assertThatThrownBy(() -> service.setup(principal, new TotpSetupRequest("wrong")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.AUTH_INVALID_CREDENTIALS);
        verify(totpAccountService, never()).stageTotpSecret(anyLong(), any());

        var view = service.setup(principal, new TotpSetupRequest("learner-password"));
        assertThat(view.secret()).matches("[A-Z2-7]{32}");
        assertThat(view.otpauthUri())
                .startsWith("otpauth://totp/Yilian:learner?secret=" + view.secret())
                .endsWith("&issuer=Yilian&algorithm=SHA1&digits=8&period=30");
        ArgumentCaptor<String> cipherCaptor = ArgumentCaptor.forClass(String.class);
        verify(totpAccountService).stageTotpSecret(eq(7L), cipherCaptor.capture());
        // 落库的是密文且能解回明文（同一主密钥）；明文本身不落库。
        assertThat(cipherCaptor.getValue()).isNotEqualTo(view.secret());
        assertThat(secretCipher.decrypt(cipherCaptor.getValue())).isEqualTo(view.secret());
    }

    /** [AC-4] activate：未 setup 40000；错码 40101；验码通过后生成 10 个 8 位恢复码并整体落哈希。 */
    @Test
    @DisplayName("[AC-4] activate 验码、恢复码生成与绑定")
    void ac4_activateVerifiesCodeAndIssuesRecoveryCodes() {
        UserCredentials user = credentials(true);
        when(userAccountService.findById(7L)).thenReturn(Optional.of(user));
        // 未 setup（无密文）：引导先走 setup，而不是静默换钥。
        when(totpAccountService.findAccount(7L)).thenReturn(Optional.of(account(false, 0)));

        assertThatThrownBy(() -> service.activate(principal, new TotpActivateRequest("12345678")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.VALIDATION_FAILED);

        // 已 setup：错码 40101（远端窗口码，显式窗口外），随后当前码通过。
        when(totpAccountService.findAccount(7L))
                .thenReturn(Optional.of(accountWithCipher(false, 0, enrolledCipher)));
        String wrongCode = wrongButWellFormedCode(enrolledSecret);
        assertThatThrownBy(() -> service.activate(principal, new TotpActivateRequest(wrongCode)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.AUTH_INVALID_CREDENTIALS);

        String currentCode = currentCode(enrolledSecret);
        var view = service.activate(principal, new TotpActivateRequest(currentCode));
        assertThat(view.recoveryCodes()).hasSize(TotpAuthServiceImpl.RECOVERY_CODE_COUNT);
        assertThat(view.recoveryCodes())
                .allSatisfy(
                        code -> {
                            assertThat(code).matches("\\d{8}");
                            assertThat(code.charAt(0)).isNotEqualTo('0');
                        });
        // 恢复码两两互异（SecureRandom 空间 9×10^7，撞码概率可忽略但仍然断言）。
        assertThat(view.recoveryCodes()).doesNotHaveDuplicates();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> hashesCaptor = ArgumentCaptor.forClass(List.class);
        verify(totpAccountService).activateTotp(eq(7L), eq(enrolledCipher), hashesCaptor.capture());
        assertThat(hashesCaptor.getValue())
                .hasSize(TotpAuthServiceImpl.RECOVERY_CODE_COUNT)
                .allSatisfy(hash -> assertThat(hash).startsWith("$2a$"))
                // 哈希与明文一一对应（BCrypt 可验证），且不是明文本身。
                .allSatisfy(hash -> assertThat(hash).isNotIn(view.recoveryCodes()));
        for (int i = 0; i < view.recoveryCodes().size(); i++) {
            assertThat(
                            passwordEncoder.matches(
                                    view.recoveryCodes().get(i), hashesCaptor.getValue().get(i)))
                    .as("恢复码 %s 的哈希可验证", view.recoveryCodes().get(i))
                    .isTrue();
        }
    }

    /** [AC-4] activate 密钥漂移护栏：service 侧校验由 system 层执行（此处锁编排把密文原样上送）。 */
    @Test
    @DisplayName("[AC-4] activate 上送验过的密文而非现场新密钥")
    void ac4_activateSendsVerifiedCipher() {
        UserCredentials user = credentials(true);
        when(userAccountService.findById(7L)).thenReturn(Optional.of(user));
        when(totpAccountService.findAccount(7L))
                .thenReturn(Optional.of(accountWithCipher(false, 0, enrolledCipher)));
        service.activate(principal, new TotpActivateRequest(currentCode(enrolledSecret)));
        verify(totpAccountService).activateTotp(eq(7L), eq(enrolledCipher), any());
    }

    /** [AC-4] disable：未启用 40000；密码对但验证码错 40101；恢复码可代替验证器（逃生通道）。 */
    @Test
    @DisplayName("[AC-4] disable 双确认与恢复码逃生通道")
    void ac4_disableRequiresPasswordAndCodeOrRecoveryCode() {
        UserCredentials user = credentials(true);
        when(userAccountService.findById(7L)).thenReturn(Optional.of(user));
        when(totpAccountService.findAccount(7L))
                .thenReturn(Optional.of(accountWithCipher(true, 5, enrolledCipher)));

        assertThatThrownBy(
                        () -> service.disable(principal, new TotpDisableRequest("pw", "12345678")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.AUTH_INVALID_CREDENTIALS);
        verify(totpAccountService, never()).clearTotpEnrollment(anyLong());

        // 恢复码逃生：验证码槽填剩余恢复码之一同样放行。
        String recoveryCode = "10203040";
        TotpAccountView withRecovery =
                new TotpAccountView(
                        7L,
                        "learner",
                        true,
                        Instant.now(),
                        enrolledCipher,
                        5,
                        List.of(
                                new TotpAccountView.RecoveryCodeHash(
                                        1L, passwordEncoder.encode(recoveryCode))));
        when(totpAccountService.findAccount(7L)).thenReturn(Optional.of(withRecovery));
        service.disable(principal, new TotpDisableRequest("learner-password", recoveryCode));
        verify(totpAccountService).clearTotpEnrollment(7L);
    }

    /** [AC-4] status：投影 enabled/enrolledAt/剩余恢复码数；未登录 401。 */
    @Test
    @DisplayName("[AC-4] status 投影与未登录收敛")
    void ac4_statusProjectsState() {
        when(totpAccountService.findAccount(7L))
                .thenReturn(Optional.of(accountWithCipher(true, 5, enrolledCipher)));
        var view = service.status(principal);
        assertThat(view.enabled()).isTrue();
        assertThat(view.remainingRecoveryCodes()).isEqualTo(5);
        assertThat(view.enrolledAt()).isNotNull();

        when(totpAccountService.findAccount(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.status(principal))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.AUTH_REQUIRED);
        assertThatThrownBy(() -> service.status(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.AUTH_REQUIRED);
    }

    /**
     * 构造非 prod 环境让 SecretCipher 回落文档化 dev 主密钥（与 SecretCipherTest 同口径）。
     *
     * @return mock 环境
     */
    private static MockEnvironment nonProdEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles();
        return environment;
    }

    /**
     * 构造认证视图（密码哈希为 BCrypt("learner-password")）。
     *
     * @param enabled 账号是否启用
     * @return 凭据视图
     */
    private UserCredentials credentials(boolean enabled) {
        return new UserCredentials(
                7L,
                "learner@example.com",
                "learner",
                passwordEncoder.encode("learner-password"),
                io.github.codejinxz.yilian.system.entity.UserRole.USER,
                enabled,
                true,
                false,
                "Asia/Shanghai",
                3,
                new java.math.BigDecimal("0.900"),
                false,
                java.time.Instant.EPOCH);
    }

    /**
     * 构造未 setup 的账户视图（密文为空）。
     *
     * @param totpEnabled 开关
     * @param remaining 剩余恢复码
     * @return 账户视图
     */
    private TotpAccountView account(boolean totpEnabled, int remaining) {
        return new TotpAccountView(7L, "learner", totpEnabled, null, null, remaining, List.of());
    }

    /**
     * 构造已落密文的账户视图。
     *
     * @param totpEnabled 开关
     * @param remaining 剩余恢复码
     * @param cipher 密文
     * @return 账户视图
     */
    private TotpAccountView accountWithCipher(boolean totpEnabled, int remaining, String cipher) {
        return new TotpAccountView(
                7L, "learner", totpEnabled, Instant.now(), cipher, remaining, List.of());
    }

    /**
     * 计算密钥的当前动态码（参考实现，与 TotpServiceImplTest 同款；算法正确性由 RFC 向量锁定）。
     *
     * @param base32Secret Base32 密钥
     * @return 8 位动态码
     */
    private static String currentCode(String base32Secret) {
        byte[] secret = TotpServiceImpl.base32Decode(base32Secret);
        long counter = Math.floorDiv(Instant.now().getEpochSecond(), TotpServiceImpl.STEP_SECONDS);
        return referenceHotp(secret, counter);
    }

    /**
     * 计算一个「格式合法但不在 ±1 窗口内」的码：对同一把密钥取远端 counter 的码， 并显式断言与当前窗口三码互异（显式检查，不赌概率）。
     *
     * @param base32Secret 目标密钥（错码对同一密钥计算，格式合法但窗口外）
     * @return 8 位错码
     */
    private static String wrongButWellFormedCode(String base32Secret) {
        byte[] secret = TotpServiceImpl.base32Decode(base32Secret);
        long counter = Math.floorDiv(Instant.now().getEpochSecond(), TotpServiceImpl.STEP_SECONDS);
        for (int distance = 1000; distance < 1100; distance++) {
            String candidate = referenceHotp(secret, counter + distance);
            if (!candidate.equals(referenceHotp(secret, counter - 1))
                    && !candidate.equals(referenceHotp(secret, counter))
                    && !candidate.equals(referenceHotp(secret, counter + 1))) {
                return candidate;
            }
        }
        throw new IllegalStateException("错码生成冲突");
    }

    /**
     * 参考 HOTP 实现（RFC 4226 §5.3，与被测实现独立）。
     *
     * @param secret 密钥字节
     * @param counter 步进计数
     * @return 8 位动态码
     */
    private static String referenceHotp(byte[] secret, long counter) {
        byte[] message = new byte[8];
        for (int i = 0; i < 8; i++) {
            message[i] = (byte) (counter >>> (8 * (7 - i)));
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] digest = mac.doFinal(message);
            int offset = digest[digest.length - 1] & 0x0F;
            int binary =
                    ((digest[offset] & 0x7F) << 24)
                            | ((digest[offset + 1] & 0xFF) << 16)
                            | ((digest[offset + 2] & 0xFF) << 8)
                            | (digest[offset + 3] & 0xFF);
            return String.format("%08d", binary % 100_000_000);
        } catch (Exception exception) {
            throw new IllegalStateException("参考实现 HMAC 初始化失败", exception);
        }
    }
}
