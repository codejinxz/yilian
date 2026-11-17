package io.github.codejinxz.yilian.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.security.SecretCipher.SecretCipherException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.Base64;

/**
 * {@link SecretCipher} 单测 [AC-2]：跨模块共享加密基建的行为契约——AES-256-GCM 往返、 错主密钥解密失败、密文篡改失败、IV 唯一性、主密钥解析
 * （hex/base64/原始口令）与 prod fail-fast 纪律。provider 域薄委托 {@code ProviderKeyCrypto}
 * 的兼容性由其既有测试锁定，此处锁定新家的行为。
 */
class SecretCipherTest {

    /** 与 JWT secret 同纪律的原始口令形态（≥32 字符）；SHA-256 派生为 AES-256 密钥。 */
    private static final String RAW_PASSPHRASE = "test-only-yilian-master-key-0123456789abcdef";

    /** openssl rand -hex 32 风格的 32 字节密钥（hex 编码）。 */
    private static final String HEX_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** openssl rand -base64 32 风格的 32 字节密钥（base64 编码）。 */
    private static final String BASE64_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    /**
     * 以指定主密钥构造实例（非 prod 环境）。
     *
     * @param masterKey 主密钥字符串
     * @return 加解密实例
     */
    private SecretCipher cipherWith(String masterKey) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(environment.getProperty(SecretCipher.MASTER_KEY_ENV)).thenReturn(masterKey);
        return new SecretCipher(environment);
    }

    /** [AC-2] 往返：encrypt → decrypt 还原明文；三种主密钥形态（hex/base64/原始口令）行为一致。 */
    @Test
    @DisplayName("[AC-2] 三种主密钥形态下加解密往返一致")
    void ac2_roundTripAcrossMasterKeyShapes() {
        for (String masterKey : new String[] {RAW_PASSPHRASE, HEX_KEY, BASE64_KEY}) {
            SecretCipher cipher = cipherWith(masterKey);
            String plaintext = "totp-secret-abc234567-plaintext";
            String encrypted = cipher.encrypt(plaintext);
            assertThat(encrypted).isNotBlank().isNotEqualTo(plaintext);
            assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
        }
    }

    /** [AC-2] 错主密钥解密失败：主密钥换了之后旧密文不可解（auth tag 不符），按异常透出。 */
    @Test
    @DisplayName("[AC-2] 错主密钥解密失败")
    void ac2_decryptFailsWithWrongMasterKey() {
        SecretCipher original = cipherWith(RAW_PASSPHRASE);
        String encrypted = original.encrypt("secret-value");

        SecretCipher rotated = cipherWith("another-test-only-master-key-0123456789abcdefx");
        assertThatThrownBy(() -> rotated.decrypt(encrypted))
                .isInstanceOf(SecretCipherException.class)
                .hasMessageContaining("解密失败");
    }

    /** [AC-2] 密文篡改失败：翻转密文字节后 auth tag 校验不通过（tag 尾字节与密文主体各验一次）。 */
    @Test
    @DisplayName("[AC-2] 篡改密文解密失败")
    void ac2_decryptFailsWhenCipherTampered() {
        SecretCipher cipher = cipherWith(HEX_KEY);
        String encrypted = cipher.encrypt("secret-value");

        byte[] payload = Base64.getDecoder().decode(encrypted);
        payload[payload.length - 1] ^= 0x01; // 破坏 tag 尾字节
        String tampered = Base64.getEncoder().encodeToString(payload);
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(SecretCipherException.class);

        byte[] payload2 = Base64.getDecoder().decode(encrypted);
        payload2[payload2.length / 2] ^= 0x80; // 破坏密文主体字节
        String tampered2 = Base64.getEncoder().encodeToString(payload2);
        assertThatThrownBy(() -> cipher.decrypt(tampered2))
                .isInstanceOf(SecretCipherException.class);
    }

    /** [AC-2] 密文长度非法：不足 IV + tag 的 Base64 串按同一异常归一，不落 JCE 裸异常。 */
    @Test
    @DisplayName("[AC-2] 过短密文拒绝")
    void ac2_shortCipherRejected() {
        SecretCipher cipher = cipherWith(RAW_PASSPHRASE);
        String tooShort = Base64.getEncoder().encodeToString(new byte[4]);
        assertThatThrownBy(() -> cipher.decrypt(tooShort))
                .isInstanceOf(SecretCipherException.class)
                .hasMessageContaining("密文长度非法");
    }

    /** [AC-2] IV 唯一性：同一明文两次加密得到不同密文（随机 IV），且 IV 段字节不同、两次都能解回。 */
    @Test
    @DisplayName("[AC-2] 随机 IV 每次加密唯一")
    void ac2_ivUniquePerEncryption() {
        SecretCipher cipher = cipherWith(RAW_PASSPHRASE);
        String plaintext = "same-plaintext";
        String encrypted1 = cipher.encrypt(plaintext);
        String encrypted2 = cipher.encrypt(plaintext);

        assertThat(encrypted1).isNotEqualTo(encrypted2);
        byte[] payload1 = Base64.getDecoder().decode(encrypted1);
        byte[] payload2 = Base64.getDecoder().decode(encrypted2);
        // 前 12 字节是 IV：两次加密的 IV 不应相同（SecureRandom 现场生成）。
        byte[] iv1 = new byte[12];
        byte[] iv2 = new byte[12];
        System.arraycopy(payload1, 0, iv1, 0, 12);
        System.arraycopy(payload2, 0, iv2, 0, 12);
        assertThat(iv1).isNotEqualTo(iv2);
        // 密文不同但都能解回同一明文。
        assertThat(cipher.decrypt(encrypted1)).isEqualTo(plaintext);
        assertThat(cipher.decrypt(encrypted2)).isEqualTo(plaintext);
    }

    /** [AC-2] prod fail-fast：主密钥缺失启动失败；local-dev-only 开发值 prod 拒绝。 */
    @Test
    @DisplayName("[AC-2] prod 主密钥缺失/开发值启动失败")
    void ac2_prodFailsFastOnMissingOrDevMasterKey() {
        Environment prodMissing = mock(Environment.class);
        when(prodMissing.getActiveProfiles()).thenReturn(new String[] {"prod"});
        when(prodMissing.getProperty(anyString())).thenReturn(null);
        assertThatThrownBy(() -> new SecretCipher(prodMissing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YILIAN_SECRET_MASTER_KEY");

        Environment prodDevValue = mock(Environment.class);
        when(prodDevValue.getActiveProfiles()).thenReturn(new String[] {"prod"});
        when(prodDevValue.getProperty(SecretCipher.MASTER_KEY_ENV))
                .thenReturn(SecretCipher.LOCAL_DEV_DEFAULT_KEY);
        assertThatThrownBy(() -> new SecretCipher(prodDevValue))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local-dev-only");
    }

    /** [AC-2] 非 prod 缺值回落文档化 dev 默认（local 纪律）：可用但带告警，不阻塞启动。 */
    @Test
    @DisplayName("[AC-2] 非 prod 缺值回落 dev 默认且可用")
    void ac2_nonProdFallsBackToDocumentedDevDefault() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(environment.getProperty(anyString())).thenReturn(null);
        SecretCipher cipher = new SecretCipher(environment);
        String encrypted = cipher.encrypt("local-dev-plaintext");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("local-dev-plaintext");
    }

    /** [AC-2] 短主密钥（<32 字节）拒绝：任何形态下都启动失败，不让弱密钥上线。 */
    @Test
    @DisplayName("[AC-2] 短主密钥拒绝")
    void ac2_shortMasterKeyRejected() {
        assertThatThrownBy(() -> cipherWith("short-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少需要 32 字节");
    }

    /** [AC-2] last4：尾 4 位辨识号；少于 4 位取全串。 */
    @Test
    @DisplayName("[AC-2] last4 取尾 4 位")
    void ac2_last4TakesTrailingFourChars() {
        assertThat(SecretCipher.last4("sk-abcdefgh")).isEqualTo("efgh");
        assertThat(SecretCipher.last4("abc")).isEqualTo("abc");
        assertThat(SecretCipher.last4("abcd")).isEqualTo("abcd");
    }
}
